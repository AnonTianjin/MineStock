package dev.onelili.mstock.config;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.model.MarketType;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 杠杆交易配置。
 *
 * <p>保证金率定义为 {@code (margin + unrealizedPnl) / margin}，因此开仓瞬间恒为 {@code 1.0}，
 * 与杠杆倍数解耦：{@code marginCallThreshold} 与 {@code liquidationThreshold} 才有意义。
 * 若改用 {@code 权益 / 名义价值}，默认 {@code leverage=10} 开仓即为 {@code 0.1}，
 * 会立刻触碰 {@code liquidationThreshold} 而被强平。
 */
public class LeverageConfig {

    private final MineStock plugin;

    public LeverageConfig(MineStock plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    /**
     * 杠杆制度。
     *
     * <p>股票与外汇的<b>上限、默认值、开关</b>三样都彼此独立，因此凡是"校验一个杠杆倍数"
     * 或"取默认值"的地方都必须先指明制度 —— 混用会让
     * {@code leverage.stock.max-leverage} 形同虚设。
     */
    public enum Policy {
        /** 外汇 / CFD / 虚拟货币，上限 {@code leverage.max-leverage}（默认 100）。 */
        FOREX,
        /** 股票，上限 {@code leverage.stock.max-leverage}（默认 10），可被 enabled 整体关闭。 */
        STOCK;

        /**
         * 按持仓所属市场推导应适用的杠杆制度。
         *
         * <p><b>为什么必须有这个映射</b>：biquote 上的"股票 CFD"
         * （{@code MSFTUSDT}、{@code SPYUSDT} …，约 186 个）元数据是 {@code type=Stock}，
         * 因此在外汇界面成交时会以 {@code market=}{@link MarketType#EQUITY} 落库。
         * 它们是<b>股票敞口</b>，必须受股票制度约束（10x 上限、{@code stock.enabled} 开关）。
         *
         * <p>若这类仓位按 {@link #FOREX} 校验，玩家就能在<b>外汇界面</b>买到
         * 100x 的微软 / 标普 500 敞口，而同一个标的走 {@code /mstock buy MSFT}
         * 只允许 10x —— 股票上限与关闭开关都会被整体绕过。
         *
         * <p>其余市场（外汇 / 贵金属 / 能源 / 指数 / 加密）沿用 {@link #FOREX}。
         *
         * <p><b>{@link MarketType#SIMULATION} 也归 {@link #STOCK}</b>：模拟盘若落
         * {@code FOREX}，{@code /mstock simulation buy SIM001 100 50} 就能开出 50x
         * （直接套用 {@code leverage.max-leverage}，默认 100），绕过保守上限。
         * 代价是"关闭股票杠杆会连带关闭模拟盘杠杆（&gt;1x）"—— 这是设计的必然推论，
         * 已登记为风险 R2。
         */
        public static Policy of(MarketType market) {
            return market == MarketType.EQUITY || market == MarketType.SIMULATION ? STOCK : FOREX;
        }
    }

    /** 该制度下的最大杠杆倍数。 */
    public int maxLeverage(Policy policy) {
        return policy == Policy.STOCK ? getStockMaxLeverage() : getMaxLeverage();
    }

    /** 该制度下玩家未设置过时使用的默认倍数。 */
    public int defaultLeverage(Policy policy) {
        return policy == Policy.STOCK ? getStockDefaultLeverage() : getForexDefaultLeverage();
    }

    /**
     * 该制度下是否接受这个杠杆倍数。
     *
     * <p>{@link Policy#STOCK} 的两道关卡：先看 {@code leverage.stock.enabled}
     * （关闭后只接受 {@code 1x}，即全额现金等价），再看是否落在股票自己的
     * {@code [1, stockMaxLeverage]} 区间内。
     *
     * <p>这是唯一的股票杠杆权威判据：调用方不必自己判断 enabled，
     * 否则就会重演"股票买 100x 通过外汇上限校验"的越权。
     */
    public boolean accepts(Policy policy, int leverage) {
        if (policy == Policy.STOCK) {
            if (leverage > 1 && !isStockLeverageEnabled()) return false;
            return isValidStockLeverage(leverage);
        }
        return isValidLeverage(leverage);
    }

    /** 允许玩家设置的最大杠杆倍数。 */
    public int getMaxLeverage() {
        return Math.max(1, cfg().getInt("leverage.max-leverage", 100));
    }

    /**
     * 玩家未设置过杠杆时使用的默认倍数。
     *
     * <p><b>这是兜底值，不是外汇默认值</b>：外汇 / CFD 的默认倍数由
     * {@link #getForexDefaultLeverage()}（{@code forex.default-leverage}）决定。
     * 该键仅在 {@code forex.default-leverage} 缺失 / 非法时作为回落使用。
     */
    public int getDefaultLeverage() {
        return clamp(cfg().getInt("leverage.default-leverage", 10));
    }

    /** 触发追保通知的保证金率阈值。 */
    public double getMarginCallThreshold() {
        return clampRate(cfg().getDouble("leverage.margin-call-threshold", 0.5));
    }

    /** 触发强制平仓的保证金率阈值。 */
    public double getLiquidationThreshold() {
        return clampRate(cfg().getDouble("leverage.liquidation-threshold", 0.1));
    }

    /** 维持保证金率，用于精确定价强平价。 */
    public double getMaintenanceMarginRate() {
        return clampRate(cfg().getDouble("leverage.maintenance-margin-rate", 0.005));
    }

    /**
     * 外汇市场的默认杠杆倍数（{@code forex.default-leverage}）。
     *
     * <p>玩家从未设置过外汇杠杆时用它；键缺失或非法（&lt;1）时回落到
     * {@link #getDefaultLeverage()}。
     */
    public int getForexDefaultLeverage() {
        int configured = cfg().getInt("forex.default-leverage", 0);
        if (!cfg().isSet("forex.default-leverage") || configured < 1) {
            return getDefaultLeverage();
        }
        return clamp(configured);
    }

    /** 强制平仓巡检周期（tick）。 */
    public long getLiquidationSweepTicks() {
        return Math.max(20L, cfg().getLong("leverage.liquidation-sweep-seconds", 30L) * 20L);
    }

    // ── 股票杠杆 ──────────────────────────────────────────────────────────────
    // 股票与外汇的上限分开配置：两者波动率与监管口径不同，
    // 允许管理员单独收紧股票杠杆而不影响外汇。

    /** 股票允许的最大杠杆倍数。 */
    public int getStockMaxLeverage() {
        return Math.max(1, cfg().getInt("leverage.stock.max-leverage", 10));
    }

    /** 股票默认杠杆；默认 1 表示"不借钱"，玩家行为与无杠杆时一致。 */
    public int getStockDefaultLeverage() {
        return clampStock(cfg().getInt("leverage.stock.default-leverage", 1));
    }

    /** 是否允许股票使用杠杆（关闭后股票只能全额买入）。 */
    public boolean isStockLeverageEnabled() {
        return cfg().getBoolean("leverage.stock.enabled", true);
    }

    /** 把任意输入收敛到股票区间 {@code [1, stockMaxLeverage]}。 */
    public int clampStock(int leverage) {
        return Math.max(1, Math.min(getStockMaxLeverage(), leverage));
    }

    /** 校验股票杠杆是否合法。 */
    public boolean isValidStockLeverage(int leverage) {
        return leverage >= 1 && leverage <= getStockMaxLeverage();
    }

    /** 把任意输入收敛到 {@code [1, maxLeverage]}。 */
    public int clamp(int leverage) {
        return Math.max(1, Math.min(getMaxLeverage(), leverage));
    }

    /** 校验玩家请求的杠杆是否合法。 */
    public boolean isValidLeverage(int leverage) {
        return leverage >= 1 && leverage <= getMaxLeverage();
    }

    private static double clampRate(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
