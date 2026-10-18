package dev.onelili.mstock.leverage;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.database.TradingRepository;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.lifecycle.LifecycleTaskManager;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;import dev.onelili.mstock.scheduler.CompatScheduler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 杠杆交易的统一服务层。
 *
 * <p>指令（{@code /mstock leverage|margin}、{@code /mstock forex buy|sell}）与
 * 各 GUI 按钮都只调用这里的方法，因此两条入口的效果、权限、冷却与异步行为完全一致。
 *
 * <p>线程模型：所有公开方法返回 {@link CompletableFuture}，回调在 MineStock 的
 * 异步线程上执行；任何 Bukkit 状态（玩家背包、经济余额）的读写都必须由调用方
 * 通过 {@link CompatScheduler#runOnEntity} 切回玩家线程。
 */
public class LeverageService {

    private final MineStock plugin;
    private final TradingRepository repository;
    private final LeverageEngine engine;
    private final LeverageConfig config;
    private final PlayerLeverageManager leverageManager;
    private final Logger logger;

    public LeverageService(MineStock plugin, TradingRepository repository, LeverageEngine engine,
                           LeverageConfig config, PlayerLeverageManager leverageManager) {
        this.plugin = plugin;
        this.repository = repository;
        this.engine = engine;
        this.config = config;
        this.leverageManager = leverageManager;
        this.logger = plugin.getLogger();
    }

    // ── 开仓 ──────────────────────────────────────────────────────────────────

    /**
     * 开仓：读取玩家<b>当前</b>杠杆，冻结 {@code notional / leverage} 作为保证金后落库。
     *
     * @param requestedLeverage 指定杠杆；{@code 0} 或负数表示使用玩家当前设置
     */
    public CompletableFuture<TradeOutcome> open(UUID playerUuid, MarketType market, String symbol,
                                                TradeDirection direction, double quantity,
                                                double price, int requestedLeverage) {
        return open(playerUuid, market, symbol, direction, quantity, price, requestedLeverage, 0.0);
    }

    /**
     * 开仓并额外收取一笔手续费（股票开仓收佣金，外汇默认免佣金）。
     *
     * <p>实际扣款 = {@code 保证金 + fee}；写库失败时手续费随保证金一起退回。
     *
     * <p>杠杆制度由 {@code market} <b>推导</b>（{@link LeverageConfig.Policy#of(MarketType)}），
     * 而不是一律按外汇：biquote 的股票 CFD 以 {@code market=EQUITY} 落库，
     * 必须受股票上限（默认 10）与 {@code leverage.stock.enabled} 约束。
     *
     * @param fee 开仓手续费；非正数视为免佣金
     */
    public CompletableFuture<TradeOutcome> open(UUID playerUuid, MarketType market, String symbol,
                                                TradeDirection direction, double quantity,
                                                double price, int requestedLeverage, double fee) {
        return open(playerUuid, market, symbol, direction, quantity, price,
                requestedLeverage, fee, LeverageConfig.Policy.of(market));
    }

    /**
     * 按指定杠杆制度开仓。
     *
     * <p><b>为什么需要制度参数</b>：股票与外汇的上限是两套独立数字
     * （默认 10 vs 100）。若一律用外汇区间校验，股票仓位就能开到外汇的上限；
     * 反过来，管理员若把股票上限设得比外汇高，股票自身校验通过后又会在这里
     * 被外汇区间二次拒绝，且报出的上限数字是错的。
     * 因此校验以调用方声明的制度为准，{@code max} 占位符也取该制度的值。
     *
     * @param fee    开仓手续费；非正数视为免佣金
     * @param policy 杠杆制度；决定校验区间、默认值与错误提示中的上限
     */
    public CompletableFuture<TradeOutcome> open(UUID playerUuid, MarketType market, String symbol,
                                                TradeDirection direction, double quantity,
                                                double price, int requestedLeverage, double fee,
                                                LeverageConfig.Policy policy) {
        LeverageConfig.Policy effectivePolicy =
                policy != null ? policy : LeverageConfig.Policy.FOREX;
        if (direction == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-direction"));
        }
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-quantity"));
        }
        if (!(price > 0) || !Double.isFinite(price)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-no-price"));
        }

        double effectiveFee = fee > 0 && Double.isFinite(fee) ? round2(fee) : 0.0;

        int leverage = requestedLeverage > 0
                ? requestedLeverage
                : defaultLeverageFor(effectivePolicy, playerUuid);
        if (!config.accepts(effectivePolicy, leverage)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail(
                    "leverage-out-of-range",
                    "max", String.valueOf(config.maxLeverage(effectivePolicy))));
        }

        double margin = round2(engine.requiredMargin(price, quantity, leverage));
        if (margin <= 0) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-quantity"));
        }
        double totalCost = round2(margin + effectiveFee);

        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));
        }

        return tasks.supplyAsync(() -> {
            // 余额校验与扣款必须在同一线程完成，避免并发超卖。
            AtomicBoolean withdrawn = new AtomicBoolean();
            try {
                EconomyService economy = plugin.getEconomy();
                if (economy == null || !economy.isReady()) {
                    return TradeOutcome.fail("vault-not-found");
                }
                org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
                if (!economy.has(offline, totalCost)) {
                    return TradeOutcome.fail("not-enough-money",
                            "need", format2(totalCost),
                            "balance", format2(economy.getBalance(offline)));
                }
                if (!economy.withdraw(offline, totalCost)) {
                    return TradeOutcome.fail("not-enough-money",
                            "need", format2(totalCost),
                            "balance", format2(economy.getBalance(offline)));
                }
                withdrawn.set(true);

                MarginPosition position = engine.open(playerUuid, -1L, market, symbol,
                        direction, price, quantity, leverage, margin);
                long id = repository.insertMarginPosition(position);

                return TradeOutcome.ok("margin-open-success",
                        "id", String.valueOf(id),
                        "symbol", symbol,
                        "direction", direction.name(),
                        "quantity", formatQty(quantity),
                        "price", format2(price),
                        "leverage", String.valueOf(leverage),
                        "margin", format2(margin));
            } catch (SQLException e) {
                logger.warning("[MineStock] 开仓写库失败 " + symbol + ": " + e.getMessage());
                if (withdrawn.get()) refund(playerUuid, totalCost, "开仓");
                return TradeOutcome.fail("db-error");
            } catch (RuntimeException e) {
                logger.warning("[MineStock] 开仓异常 " + symbol + ": " + e.getMessage());
                if (withdrawn.get()) refund(playerUuid, totalCost, "开仓");
                return TradeOutcome.fail("db-error");
            }
        });
    }

    // ── 平仓 ──────────────────────────────────────────────────────────────────

    /** 按仓位 ID 平仓（手动平仓）。 */
    public CompletableFuture<TradeOutcome> close(UUID playerUuid, long positionId, double currentPrice) {
        return close(playerUuid, positionId, currentPrice, plugin.priceSource());
    }

    /**
     * 与 3 参版本同义，但显式接受价格源以判断可交易性。
     *
     * <p><b>为什么新增重载而不是改签名</b>：3 参版本是 {@code /mstock margin close <id>}
     * 与 GUI 的平仓入口，改动签名会波及既有调用点与 {@code PriceRoutingTest} 等测试。
     * 而"停牌不可平仓"又必须能读到 {@code SymbolPriceSource.tradable}，因此加一层重载。
     */
    public CompletableFuture<TradeOutcome> close(UUID playerUuid, long positionId,
                                                 double currentPrice, SymbolPriceSource prices) {
        if (!(currentPrice > 0) || !Double.isFinite(currentPrice)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-no-price"));
        }
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));

        return tasks.supplyAsync(() -> {
            try {
                MarginPosition position = repository.findMarginPosition(playerUuid, positionId);
                if (position == null) return TradeOutcome.fail("margin-position-not-found");
                if (prices != null && !prices.tradable(position.getMarket(), position.getSymbol())) {
                    return TradeOutcome.fail("simulation-suspended", "code", position.getSymbol());
                }
                return settleClose(position, currentPrice);
            } catch (SQLException e) {
                logger.warning("[MineStock] 平仓失败 #" + positionId + ": " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
        });
    }

    /**
     * 平掉该玩家的全部仓位（{@code /mstock margin close-all}）。
     *
     * <p>被停牌的仓位会被<b>跳过</b>（需求 #2），并按跳过的数量分三种结果告知玩家：
     * 全部平掉、部分平掉（附跳过数）、全部被停牌挡下（明确说是停牌，而不是"行情获取失败"）。
     */
    public CompletableFuture<TradeOutcome> closeAll(UUID playerUuid, SymbolPriceSource prices) {
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));

        return tasks.supplyAsync(() -> {
            List<MarginPosition> positions;
            try {
                positions = repository.findMarginPositions(playerUuid);
            } catch (SQLException e) {
                logger.warning("[MineStock] 读取持仓失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            if (positions.isEmpty()) return TradeOutcome.fail("margin-no-positions");

            int closed = 0;
            int suspended = 0;
            double totalPayout = 0.0;
            for (MarginPosition position : positions) {
                // 停牌守卫：与 close / closeQuantity / sweepLiquidations 同一条链。
                // 少了这一条，同一个停牌仓位经 `/mstock margin close` 被拒、经 `closeall` 却能成交，
                // 玩家只要换条指令就能绕过需求 #2「停牌 = 价格冻结、不能交易、也不强平」。
                if (prices != null && !prices.tradable(position.getMarket(), position.getSymbol())) {
                    suspended++;
                    continue;
                }
                Double price = fetchPrice(prices, position.getMarket(), position.getSymbol());
                if (price == null) continue;
                TradeOutcome outcome = settleClose(position, price);
                if (outcome.success()) {
                    closed++;
                    totalPayout += engine.closePayout(position, price);
                }
            }
            if (closed == 0) {
                // 全部被停牌挡下时给出准确原因：报"没有可平仓的仓位，或行情获取失败"
                // 会让玩家以为可以稍后重试或以为自己没仓位，而真相是这些仓位当前被禁止交易。
                if (suspended > 0 && suspended == positions.size()) {
                    return TradeOutcome.fail("margin-close-all-suspended", "count", String.valueOf(suspended));
                }
                // 混合场景（部分停牌 + 其余取价失败）：只说"行情获取失败"会掩盖被停牌挡住的那几笔，
                // 玩家不知道它们存在、也不知道要等停牌解除。
                if (suspended > 0) {
                    return TradeOutcome.fail("margin-close-all-blocked", "suspended", String.valueOf(suspended));
                }
                return TradeOutcome.fail("margin-close-all-failed");
            }
            if (suspended > 0) {
                return TradeOutcome.ok("margin-close-all-partial",
                        "count", String.valueOf(closed),
                        "payout", format2(totalPayout),
                        "suspended", String.valueOf(suspended));
            }
            return TradeOutcome.ok("margin-close-all-success",
                    "count", String.valueOf(closed),
                    "payout", format2(totalPayout));
        });
    }

    // ── 查询 ──────────────────────────────────────────────────────────────────

    /** 查询持仓并刷新未实现盈亏 / 强平价（只读，不改动杠杆与保证金）。 */
    public CompletableFuture<MarginSnapshot> snapshot(UUID playerUuid, SymbolPriceSource prices) {
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) {
            return CompletableFuture.completedFuture(MarginSnapshot.of(List.of()));
        }
        return tasks.supplyAsync(() -> {
            List<MarginPosition> positions;
            try {
                positions = repository.findMarginPositions(playerUuid);
            } catch (SQLException e) {
                logger.warning("[MineStock] 读取持仓失败: " + e.getMessage());
                return MarginSnapshot.of(List.of());
            }
            List<MarginPosition> refreshed = new ArrayList<>(positions.size());
            for (MarginPosition position : positions) {
                Double price = fetchPrice(prices, position.getMarket(), position.getSymbol());
                if (price != null) {
                    position.setUnrealizedPnl(round2(engine.unrealizedPnl(position, price)));
                }
                refreshed.add(position);
            }
            return MarginSnapshot.of(refreshed);
        });
    }

    /** 强平价与保证金率等衍生指标，供 UI 展示。 */
    public double marginRatio(MarginPosition position, double currentPrice) {
        return engine.marginRatio(position, currentPrice);
    }

    // ── 强平巡检 ──────────────────────────────────────────────────────────────

    /**
     * 扫描全部仓位，对触及强平线的仓位强制平仓。
     *
     * <p>使用该仓位<b>开仓时</b>记录的 {@code leverage} 与 {@code margin} 计算，绝不重算。
     *
     * @param onLiquidated 每个被强平仓位的回调，携带结算结果，用于通知玩家
     * @return 实际强平的仓位数量
     */
    /**
     * 追保通知的节流间隔（毫秒）：同一仓位 5 分钟内不重复打扰。
     *
     * <p>巡检周期默认 30 秒，不加节流的话，一个浮亏仓位每 30 秒就刷一次屏。
     */
    private static final long MARGIN_CALL_THROTTLE_MS = 5 * 60_000L;

    /** 上次发送追保通知的时间：仓位 ID → 时间戳。 */
    private final Map<Long, Long> marginCallNotifiedAt = new ConcurrentHashMap<>();

    /**
     * 扫描全部仓位：触及追保线的发通知（带一键补交），触及强平线的强制平仓。
     *
     * <p>使用该仓位<b>开仓时</b>记录的 {@code leverage} 与 {@code margin} 计算，绝不重算。
     *
     * @param onLiquidated 每个被强平仓位的回调，携带结算结果，用于通知玩家
     * @param onMarginCall 每个触发追保仓位的回调（已节流），用于通知玩家
     * @return 实际强平的仓位数量
     */
    public int sweepLiquidations(SymbolPriceSource prices,
                                 java.util.function.BiConsumer<MarginPosition, TradeOutcome> onLiquidated,
                                 java.util.function.BiConsumer<MarginPosition, MarginCallNotice> onMarginCall) {
        List<MarginPosition> positions;
        try {
            positions = repository.findAllOpenMarginPositions();
        } catch (SQLException e) {
            logger.warning("[MineStock] 强平巡检读取持仓失败: " + e.getMessage());
            return 0;
        }
        int liquidated = 0;
        long now = System.currentTimeMillis();
        Map<Long, Double> pendingPnl = new LinkedHashMap<>();
        for (MarginPosition position : positions) {
            // 停牌：价格已冻结，跳过估值与强平，避免用冻结价把玩家强平（插桩点必须在 fetchPrice 之前）。
            if (prices != null && !prices.tradable(position.getMarket(), position.getSymbol())) {
                continue;
            }
            Double price = fetchPrice(prices, position.getMarket(), position.getSymbol());
            if (price == null) continue;

            double pnl = round2(engine.unrealizedPnl(position, price));
            pendingPnl.put(position.getId(), pnl);
            position.setUnrealizedPnl(pnl);

            if (!engine.shouldLiquidate(position, price)) {
                // 未到强平线但已进入追保区 → 发追保通知（节流）
                if (engine.shouldMarginCall(position, price)) {
                    Long last = marginCallNotifiedAt.get(position.getId());
                    if (last == null || now - last >= MARGIN_CALL_THROTTLE_MS) {
                        marginCallNotifiedAt.put(position.getId(), now);
                        if (onMarginCall != null) {
                            onMarginCall.accept(position, buildMarginCallNotice(position, price));
                        }
                    }
                }
                continue;
            }

            // 强平：按仓位自身固化的杠杆与保证金结算。
            TradeOutcome outcome = settleClose(position, price, true);
            if (outcome.success()) {
                liquidated++;
                marginCallNotifiedAt.remove(position.getId());
                if (onLiquidated != null) onLiquidated.accept(position, outcome);
            }
        }

        // 盈亏落库收尾一次性批量提交：单连接单事务，不再逐行借还连接、逐行提交。
        // 本轮被强平的仓位其行已被删除，批量更新对它们是空操作。
        if (!pendingPnl.isEmpty()) {
            try {
                repository.updateMarginUnrealizedBatch(pendingPnl);
            } catch (SQLException e) {
                logger.warning("[MineStock] 批量更新未实现盈亏失败: " + e.getMessage());
            }
        }
        return liquidated;
    }

    /** 清理已平仓位的追保节流记录（关服时）。 */
    public void clearMarginCallThrottles() {
        marginCallNotifiedAt.clear();
    }

    /**
     * 追保通知内容：建议补交额按"拉回到 80% 保证金率"计算。
     *
     * <p>拉回到 80% 而不是 100%，是因为玩家补交是为了争取价格回升的空间，
     * 补到 100% 等于完全去杠杆、失去杠杆的意义；80% 留有余量但已远离强平线。
     */
    private MarginCallNotice buildMarginCallNotice(MarginPosition position, double price) {
        double suggested = round2(engine.requiredTopUp(position, price, 0.8));
        if (suggested <= 0) suggested = round2(position.getMargin() * 0.5);
        return new MarginCallNotice(price,
                engine.marginRatio(position, price),
                engine.liquidationPrice(position),
                suggested);
    }

    /** 追保通知的载荷。 */
    public record MarginCallNotice(double currentPrice, double marginRatio,
                                   double liquidationPrice, double suggestedTopUp) {
    }

    // ── 补交保证金 ────────────────────────────────────────────────────────────

    /**
     * 向指定仓位补交保证金。
     *
     * <p>收到追保通知后的自救手段：从余额扣款加进仓位保证金，
     * 保证金率立即回升、强平价被推远（等价于给浮亏仓位去杠杆）。
     *
     * <p><b>与强平巡检的并发安全</b>：先扣款、再写库（与现金卖出同序）；
     * 若写库时仓位已被强平，会把这笔钱原额退回 —— 绝不让玩家既丢仓位又丢钱。
     *
     * @param amount 补交金额，必须大于 0
     * @param currentPrice 当前价格，用于计算补交后的强平价；取不到价时无法受理
     */
    public CompletableFuture<TradeOutcome> topUp(UUID playerUuid, long positionId,
                                                 double amount, double currentPrice) {
        if (!(amount > 0) || !Double.isFinite(amount)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-quantity"));
        }
        if (!(currentPrice > 0) || !Double.isFinite(currentPrice)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-no-price"));
        }
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));

        return tasks.supplyAsync(() -> {
            try {
                MarginPosition position = repository.findMarginPosition(playerUuid, positionId);
                if (position == null) return TradeOutcome.fail("margin-position-not-found");

                double deposit = round2(amount);
                double newMargin = round2(position.getMargin() + deposit);
                int newLeverage = engine.leverageAfterTopUp(position, newMargin);
                double newLiqPrice = engine.liquidationPriceAfterTopUp(position, newMargin);

                EconomyService economy = plugin.getEconomy();
                if (economy == null || !economy.isReady()) {
                    return TradeOutcome.fail("vault-not-found");
                }
                org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
                if (!economy.has(offline, deposit)) {
                    return TradeOutcome.fail("not-enough-money",
                            "need", format2(deposit),
                            "balance", format2(economy.getBalance(offline)));
                }
                if (!economy.withdraw(offline, deposit)) {
                    return TradeOutcome.fail("not-enough-money",
                            "need", format2(deposit),
                            "balance", format2(economy.getBalance(offline)));
                }

                if (!repository.topUpMargin(positionId, newMargin, newLeverage, newLiqPrice)) {
                    // 仓位在扣款后、写库前被并发强平 —— 原额退回
                    refund(playerUuid, deposit, "补交保证金（仓位已平）");
                    return TradeOutcome.fail("margin-position-not-found");
                }

                // 补交后的保证金率：(新保证金 + 未实现盈亏) / 新保证金
                double ratioAfter = (newMargin + engine.unrealizedPnl(position, currentPrice))
                        / Math.max(newMargin, 1e-9);
                return TradeOutcome.ok("margin-topup-success",
                        "id", String.valueOf(positionId),
                        "symbol", position.getSymbol(),
                        "amount", format2(deposit),
                        "margin", format2(newMargin),
                        "leverage", newLeverage + "x",
                        "liq_price", format2(newLiqPrice),
                        "ratio", format2(ratioAfter * 100.0));
            } catch (SQLException e) {
                logger.warning("[MineStock] 补交保证金失败 #" + positionId + ": " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
        });
    }

    // ── 部分平仓 ──────────────────────────────────────────────────────────────

    /**
     * 部分平仓的结果。
     *
     * @param closedQuantity 实际平掉的数量（可能小于请求值）
     * @param payout         到账金额（已扣除平仓手续费）
     * @param fee            平仓手续费
     * @param closedIds      被整笔平掉的仓位 ID；部分平仓的仓位会保留在表里
     */
    public record PartialClose(double closedQuantity, double payout, double fee,
                               List<Long> closedIds) {
    }

    /**
     * 按数量平掉该玩家在指定品种 / 市场上的杠杆持仓（先开先平），支持部分平仓。
     *
     * <p>数量按<b>比例</b>分摊：平掉一半数量即释放一半保证金；
     * 强平价与杠杆不变（由开仓时的入场价与杠杆决定）。
     *
     * @param feeRate 平仓手续费率（乘以平仓名义价值）；非正数视为免佣金
     * @return 实际平仓结果；可平数量不足时只平掉现有数量
     */
    public CompletableFuture<PartialClose> closeQuantity(UUID playerUuid, MarketType market,
                                                         String symbol, double quantity,
                                                         SymbolPriceSource prices, double feeRate) {
        return closeQuantity(playerUuid, market, symbol, null, quantity, prices, feeRate);
    }

    /**
     * 按数量平掉指定<b>方向</b>的杠杆持仓（先开先平），支持部分平仓。
     *
     * <p>做空上线后同一品种可以同时有多单与空单，只按品种平仓会把两个方向一起平掉，
     * 因此平仓必须显式带方向。6 参重载保持「不分方向」的旧语义。
     *
     * @param direction 目标方向；{@code null} 表示不分方向
     */
    public CompletableFuture<PartialClose> closeQuantity(UUID playerUuid, MarketType market,
                                                         String symbol, TradeDirection direction,
                                                         double quantity,
                                                         SymbolPriceSource prices, double feeRate) {
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks == null) {
            return CompletableFuture.completedFuture(new PartialClose(0, 0, 0, List.of()));
        }
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            return CompletableFuture.completedFuture(new PartialClose(0, 0, 0, List.of()));
        }
        // 停牌拒绝路径：PartialClose 是 4 组件 record，没有失败原因字段，因此这里返回零结果
        // （语义即"未平掉任何仓位"）。用户可见的明确拒绝对话发生在 SimulationService.sell /
        // closeLeveraged 的前置校验，它返回带 simulation-suspended 的 TradeOutcome。
        if (prices != null && !prices.tradable(market, symbol)) {
            logger.fine("[MineStock] 停牌跳过部分平仓: " + symbol);
            return CompletableFuture.completedFuture(new PartialClose(0, 0, 0, List.of()));
        }
        double effectiveRate = feeRate > 0 && Double.isFinite(feeRate) ? feeRate : 0.0;

        return tasks.supplyAsync(() -> {
            double remaining = quantity;
            double payout = 0.0;
            double fee = 0.0;
            List<Long> closedIds = new ArrayList<>();

            List<MarginPosition> positions;
            try {
                positions = repository.findMarginPositionsBySymbol(playerUuid, market, symbol, direction);
            } catch (SQLException e) {
                logger.warning("[MineStock] 读取杠杆持仓失败 " + symbol + ": " + e.getMessage());
                return new PartialClose(0, 0, 0, List.of());
            }

            for (MarginPosition position : positions) {
                if (remaining <= 1e-9) break;

                Double price = fetchPrice(prices, position.getMarket(), position.getSymbol());
                if (price == null) continue;

                // 比例结算：数量与保证金同比例释放，强平价与杠杆不变
                LeverageEngine.PartialSettlement settlement =
                        engine.partialClose(position, remaining);
                double closeQty = settlement.closedQuantity();
                double closedMargin = round2(settlement.releasedMargin());
                double pnl = round2(engine.partialPnl(position, price, closeQty));
                double closeNotional = price * closeQty;
                double closeFee = round2(closeNotional * effectiveRate);
                double thisPayout = Math.max(0.0, round2(closedMargin + pnl - closeFee));

                try {
                    boolean fully = closeQty >= position.getQuantity() - 1e-9;
                    if (fully) {
                        if (!repository.deleteMarginPosition(position.getId())) {
                            // 已被并发平掉，跳过
                            continue;
                        }
                        closedIds.add(position.getId());
                    } else {
                        // 原子扣减：传「本次平仓量」而非算好的剩余值。
                        // 返回 false 说明该仓位已被并发平掉（数量不足扣减或行已消失），
                        // 必须跳过且不入账，否则同一份保证金会被两个任务各领一次。
                        if (!repository.reduceMarginPosition(position.getId(),
                                closeQty,
                                closedMargin,
                                position.getUnrealizedPnl())) {
                            continue;
                        }
                    }
                } catch (SQLException e) {
                    logger.warning("[MineStock] 部分平仓写库失败 #" + position.getId()
                            + ": " + e.getMessage());
                    continue;
                }

                if (thisPayout > 0) {
                    // 仓位已缩减/删除，入账带重试，避免玩家既减仓又收不到钱。
                    if (!payoutTo(playerUuid, thisPayout)) {
                        logger.severe("[MineStock] 部分平仓入账失败（已重试），需人工补偿：玩家 "
                                + playerUuid + " 金额 " + format2(thisPayout)
                                + " 仓位 #" + position.getId());
                    }
                }

                remaining -= closeQty;
                payout += thisPayout;
                fee += closeFee;
            }

            return new PartialClose(round2(quantity - remaining), round2(payout),
                    round2(fee), List.copyOf(closedIds));
        });
    }

    /** 该玩家在某市场 / 品种 / 方向上的持仓数量合计（同步读库，供界面与「全部平仓」使用）。 */
    public double quantityOf(UUID playerUuid, MarketType market, String symbol,
                             TradeDirection direction) {
        try {
            double total = 0.0;
            for (MarginPosition position : repository.findMarginPositionsBySymbol(
                    playerUuid, market, symbol, direction)) {
                total += position.getQuantity();
            }
            return total;
        } catch (SQLException e) {
            logger.warning("[MineStock] 读取持仓数量失败 " + symbol + ": " + e.getMessage());
            return 0.0;
        }
    }

    // ── 内部结算 ──────────────────────────────────────────────────────────────

    /**
     * 结算平仓：先删库（幂等锁），成功后再入账。
     *
     * <p>这样并发触发（手动平仓与强平同时发生）只会有一方拿到删除成功的返回值，
     * 另一方直接返回，不会重复出金。
     */
    private TradeOutcome settleClose(MarginPosition position, double currentPrice) {
        return settleClose(position, currentPrice, false);
    }

    private TradeOutcome settleClose(MarginPosition position, double currentPrice, boolean forced) {
        double payout = round2(engine.closePayout(position, currentPrice));
        double pnl = round2(engine.unrealizedPnl(position, currentPrice));

        try {
            if (!repository.deleteMarginPosition(position.getId())) {
                return TradeOutcome.fail("margin-position-not-found");
            }
        } catch (SQLException e) {
            logger.warning("[MineStock] 平仓删除仓位失败 #" + position.getId() + ": " + e.getMessage());
            return TradeOutcome.fail("db-error");
        }

        if (payout > 0) {
            // 仓位行已删除，钱必须到账：deposit 失败时重试一次，
            // 仍失败则记 severe 并把金额写进日志，便于管理员手工补偿。
            // 不能静默吞掉 —— 玩家会既丢仓位又丢钱。
            if (!payoutTo(position.getPlayerUuid(), payout)) {
                logger.severe("[MineStock] 平仓入账失败（已重试），需人工补偿：玩家 "
                        + position.getPlayerUuid() + " 金额 " + format2(payout)
                        + " 仓位 #" + position.getId() + " " + position.getSymbol());
            }
        }

        return TradeOutcome.ok(forced ? "margin-liquidated" : "margin-close-success",
                "id", String.valueOf(position.getId()),
                "symbol", position.getSymbol(),
                "price", format2(currentPrice),
                "pnl", (pnl >= 0 ? "+" : "") + format2(pnl),
                "payout", format2(payout));
    }

    /**
     * 给玩家入账一笔平仓款，失败时重试一次。
     *
     * <p>仓位行在调用前已经删除，因此这笔钱<b>必须</b>送到 —— 经济插件偶发失败
     * （数据库抖动、其他插件锁）不该让玩家既丢仓位又丢钱。重试仍失败时返回
     * {@code false}，由调用方记 severe 日志供人工补偿。
     *
     * @return {@code true} 表示钱已成功入账
     */
    private boolean payoutTo(UUID playerUuid, double amount) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                EconomyService economy = plugin.getEconomy();
                if (economy == null || !economy.isReady()) return false;
                org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
                if (economy.deposit(offline, amount)) return true;
            } catch (RuntimeException e) {
                logger.warning("[MineStock] 平仓入账异常（第 " + (attempt + 1)
                        + " 次）: " + e.getMessage());
            }
        }
        return false;
    }

    /** 写库失败时把已扣的保证金退回，避免玩家凭空损失。 */
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

    /**
     * 玩家在该制度下未指定杠杆时使用的倍数。
     *
     * <p>股票与外汇的默认值彼此独立（{@code leverage.stock.default-leverage} vs
     * {@code leverage.default-leverage}），因此这里必须按制度分派，
     * 不能一律取外汇默认值。
     */
    private int defaultLeverageFor(LeverageConfig.Policy policy, UUID playerUuid) {
        if (policy == LeverageConfig.Policy.STOCK) {
            if (!config.isStockLeverageEnabled()) return 1;
            return leverageManager.getStockLeverage(playerUuid);
        }
        return leverageManager.getLeverage(playerUuid);
    }

    private Double fetchPrice(SymbolPriceSource prices, MarketType market, String symbol) {
        if (prices == null) return null;
        try {
            Double price = prices.price(market, symbol).join();
            if (price == null || !Double.isFinite(price) || price <= 0) return null;
            return price;
        } catch (RuntimeException e) {
            logger.fine("[MineStock] 获取 " + symbol + " 价格失败: " + e.getMessage());
            return null;
        }
    }

    public LeverageEngine getEngine() {
        return engine;
    }

    public LeverageConfig getConfig() {
        return config;
    }

    public PlayerLeverageManager getLeverageManager() {
        return leverageManager;
    }

    private static double round2(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 数量可能是小数（外汇手数 / 加密币数量），最多保留 4 位有效小数。 */
    static String formatQty(double quantity) {
        BigDecimal value = BigDecimal.valueOf(quantity).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return value.toPlainString();
    }
}
