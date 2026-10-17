package dev.onelili.mstock.futures;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.TradingRepository;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.leverage.TradeOutcome;
import dev.onelili.mstock.lifecycle.LifecycleTaskManager;
import dev.onelili.mstock.market.ForexProvider;
import dev.onelili.mstock.model.FuturesContract;
import dev.onelili.mstock.model.FuturesPosition;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.TradeDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * 期货服务：开多 / 开空、每日盯市、到期自动平仓。
 *
 * <p>与 {@code LeverageService} 共用 {@link SymbolPriceSource} 与 {@link TradeOutcome} 约定，
 * 保证指令与 GUI 走同一条路径。
 */
public class FuturesService {

    private final MineStock plugin;
    private final TradingRepository repository;
    private final FuturesEngine engine = new FuturesEngine();
    private final Logger logger;

    private volatile ForexProvider provider;
    private volatile MainConfig config;
    /** 合约目录缓存：避免每次打开 GUI 都重新拉取 symbols。 */
    private volatile Map<String, FuturesContract> contracts = new LinkedHashMap<>();

    public FuturesService(MineStock plugin, ForexProvider provider,
                          TradingRepository repository, MainConfig config) {
        this.plugin = plugin;
        this.provider = provider;
        this.repository = repository;
        this.config = config;
        this.logger = plugin.getLogger();
        this.contracts = FuturesContractRegistry.fromConfig(
                config.getFuturesContracts(), config.getFuturesDefaultInitialMarginRate());
    }

    public synchronized void reconfigure(ForexProvider provider, MainConfig config) {
        this.provider = provider;
        this.config = config;
        this.contracts = FuturesContractRegistry.fromConfig(
                config.getFuturesContracts(), config.getFuturesDefaultInitialMarginRate());
    }

    // ── 合约目录 ──────────────────────────────────────────────────────────────

    /** 配置中显式声明的合约；为空时由 biquote 派生，失败则用内置表。 */
    public synchronized Map<String, FuturesContract> contracts() {
        return contracts;
    }

    /** 异步加载合约目录（配置为空时拉取 biquote 品种元数据）。 */
    public CompletableFuture<Map<String, FuturesContract>> loadContracts() {
        MainConfig currentConfig = config;
        double rate = currentConfig.getFuturesDefaultInitialMarginRate();
        Map<String, FuturesContract> configured = FuturesContractRegistry.fromConfig(
                currentConfig.getFuturesContracts(), rate);
        if (!configured.isEmpty()) {
            synchronized (this) {
                contracts = configured;
            }
            return CompletableFuture.completedFuture(configured);
        }
        synchronized (this) {
            if (!contracts.isEmpty()) return CompletableFuture.completedFuture(contracts);
        }
        ForexProvider current = provider;
        LocalDate today = LocalDate.now();
        if (current == null) {
            return CompletableFuture.completedFuture(cache(FuturesContractRegistry.fallback(rate, today)));
        }
        return current.getActiveSymbols()
                .thenApply(symbols -> {
                    Map<String, FuturesContract> derived =
                            FuturesContractRegistry.fromSymbols(symbols, rate, today);
                    if (derived.isEmpty()) derived = FuturesContractRegistry.fallback(rate, today);
                    return cache(derived);
                })
                .exceptionally(error -> {
                    logger.warning("[MineStock] 期货合约目录加载失败，使用内置表: "
                            + error.getMessage());
                    return cache(FuturesContractRegistry.fallback(rate, today));
                });
    }

    private Map<String, FuturesContract> cache(Map<String, FuturesContract> loaded) {
        synchronized (this) {
            contracts = loaded;
        }
        return loaded;
    }

    /** 未到期合约列表。 */
    public CompletableFuture<List<FuturesContract>> activeContracts() {
        LocalDate today = LocalDate.now();
        return loadContracts().thenApply(map -> FuturesContractRegistry.activeOnly(map, today));
    }

    // ── 开仓 ──────────────────────────────────────────────────────────────────

    public CompletableFuture<TradeOutcome> open(UUID playerUuid, String symbol,
                                                TradeDirection direction, double quantity) {
        if (direction == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-direction"));
        }
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-quantity"));
        }
        String normalized = normalize(symbol);
        if (normalized == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-symbol"));
        }

        return loadContracts().thenCompose(map -> {
            FuturesContract contract = map.get(normalized);
            if (contract == null) {
                return CompletableFuture.completedFuture(
                        TradeOutcome.fail("futures-contract-not-found", "symbol", normalized));
            }
            LocalDate today = LocalDate.now();
            if (contract.isExpired(today)) {
                return CompletableFuture.completedFuture(
                        TradeOutcome.fail("futures-contract-expired", "symbol", normalized));
            }
            return priceOf(normalized).thenCompose(price -> doOpen(
                    playerUuid, contract, direction, quantity, price, today));
        });
    }

    private CompletableFuture<TradeOutcome> doOpen(UUID playerUuid, FuturesContract contract,
                                                   TradeDirection direction, double quantity,
                                                   double price, LocalDate today) {
        if (!(price > 0) || !Double.isFinite(price)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-no-price"));
        }
        double margin = round2(engine.requiredMargin(contract, price, quantity));
        if (margin <= 0) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-quantity"));
        }

        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));

        return tasks.supplyAsync(() -> {
            AtomicBoolean withdrawn = new AtomicBoolean();
            try {
                EconomyService economy = plugin.getEconomy();
                if (economy == null || !economy.isReady()) {
                    return TradeOutcome.fail("vault-not-found");
                }
                org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
                if (!economy.has(offline, margin)) {
                    return TradeOutcome.fail("not-enough-money",
                            "need", format2(margin),
                            "balance", format2(economy.getBalance(offline)));
                }
                if (!economy.withdraw(offline, margin)) {
                    return TradeOutcome.fail("not-enough-money",
                            "need", format2(margin),
                            "balance", format2(economy.getBalance(offline)));
                }
                withdrawn.set(true);

                FuturesPosition position = new FuturesPosition(
                        -1L, playerUuid, contract.getSymbol(), direction, quantity, price,
                        contract.getContractSize(), contract.getInitialMarginRate(),
                        margin, contract.getExpiryDate(), price, 0.0,
                        System.currentTimeMillis());
                long id = repository.insertFuturesPosition(position);

                return TradeOutcome.ok("futures-open-success",
                        "id", String.valueOf(id),
                        "symbol", contract.getSymbol(),
                        "direction", direction.name(),
                        "quantity", formatQty(quantity),
                        "price", format2(price),
                        "leverage", String.valueOf(contract.getLeverage()),
                        "margin", format2(margin),
                        "expiry", contract.getExpiryDate());
            } catch (SQLException e) {
                logger.warning("[MineStock] 期货开仓写库失败 " + contract.getSymbol()
                        + ": " + e.getMessage());
                if (withdrawn.get()) refund(playerUuid, margin, "期货开仓");
                return TradeOutcome.fail("db-error");
            } catch (RuntimeException e) {
                logger.warning("[MineStock] 期货开仓异常: " + e.getMessage());
                if (withdrawn.get()) refund(playerUuid, margin, "期货开仓");
                return TradeOutcome.fail("db-error");
            }
        });
    }

    // ── 平仓 ──────────────────────────────────────────────────────────────────

    public CompletableFuture<TradeOutcome> close(UUID playerUuid, long positionId) {
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));

        return tasks.supplyAsync(() -> {
            FuturesPosition position;
            try {
                position = repository.findFuturesPosition(playerUuid, positionId);
            } catch (SQLException e) {
                logger.warning("[MineStock] 读取期货持仓失败 #" + positionId + ": " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            if (position == null) return TradeOutcome.fail("futures-position-not-found");

            Double price = fetchPrice(position.getSymbol());
            if (price == null) return TradeOutcome.fail("trade-no-price");
            return settleClose(position, price, false);
        });
    }

    // ── 查询 ──────────────────────────────────────────────────────────────────

    /**
     * 按方向平掉某合约上的全部持仓（期货 GUI 的「平多 / 平空」按键）。
     *
     * <p>期货持仓以单个 id 为单位平仓，而 GUI 的交易页只认识「合约 + 方向」，
     * 因此在服务端把该方向上的持仓全部结清，并汇总成一条结果返回。
     *
     * @param direction 只平这个方向的持仓；{@code null} 视为非法请求
     */
    public CompletableFuture<TradeOutcome> closeByDirection(UUID playerUuid, String symbol,
                                                            TradeDirection direction) {
        if (direction == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-direction"));
        }
        String normalized = normalize(symbol);
        if (normalized == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-symbol"));
        }
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));

        return tasks.supplyAsync(() -> {
            List<FuturesPosition> all;
            try {
                all = repository.findFuturesPositions(playerUuid);
            } catch (SQLException e) {
                logger.warning("[MineStock] 读取期货持仓失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            List<FuturesPosition> targets = new ArrayList<>();
            for (FuturesPosition position : all) {
                if (normalized.equals(position.getSymbol())
                        && engine.directionOf(position) == direction) {
                    targets.add(position);
                }
            }
            if (targets.isEmpty()) {
                return TradeOutcome.fail("futures-no-positions",
                        "symbol", normalized,
                        "direction", direction.name());
            }

            int closed = 0;
            double totalPnl = 0.0;
            double totalPayout = 0.0;
            for (FuturesPosition position : targets) {
                Double price = fetchPrice(normalized);
                if (price == null) continue;
                double pnl = round2(engine.totalPnl(position, price));
                TradeOutcome outcome = settleClose(position, price, false);
                if (outcome.success()) {
                    closed++;
                    totalPnl += pnl;
                    totalPayout += engine.closePayout(position, price);
                }
            }
            if (closed == 0) {
                return TradeOutcome.fail("trade-no-price", "symbol", normalized);
            }
            return TradeOutcome.ok("futures-close-all-success",
                    "symbol", normalized,
                    "direction", direction.name(),
                    "count", String.valueOf(closed),
                    "pnl", (totalPnl >= 0 ? "+" : "") + format2(totalPnl),
                    "payout", format2(totalPayout));
        });
    }

    public CompletableFuture<List<FuturesPosition>> positions(UUID playerUuid) {
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) return CompletableFuture.completedFuture(List.of());
        return tasks.supplyAsync(() -> {
            try {
                return repository.findFuturesPositions(playerUuid);
            } catch (SQLException e) {
                logger.warning("[MineStock] 读取期货持仓失败: " + e.getMessage());
                return List.of();
            }
        });
    }

    // ── 每日盯市 + 到期平仓 ──────────────────────────────────────────────────

    /**
     * 扫描全部期货持仓：先做每日盯市结算，再对到期持仓自动平仓。
     *
     * <p>结算价取自 biquote 日线（由 {@link #dailySettlePrice} 决定），不依赖外部价格源。
     *
     * @param onSettled 回调，携带持仓与结算结果（{@code futures-settled} 或 {@code futures-expired}）
     */
    public void settleAll(BiConsumer<FuturesPosition, TradeOutcome> onSettled) {
        List<FuturesPosition> positions;
        try {
            positions = repository.findAllOpenFuturesPositions();
        } catch (SQLException e) {
            logger.warning("[MineStock] 期货盯市读取持仓失败: " + e.getMessage());
            return;
        }
        LocalDate today = LocalDate.now();
        for (FuturesPosition position : positions) {
            try {
                settleOne(position, today, onSettled);
            } catch (RuntimeException e) {
                logger.warning("[MineStock] 期货结算异常 #" + position.getId() + ": " + e.getMessage());
            }
        }
    }

    private void settleOne(FuturesPosition position, LocalDate today,
                           BiConsumer<FuturesPosition, TradeOutcome> onSettled) {
        Double settlePrice = dailySettlePrice(position.getSymbol());
        if (settlePrice == null) {
            Double latest = fetchPrice(position.getSymbol());
            settlePrice = engine.chooseSettlePrice(null, latest);
        }
        if (settlePrice == null || settlePrice <= 0) return;

        if (position.isExpired(today)) {
            TradeOutcome outcome = settleClose(position, settlePrice, true);
            if (outcome.success() && onSettled != null) onSettled.accept(position, outcome);
            return;
        }

        // 同一结算日内只结算一次：以最后结算价是否变化作为"跨日"判据。
        if (samePrice(position.getLastSettlePrice(), settlePrice)) return;

        double pnl = round2(engine.settlementPnl(position, settlePrice));
        double realized = round2(position.getRealizedPnl() + pnl);
        try {
            repository.updateFuturesSettlement(position.getId(), settlePrice, realized);
        } catch (SQLException e) {
            logger.warning("[MineStock] 写入期货结算失败 #" + position.getId() + ": " + e.getMessage());
            return;
        }
        position.setLastSettlePrice(settlePrice);
        position.setRealizedPnl(realized);

        if (onSettled != null) {
            onSettled.accept(position, TradeOutcome.ok("futures-settled",
                    "id", String.valueOf(position.getId()),
                    "symbol", position.getSymbol(),
                    "settle", format2(settlePrice),
                    "pnl", (pnl >= 0 ? "+" : "") + format2(pnl),
                    "total", (realized >= 0 ? "+" : "") + format2(realized)));
        }
    }

    /**
     * 取日线结算价：{@code ohlc(symbol,"1d",2)} 中最后一根<b>已收盘</b>的 bar。
     *
     * <p>用 {@code limit=1} 会拿到 {@code isOpen: true} 的当日未完成 bar，不适合做结算价。
     */
    private Double dailySettlePrice(String symbol) {
        ForexProvider current = provider;
        if (current == null) return null;
        try {
            List<KLinePoint> bars = current.getOHLC(symbol, "1d", 2).join();
            if (bars == null || bars.isEmpty()) return null;
            // 最早在前：最后一根是当日（未收盘），倒数第二根才是上一个已收盘交易日。
            for (int i = bars.size() - 1; i >= 0; i--) {
                double close = bars.get(i).getClose();
                if (Double.isFinite(close) && close > 0) {
                    if (i == bars.size() - 1 && bars.size() >= 2) continue;
                    return close;
                }
            }
            double last = bars.get(bars.size() - 1).getClose();
            return Double.isFinite(last) && last > 0 ? last : null;
        } catch (RuntimeException e) {
            logger.fine("[MineStock] 获取 " + symbol + " 日线结算价失败: " + e.getMessage());
            return null;
        }
    }

    // ── 内部结算 ──────────────────────────────────────────────────────────────

    private TradeOutcome settleClose(FuturesPosition position, double currentPrice, boolean expired) {
        double payout = round2(engine.closePayout(position, currentPrice));
        double pnl = round2(engine.totalPnl(position, currentPrice));

        try {
            if (!repository.deleteFuturesPosition(position.getId())) {
                return TradeOutcome.fail("futures-position-not-found");
            }
        } catch (SQLException e) {
            logger.warning("[MineStock] 期货平仓删除失败 #" + position.getId() + ": " + e.getMessage());
            return TradeOutcome.fail("db-error");
        }

        if (payout > 0) {
            // 持仓行已删除，钱必须到账：失败重试一次，仍失败则记 severe 供人工补偿。
            // 静默吞掉会让玩家既丢持仓又丢钱。
            if (!payoutTo(position.getPlayerUuid(), payout)) {
                logger.severe("[MineStock] 期货平仓入账失败（已重试），需人工补偿：玩家 "
                        + position.getPlayerUuid() + " 金额 " + format2(payout)
                        + " 持仓 #" + position.getId() + " " + position.getSymbol());
            }
        }

        return TradeOutcome.ok(expired ? "futures-expired" : "futures-close-success",
                "id", String.valueOf(position.getId()),
                "symbol", position.getSymbol(),
                "price", format2(currentPrice),
                "pnl", (pnl >= 0 ? "+" : "") + format2(pnl),
                "payout", format2(payout));
    }

    /**
     * 给玩家入账一笔平仓款，失败时重试一次。
     *
     * <p>持仓行在调用前已删除，因此这笔钱必须送到；经济插件偶发失败不该让玩家
     * 既丢持仓又丢钱。重试仍失败返回 {@code false}，由调用方记 severe 供人工补偿。
     */
    private boolean payoutTo(UUID playerUuid, double amount) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                EconomyService economy = plugin.getEconomy();
                if (economy == null || !economy.isReady()) return false;
                org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
                if (economy.deposit(offline, amount)) return true;
            } catch (RuntimeException e) {
                logger.warning("[MineStock] 期货平仓入账异常（第 " + (attempt + 1)
                        + " 次）: " + e.getMessage());
            }
        }
        return false;
    }

    private void refund(UUID playerUuid, double amount, String reason) {
        try {
            EconomyService economy = plugin.getEconomy();
            if (economy == null || !economy.isReady()) return;
            org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
            if (!economy.deposit(offline, amount)) {
                logger.severe("[MineStock] " + reason + "退款失败！玩家 " + playerUuid
                        + " 金额 " + amount);
            }
        } catch (RuntimeException e) {
            logger.severe("[MineStock] " + reason + "退款异常: " + e.getMessage());
        }
    }

    private CompletableFuture<Double> priceOf(String symbol) {
        ForexProvider current = provider;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("外汇行情源未就绪"));
        }
        return current.getPrice(symbol).thenApply(quote -> quote.getMid());
    }

    private Double fetchPrice(String symbol) {
        try {
            Double price = priceOf(symbol).join();
            return price != null && Double.isFinite(price) && price > 0 ? price : null;
        } catch (RuntimeException e) {
            logger.fine("[MineStock] 获取 " + symbol + " 价格失败: " + e.getMessage());
            return null;
        }
    }

    public FuturesEngine getEngine() {
        return engine;
    }

    static String normalize(String symbol) {
        if (symbol == null) return null;
        String value = symbol.strip().toUpperCase(java.util.Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static boolean samePrice(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static double round2(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String formatQty(double quantity) {
        return BigDecimal.valueOf(quantity).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** 供 UI 展示：持仓的当前盈亏（相对开仓价）。 */
    public double totalPnl(FuturesPosition position, double currentPrice) {
        return engine.totalPnl(position, currentPrice);
    }

    /** 供 UI 展示：可平仓金额。 */
    public double closePayout(FuturesPosition position, double currentPrice) {
        return engine.closePayout(position, currentPrice);
    }

    /** 未到期合约的符号列表，用于 Tab 补全。 */
    public List<String> contractSymbols() {
        Map<String, FuturesContract> current = contracts();
        if (current.isEmpty()) {
            // 配置为空且尚未异步加载过：用兜底表保证补全立即可用。
            current = FuturesContractRegistry.fallback(
                    config.getFuturesDefaultInitialMarginRate(), LocalDate.now());
        }
        List<String> symbols = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (FuturesContract contract : current.values()) {
            if (!contract.isExpired(today)) symbols.add(contract.getSymbol());
        }
        return symbols;
    }
}
