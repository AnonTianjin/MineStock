package dev.onelili.mstock.simulation;

import java.util.Locale;

/**
 * 模拟盘波动率预设。
 *
 * <p>组件顺序固定为 {@code (sigma, theta, jumpLambda, jumpMagnitude, amplitude)}。
 *
 * <p>数值含义：{@code sigma} 为<b>每秒</b>对数收益标准差（{@code dt} 以秒计，
 * {@code sigma*sqrt(dt)}）。{@code NORMAL = 0.001} ≈ 每秒 0.1%、每小时 σ≈6%、
 * 一天 σ≈30%（在 ±10% 涨跌停带内会被边界策略压制）；{@code jumpLambda = 0.005}
 * ≈ 平均每 200 秒一次跳变。
 *
 * <p><b>这些是默认值，测试必须走显式参数而非预设</b>。
 */
public enum SimulationVolatilityPreset {
    /** 低波动。 */
    LOW(0.0003, 0.05, 0.002, 0.010, 0.5),
    /** 默认。 */
    NORMAL(0.0010, 0.05, 0.005, 0.020, 1.0),
    /** 高波动。 */
    HIGH(0.0030, 0.05, 0.020, 0.040, 2.0);

    private final double sigma;
    private final double theta;
    private final double jumpLambda;
    private final double jumpMagnitude;
    private final double amplitude;

    SimulationVolatilityPreset(double sigma, double theta, double jumpLambda,
                               double jumpMagnitude, double amplitude) {
        this.sigma = sigma;
        this.theta = theta;
        this.jumpLambda = jumpLambda;
        this.jumpMagnitude = jumpMagnitude;
        this.amplitude = amplitude;
    }

    /** 每秒对数收益标准差。 */
    public double sigma() {
        return sigma;
    }

    /** 均值回归速度。 */
    public double theta() {
        return theta;
    }

    /** 跳变强度（每秒发生概率）。 */
    public double jumpLambda() {
        return jumpLambda;
    }

    /** 跳变幅度。 */
    public double jumpMagnitude() {
        return jumpMagnitude;
    }

    /** 对数收益乘数。 */
    public double amplitude() {
        return amplitude;
    }

    /**
     * 解析配置 / 指令里的预设名，无法识别时返回 {@code null}（由调用方决定回落值）。
     *
     * @param raw {@code low} / {@code normal} / {@code high}（大小写不敏感）
     */
    public static SimulationVolatilityPreset parse(String raw) {
        if (raw == null) return null;
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "low" -> LOW;
            case "normal" -> NORMAL;
            case "high" -> HIGH;
            default -> null;
        };
    }

    /** 配置 / 指令里使用的稳定标识（小写枚举名）。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
