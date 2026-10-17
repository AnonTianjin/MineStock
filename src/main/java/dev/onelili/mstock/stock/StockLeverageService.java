package dev.onelili.mstock.stock;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.leverage.LeverageService;
import dev.onelili.mstock.leverage.PlayerLeverageManager;
import dev.onelili.mstock.leverage.TradeOutcome;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import dev.onelili.mstock.stockio.StockApiService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 股票杠杆交易服务。
 *
 * <p>复用 {@link LeverageService} 的保证金引擎（market = {@link MarketType#EQUITY}），
 * 因此股票杠杆仓位自动纳入：追保通知、强制平仓巡检、{@code /mstock margin}、持仓总览。
 *
 * <p><b>与现金买入的关系</b>：杠杆 = 1 表示"不借钱"，走原仓库的现金持仓路径
 * （{@code holdings} 表）；杠杆 &gt; 1 才开保证金仓位。两条路径互不污染，
 * 也保持了对 {@code MineStockAPI} 的向后兼容。
 *
 * <p>手续费沿用全局 {@code transaction-fee-percent}，按名义价值收取
 * （开仓、平仓各收一次，与现金交易一致）。
 */
public class StockLeverageService {

    private final MineStock plugin;
    private final LeverageService leverageService;
    private final Logger logger;

    public StockLeverageService(MineStock plugin, LeverageService leverageService) {
        this.plugin = plugin;
        this.leverageService = leverageService;
        this.logger = plugin.getLogger();
    }

    /**
     * 杠杆买入股票（开多）。
     *
     * <p>价格取股票行情现价（含 {@code price-ratio}），手续费按名义价值收取。
     *
     * @param leverage 指定杠杆；{@code 0} 表示使用玩家当前的股票杠杆设置
     */
    public CompletableFuture<TradeOutcome> buy(java.util.UUID playerUuid, String code,
                                               double quantity, int leverage) {
        return open(playerUuid, code, quantity, leverage, TradeDirection.LONG);
    }

    /**
     * 做空股票（开空）。
     *
     * <p>与买入共用同一个保证金引擎，唯一的差别是方向为 {@link TradeDirection#SHORT}：
     * 盈亏随价格下跌为正，保证金与强平价由同一套公式按方向翻转。
     *
     * <p><b>空头没有现金路径</b>：多头 1 倍杠杆等于全额现金买入，因此由现金持仓（{@code holdings}）
     * 承接；做空必须借券卖出，1 倍杠杆即 100% 保证金，仍然是一条保证金仓位。
     * 这也是为什么这里不放行 {@code stock-leverage-cash-path}。
     *
     * @param leverage 指定杠杆；{@code 0} 表示使用玩家当前的股票杠杆设置
     */
    public CompletableFuture<TradeOutcome> sellShort(java.util.UUID playerUuid, String code,
                                                     double quantity, int leverage) {
        return open(playerUuid, code, quantity, leverage, TradeDirection.SHORT);
    }

    /**
     * 按方向开仓。
     *
     * <p><b>杠杆校验是本方法的职责</b>：股票杠杆上限与外汇不同（默认 10 vs 100），
     * 且可被 {@code leverage.stock.enabled} 整体关闭。若把校验留给调用方，
     * 一旦有人直接传 {@code leverage} 就会绕过股票上限 —— 因此这里用
     * {@link LeverageConfig#accepts} 以股票制度为准做唯一裁决。
     *
     * @param direction 开仓方向；{@code null} 视为非法请求
     */
    public CompletableFuture<TradeOutcome> open(java.util.UUID playerUuid, String code,
                                                double quantity, int leverage,
                                                TradeDirection direction) {
        if (direction == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-direction"));
        }
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-quantity"));
        }
        String normalized = normalize(code);
        if (normalized == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-symbol"));
        }
        // 纵深防御：模拟盘代码永远不能落成 market=EQUITY 的真实保证金仓位。
        // 指令层（MStockCommand.executeBuy）已经拦下两条入口，这里是方法级不变量 ——
        // 未来的新调用方（含第三方经 MineStockAPI 的间接路径）也不会漏。
        var simulation = plugin.getSimulationService();
        if (simulation != null && simulation.isSimulationCode(normalized)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-symbol"));
        }

        LeverageConfig leverageConfig = plugin.getLeverageConfig();
        if (leverageConfig == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("db-error"));
        }
        boolean shortSide = direction == TradeDirection.SHORT;
        // 做空必然是杠杆业务（借券卖出）。股票杠杆被整体关闭时，
        // `accepts(STOCK, 1)` 仍然放行 1 倍（多头的现金路径需要它），
        // 因此空头必须在这里单独拒一次，否则关掉杠杆也能开空。
        if (shortSide && !leverageConfig.isStockLeverageEnabled()) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("leverage-stock-disabled"));
        }
        int requested = leverage > 0 ? leverage : stockLeverage(playerUuid);
        if (!leverageConfig.accepts(LeverageConfig.Policy.STOCK, requested)) {
            // 关闭时只允许 1x，其余一律按"现金路径"处理由调用方决定，这里直接拒绝非现金请求。
            return CompletableFuture.completedFuture(TradeOutcome.fail(
                    "leverage-stock-out-of-range",
                    "max", String.valueOf(leverageConfig.getStockMaxLeverage())));
        }
        if (!shortSide && requested <= 1) {
            // 多头 1x 表示不借钱，必须走现金持仓路径，不该产生保证金仓位。
            return CompletableFuture.completedFuture(
                    TradeOutcome.fail("stock-leverage-cash-path"));
        }

        StockApiService api = plugin.getApi();
        if (api == null || !api.isSupported(normalized)) {
            return CompletableFuture.completedFuture(
                    TradeOutcome.fail("unsupported-code", "code", normalized));
        }

        return api.fetch(normalized).thenCompose(info ->
                leverageService.open(
                        playerUuid,
                        MarketType.EQUITY,
                        normalized,
                        direction,
                        quantity,
                        info.getPrice() * priceRatio(),
                        requested,
                        openFee(info.getPrice() * priceRatio(), quantity),
                        LeverageConfig.Policy.STOCK));
    }

    /**
     * 卖出股票时先平杠杆<b>多头</b>仓位（先开先平，支持部分平仓）。
     *
     * @return 实际平掉的数量与到账金额；无杠杆仓位时数量为 0
     */
    public CompletableFuture<LeverageService.PartialClose> closeLeveraged(
            java.util.UUID playerUuid, String code, double quantity) {
        return closeDirection(playerUuid, code, quantity, TradeDirection.LONG);
    }

    /**
     * 回补杠杆<b>空头</b>仓位（先开先平，支持部分平仓）。
     *
     * <p>平仓带方向是必须的：同一只股票可以同时有多单与空单，
     * 只按品种平会把两个方向一起平掉。
     */
    public CompletableFuture<LeverageService.PartialClose> coverLeveraged(
            java.util.UUID playerUuid, String code, double quantity) {
        return closeDirection(playerUuid, code, quantity, TradeDirection.SHORT);
    }

    private CompletableFuture<LeverageService.PartialClose> closeDirection(
            java.util.UUID playerUuid, String code, double quantity, TradeDirection direction) {
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            return CompletableFuture.completedFuture(
                    new LeverageService.PartialClose(0, 0, 0, java.util.List.of()));
        }
        String normalized = normalize(code);
        if (normalized == null) {
            return CompletableFuture.completedFuture(
                    new LeverageService.PartialClose(0, 0, 0, java.util.List.of()));
        }
        return leverageService.closeQuantity(
                playerUuid, MarketType.EQUITY, normalized, direction, quantity,
                plugin.priceSource(), feeRate());
    }

    /**
     * 该玩家当前生效的股票杠杆。
     *
     * <p>{@code leverage.stock.enabled: false} 时恒为 {@code 1}（全额现金买入）：
     * 即使玩家库里存着历史的高杠杆设置，关闭后也不会生效。
     */
    public int stockLeverage(java.util.UUID playerUuid) {
        PlayerLeverageManager manager = plugin.getLeverageManager();
        if (manager == null) return 1;
        LeverageConfig config = plugin.getLeverageConfig();
        if (config != null && !config.isStockLeverageEnabled()) return 1;
        return manager.getStockLeverage(playerUuid);
    }

    // ── 费用 ──────────────────────────────────────────────────────────────────

    private double feeRate() {
        MainConfig config = plugin.getMainConfig();
        return config != null ? config.getTransactionFeeRate() : 0.0;
    }

    private double openFee(double unitPrice, double quantity) {
        return round2(unitPrice * quantity * feeRate());
    }

    private double priceRatio() {
        MainConfig config = plugin.getMainConfig();
        return config != null ? config.getPriceRatio() : 1.0;
    }

    static String normalize(String code) {
        if (code == null) return null;
        String value = code.strip().toUpperCase(java.util.Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static double round2(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
