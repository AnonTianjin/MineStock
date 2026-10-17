package dev.onelili.mstock.leverage;

import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;

import java.util.UUID;

/**
 * 杠杆与保证金的核心计算。
 *
 * <p><b>保证金率定义</b>：{@code marginRatio = (margin + unrealizedPnl) / margin}。
 * 开仓瞬间恒为 {@code 1.0}，与杠杆倍数无关，因此
 * {@code marginCallThreshold(0.5)} / {@code liquidationThreshold(0.1)} 才有明确含义。
 * （若改用"权益 / 名义价值"，默认 {@code leverage=10} 开仓即为 {@code 0.1}，
 * 会立刻触发强平，需求给出的默认值将自相矛盾。）
 *
 * <p><b>强平价</b>：把维持保证金率纳入定价，使价格触及强平价时保证金率恰好等于
 * {@code liquidationThreshold}：
 * <pre>
 *   多头: price = entry * (1 - 1/leverage + maintenanceMarginRate)
 *   空头: price = entry * (1 + 1/leverage - maintenanceMarginRate)
 * </pre>
 *
 * <p>本类不依赖任何 Bukkit 运行时，便于单元测试。
 */
public class LeverageEngine {

    /**
     * 阈值比较的容差。
     *
     * <p>"亏损恰好 90%"这类边界在 double 下会算出 {@code 0.10000000000000028}，
     * 从而让本该强平的仓位侥幸逃过一次巡检。用 1e-9 的容差吸收这类误差。
     */
    private static final double THRESHOLD_EPSILON = 1e-9;

    private final LeverageConfig config;

    public LeverageEngine(LeverageConfig config) {
        this.config = config;
    }

    // ── 开仓 ──────────────────────────────────────────────────────────────────

    /**
     * 计算开仓所需保证金。
     *
     * <p>冻结 {@code notional / leverage}；杠杆取调用方传入值（即玩家<b>当前</b>设置），
     * 一经开仓即固化到仓位记录里，之后不再随玩家调整而重算。
     */
    public double requiredMargin(double price, double quantity, int leverage) {
        if (price <= 0 || quantity <= 0) return 0.0;
        int effective = Math.max(1, leverage);
        return price * quantity / effective;
    }

    /**
     * 构造一笔保证金仓位。{@code leverage} 与 {@code margin} 在此刻固化。
     *
     * @param id 数据库主键；尚未入库时传 {@code -1}
     */
    public MarginPosition open(UUID playerUuid, long id, MarketType market, String symbol,
                               TradeDirection direction, double price, double quantity,
                               int leverage, double margin) {
        return new MarginPosition(
                id, playerUuid, market, symbol, direction,
                quantity, price, Math.max(1, leverage), margin,
                liquidationPrice(price, leverage, direction),
                0.0,
                System.currentTimeMillis()
        );
    }

    // ── 盈亏与保证金率 ────────────────────────────────────────────────────────

    /** 未实现盈亏：{@code (currentPrice - entryPrice) * quantity * direction}。 */
    public double unrealizedPnl(MarginPosition position, double currentPrice) {
        return (currentPrice - position.getEntryPrice())
                * position.getQuantity()
                * position.getDirection().sign();
    }

    /**
     * 保证金率：{@code (margin + unrealizedPnl) / margin}。
     *
     * <p>开仓时为 {@code 1.0}；亏损扩大时向 0 收敛；为负表示已亏穿保证金。
     */
    public double marginRatio(MarginPosition position, double currentPrice) {
        double margin = position.getMargin();
        if (margin <= 0) return 0.0;
        return (margin + unrealizedPnl(position, currentPrice)) / margin;
    }

    /** 是否应发送追保通知。 */
    public boolean shouldMarginCall(MarginPosition position, double currentPrice) {
        return marginRatio(position, currentPrice)
                <= config.getMarginCallThreshold() + THRESHOLD_EPSILON;
    }

    /** 是否应强制平仓。 */
    public boolean shouldLiquidate(MarginPosition position, double currentPrice) {
        return marginRatio(position, currentPrice)
                <= config.getLiquidationThreshold() + THRESHOLD_EPSILON;
    }

    // ── 强平价 ────────────────────────────────────────────────────────────────

    /**
     * 强平价。多头在下方，空头在上方。
     *
     * <p>使用该仓位<b>开仓时</b>记录的 {@code leverage}，与玩家当前杠杆无关。
     */
    public double liquidationPrice(double entryPrice, int leverage, TradeDirection direction) {
        if (entryPrice <= 0) return 0.0;
        int effective = Math.max(1, leverage);
        double initialMarginRate = 1.0 / effective;
        double maintenance = config.getMaintenanceMarginRate();
        int sign = direction.sign();
        double factor = 1.0 - sign * initialMarginRate + sign * maintenance;
        return Math.max(0.0, entryPrice * factor);
    }

    /** 用仓位自身固化的杠杆与方向重算强平价（读取时校准）。 */
    public double liquidationPrice(MarginPosition position) {
        return liquidationPrice(position.getEntryPrice(), position.getLeverage(),
                position.getDirection());
    }

    // ── 补交保证金 ────────────────────────────────────────────────────────────

    /**
     * 把保证金率拉回到 {@code targetRatio} 所需的补交金额。
     *
     * <p>定义：{@code (margin + topUp + uPnL) / (margin + topUp) = targetRatio}，
     * 解出 {@code topUp = uPnL / (targetRatio - 1) - margin}（亏损时 uPnL 为负，
     * 分母 targetRatio-1 也为负，商为正）。
     *
     * @param position    目标仓位
     * @param currentPrice 当前价格
     * @param targetRatio 目标保证金率，必须严格小于 1（如 0.8 表示拉回 80%）
     * @return 需要补交的金额；保证金率已高于目标时返回 0
     */
    public double requiredTopUp(MarginPosition position, double currentPrice, double targetRatio) {
        double pnl = unrealizedPnl(position, currentPrice);
        if (pnl >= 0) return 0.0;                       // 浮盈时无需补交
        if (targetRatio >= 1.0 || targetRatio <= 0) return 0.0;
        double needed = pnl / (targetRatio - 1.0) - position.getMargin();
        return Math.max(0.0, needed);
    }

    /**
     * 补交保证金后的有效杠杆：名义价值不变、保证金变多，杠杆相应降低。
     *
     * <p>向下取整且最低为 1 —— 补交到杠杆不足 1 的整数倍时，多余的保证金
     * 只是"垫"在那里抬高安全垫，不再继续降低杠杆。
     */
    public int leverageAfterTopUp(MarginPosition position, double newMargin) {
        double notional = position.getEntryPrice() * position.getQuantity();
        if (newMargin <= 0 || notional <= 0) return 1;
        return Math.max(1, (int) Math.floor(notional / newMargin));
    }

    /**
     * 补交保证金后的强平价：用<b>新保证金</b>推导的新杠杆重算。
     *
     * <p>补交 = 去杠杆：保证金越厚，强平价离现价越远。
     */
    public double liquidationPriceAfterTopUp(MarginPosition position, double newMargin) {
        return liquidationPrice(position.getEntryPrice(),
                leverageAfterTopUp(position, newMargin),
                position.getDirection());
    }

    // ── 平仓 ──────────────────────────────────────────────────────────────────

    /** 平仓释放的金额：保证金 + 未实现盈亏，下限为 0。 */
    public double closePayout(MarginPosition position, double currentPrice) {
        double payout = position.getMargin() + unrealizedPnl(position, currentPrice);
        return Math.max(0.0, payout);
    }

    // ── 部分平仓 ──────────────────────────────────────────────────────────────

    /**
     * 部分平仓的一次结算。
     *
     * @param closedQuantity    实际平掉的数量
     * @param releasedMargin    释放的保证金
     * @param remainingQuantity 平仓后仓位剩余数量
     * @param remainingMargin   平仓后仓位剩余保证金
     */
    public record PartialSettlement(double closedQuantity, double releasedMargin,
                                    double remainingQuantity, double remainingMargin) {
    }

    /**
     * 计算按数量部分平仓的比例结算。
     *
     * <p>数量与保证金<b>同比例</b>释放；强平价与杠杆不变
     * （由开仓时的入场价与杠杆决定，减仓不改变它们）。
     *
     * @param position 现有仓位
     * @param quantity 请求平掉的数量；超过持仓数量时视为全平
     */
    public PartialSettlement partialClose(MarginPosition position, double quantity) {
        double closeQty = Math.min(Math.max(quantity, 0), position.getQuantity());
        double ratio = position.getQuantity() > 0 ? closeQty / position.getQuantity() : 0.0;
        return new PartialSettlement(
                closeQty,
                position.getMargin() * ratio,
                position.getQuantity() - closeQty,
                position.getMargin() * (1 - ratio));
    }

    /**
     * 部分平仓的盈亏：这部分数量的未实现盈亏。
     *
     * <p>按数量比例分摊整仓盈亏 —— 价格对数量是线性的，因此与逐股计算一致。
     */
    public double partialPnl(MarginPosition position, double currentPrice, double quantity) {
        return unrealizedPnl(position, currentPrice)
                * (Math.min(Math.max(quantity, 0), position.getQuantity())
                        / Math.max(position.getQuantity(), 1e-12));
    }

    public double getMarginCallThreshold() {
        return config.getMarginCallThreshold();
    }

    public double getLiquidationThreshold() {
        return config.getLiquidationThreshold();
    }

    public double getMaintenanceMarginRate() {
        return config.getMaintenanceMarginRate();
    }
}
