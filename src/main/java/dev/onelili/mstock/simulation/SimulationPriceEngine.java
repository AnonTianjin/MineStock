package dev.onelili.mstock.simulation;

/**
 * 模拟盘价格引擎：纯计算、无副作用。
 *
 * <p>所有随机数由调用方（{@link SimulationService}）提供，便于"同种子复现"测试：
 * 引擎本身不持有任何 {@link java.util.Random}，同参数 + 同随机序列 ⇒ 同结果。
 *
 * <p><b>注意</b>：本类只做数学，不读配置、不碰数据库、不涉及 {@code synchronized}。
 *
 * @see SimulationStock
 */
public final class SimulationPriceEngine {

    private SimulationPriceEngine() {
    }

    /**
     * 一次 tick 的全部参数（不可变快照）。
     *
     * @param mu            每秒漂移
     * @param sigma         每秒对数收益标准差
     * @param theta         均值回归速度
     * @param anchor        均值回归锚点价
     * @param jumpLambda    跳变强度（每秒概率）
     * @param jumpMagnitude 跳变幅度
     * @param amplitude     对数收益乘数
     * @param slopePerMs    每毫秒附加的<b>绝对价格</b>增量
     * @param dtSeconds     本 tick 的推进时长（秒）
     */
    public record Params(double mu, double sigma, double theta, double anchor,
                         double jumpLambda, double jumpMagnitude, double amplitude,
                         double slopePerMs, double dtSeconds) {
    }

    /** 有效 {@code dt}：非正 / 非有限一律按 {@code 0} 处理，避免 {@code sqrt(负数)} 产生 NaN。 */
    private static double effectiveDt(double dtSeconds) {
        return (dtSeconds > 0 && Double.isFinite(dtSeconds)) ? dtSeconds : 0.0;
    }

    /** 几何布朗运动项：{@code mu*dt + sigma*sqrt(dt)*z}。 */
    public static double gbm(double mu, double sigma, double dtSeconds, double z) {
        double dt = effectiveDt(dtSeconds);
        if (dt == 0.0) {
            return 0.0;
        }
        return mu * dt + sigma * Math.sqrt(dt) * z;
    }

    /** 均值回归项：{@code theta * ln(anchor / price) * dt}；{@code price <= 0} 时按 {@code 0.01} 处理。 */
    public static double ou(double theta, double anchor, double price, double dtSeconds) {
        double dt = effectiveDt(dtSeconds);
        if (dt == 0.0) {
            return 0.0;
        }
        // price 非正 / 非有限 → 按 0.01 处理
        double effectivePrice = (price > 0 && Double.isFinite(price)) ? price : 0.01;
        // anchor 非法时退化为当前价 ⇒ ln(1) = 0，避免 ln(0) = -Infinity 污染 logReturn
        double effectiveAnchor = (anchor > 0 && Double.isFinite(anchor)) ? anchor : effectivePrice;
        return theta * Math.log(effectiveAnchor / effectivePrice) * dt;
    }

    /** 跳变项：{@code u1 < lambda*dt} 时返回 {@code magnitude*(2*u2-1)}，否则 {@code 0}。 */
    public static double jump(double lambda, double magnitude, double dtSeconds, double u1, double u2) {
        double dt = effectiveDt(dtSeconds);
        // lambda / dt 为负或 0 ⇒ 本 tick 不发生跳变
        if (dt == 0.0 || !(lambda > 0)) {
            return 0.0;
        }
        return (u1 < lambda * dt) ? magnitude * (2.0 * u2 - 1.0) : 0.0;
    }

    /** 联动相关化：{@code rho*groupZ + sqrt(1-rho^2)*z}（{@code rho} 调用前应已夹取到 {@code [-0.999, 0.999]}）。 */
    public static double correlatedZ(double rho, double groupZ, double z) {
        // 纵深防御：调用方（SimulationService.linkRhoFor）已夹取，这里再夹一次，
        // 否则 |rho| > 1 会让 sqrt(1-rho^2) 变成 NaN。
        double effectiveRho = Double.isFinite(rho) ? Math.max(-0.999, Math.min(0.999, rho)) : 0.0;
        return effectiveRho * groupZ + Math.sqrt(1.0 - effectiveRho * effectiveRho) * z;
    }

    /**
     * 纯推进一档价格。
     *
     * <p>约定：{@code base = max(0.01, autoPrice)} 在 {@code exp()} <b>之前</b>执行；
     * 结果非有限（溢出 / NaN）时回退到 {@code base}。
     */
    public static double advance(double autoPrice, Params p, double z, double u1, double u2) {
        double base = (Double.isFinite(autoPrice) && autoPrice > 0) ? Math.max(0.01, autoPrice) : 0.01;
        if (p == null) {
            return base;
        }
        double logReturn = gbm(p.mu(), p.sigma(), p.dtSeconds(), z)
                + ou(p.theta(), p.anchor(), base, p.dtSeconds())
                + jump(p.jumpLambda(), p.jumpMagnitude(), p.dtSeconds(), u1, u2);
        double next = base * Math.exp(logReturn * p.amplitude())
                + p.slopePerMs() * p.dtSeconds() * 1000.0;
        // exp 溢出为 Infinity / logReturn 为 NaN 时，回落到推进前的价格（资金级兜底）
        if (!Double.isFinite(next)) {
            next = base;
        }
        return next;
    }

    /** 涨跌停带策略（不含绝对上下限）。 */
    public static double applyBand(double price, double lower, double upper, SimulationBoundary boundary) {
        // 异常输入（NaN / ±Inf / <= 0）一律落到下沿
        if (!(price > 0) || !Double.isFinite(price)) {
            return lower;
        }
        SimulationBoundary effective = boundary == null ? SimulationBoundary.SOFT_REGRESSION : boundary;
        switch (effective) {
            case HARD_CLAMP -> price = Math.min(upper, Math.max(lower, price));
            case BOUNCE -> {
                if (price > upper) price = 2.0 * upper - price;      // 从涨停反射
                if (price < lower) price = 2.0 * lower - price;      // 从跌停反射（对称）
                if (price > upper) price = upper;                    // 极端冲击穿两侧后夹到对侧
                if (price < lower) price = lower;
            }
            case SOFT_REGRESSION -> {
                // ⚠ 按公式实施：本 tick 的价格仍在带外，只是越界幅度每 tick 减半
                if (price > upper) price = upper + (price - upper) * 0.5;
                if (price < lower) price = lower + (price - lower) * 0.5;
            }
        }
        return price;
    }

    /** 管理员绝对上下限：始终硬截断。 */
    public static double applyHardBounds(double price, double min, double max) {
        double lo = (Double.isFinite(min) && min > 0) ? min : 0.01;
        // min >= max 的退化输入（由指令层拒绝）在这里退化为"恒等于 lo"，绝不产生越界值
        double hi = (Double.isFinite(max) && max >= lo) ? max : lo;
        double value = Double.isFinite(price) ? price : lo;
        if (value < lo) value = lo;
        if (value > hi) value = hi;
        return value;
    }

    /** 最终兜底：非有限或 {@code < 0.01} → {@code 0.01}。 */
    public static double safePrice(double price) {
        return (Double.isFinite(price) && price >= 0.01) ? price : 0.01;
    }
}
