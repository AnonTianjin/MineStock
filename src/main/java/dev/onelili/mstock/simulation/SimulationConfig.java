package dev.onelili.mstock.simulation;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 模拟盘配置读取的<b>集中点</b>。
 *
 * <p><b>为什么不直接用 {@code MainConfig}</b>：{@code MainConfig} 的构造器会调用
 * {@code plugin.saveDefaultConfig()}，单元测试无法构造它；而 {@code SimulationPriceEngine} /
 * {@code SimulationStockRegistry} 的测试必须完全不碰 Bukkit。本类只依赖
 * {@link FileConfiguration} 接口，并把数值以<b>显式参数</b>传给纯计算层。
 */
public final class SimulationConfig {

    private final FileConfiguration cfg;

    /** @param cfg 传 {@code plugin.getConfig()} */
    public SimulationConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    /** {@code simulation.enabled}，默认 {@code true}。 */
    public boolean enabled() {
        return cfg == null || cfg.getBoolean("simulation.enabled", true);
    }

    /** {@code simulation.tick-seconds}，默认 {@code 1.0}，夹取 {@code [0.1, 60]}。 */
    public double tickSeconds() {
        if (cfg == null) return 1.0;
        return clamp(cfg.getDouble("simulation.tick-seconds", 1.0), 0.1, 60.0);
    }

    /** {@code simulation.auto-fill-count}，默认 {@code 10}，夹取 {@code [1, 200]}。 */
    public int autoFillCount() {
        if (cfg == null) return 10;
        int raw = cfg.getInt("simulation.auto-fill-count", 10);
        return Math.max(1, Math.min(200, raw));
    }

    /** {@code simulation.seed-base}，默认 {@code 20260101}。 */
    public long seedBase() {
        if (cfg == null) return 20260101L;
        return cfg.getLong("simulation.seed-base", 20260101L);
    }

    /** {@code simulation.default-limit-percent}，默认 {@code 10.0}，夹取 {@code [0.0001, 10.0]}（百分数口径）。 */
    public double defaultLimitPercent() {
        if (cfg == null) return 10.0;
        return clamp(cfg.getDouble("simulation.default-limit-percent", 10.0), 0.0001, 10.0);
    }

    /** {@code simulation.default-preset}，默认 {@link SimulationVolatilityPreset#NORMAL}。 */
    public SimulationVolatilityPreset defaultPreset() {
        if (cfg == null) return SimulationVolatilityPreset.NORMAL;
        SimulationVolatilityPreset parsed =
                SimulationVolatilityPreset.parse(cfg.getString("simulation.default-preset", "normal"));
        return parsed == null ? SimulationVolatilityPreset.NORMAL : parsed;
    }

    /** {@code simulation.default-boundary}，默认 {@link SimulationBoundary#SOFT_REGRESSION}。 */
    public SimulationBoundary defaultBoundary() {
        if (cfg == null) return SimulationBoundary.SOFT_REGRESSION;
        SimulationBoundary parsed =
                SimulationBoundary.parse(cfg.getString("simulation.default-boundary", "soft"));
        return parsed == null ? SimulationBoundary.SOFT_REGRESSION : parsed;
    }

    /** {@code simulation.min-price}，默认 {@code 0.01}。 */
    public double minPrice() {
        if (cfg == null) return 0.01;
        return cfg.getDouble("simulation.min-price", 0.01);
    }

    /** {@code simulation.max-price}，默认 {@code 1_000_000.0}。 */
    public double maxPrice() {
        if (cfg == null) return 1_000_000.0;
        return cfg.getDouble("simulation.max-price", 1_000_000.0);
    }

    /** {@code simulation.kline-capacity}，默认 {@code 512}，夹取 {@code [64, 4096]}。 */
    public int klineCapacity() {
        if (cfg == null) return 512;
        int raw = cfg.getInt("simulation.kline-capacity", 512);
        return Math.max(64, Math.min(4096, raw));
    }

    /** {@code simulation.link.group-weight}，默认 {@code 1.0}。 */
    public double linkGroupWeight() {
        if (cfg == null) return 1.0;
        return cfg.getDouble("simulation.link.group-weight", 1.0);
    }
}
