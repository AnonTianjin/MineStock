package dev.onelili.mstock.simulation;

import java.util.Random;

/**
 * 模拟盘的单只股票模型（可变）。
 *
 * <p><b>并发约定</b>：字段全部 {@code volatile}，写操作统一在 {@code synchronized (stock)} 内进行
 * —— tick 线程与管理员写可能并发（{@code LifecycleTaskManager} 常规池 2 线程），
 * 逐字段读写配合对象锁才能保证一次 tick 读到自洽的一组值。
 *
 * <p><b>单位规范（全模块统一，不得混用）</b>：
 * <ul>
 *   <li>{@code limitPercent} / {@code manualTargetPercent} —— <b>百分数</b>（{@code 10.0} = ±10%）</li>
 *   <li>{@code slopePerMs} —— 每毫秒增加的<b>绝对价格</b></li>
 *   <li>{@code slopeUntil} —— epoch 毫秒</li>
 *   <li>{@code amplitude} —— 对数收益乘数</li>
 *   <li>{@code minPrice}/{@code maxPrice} —— 绝对价格</li>
 * </ul>
 */
public final class SimulationStock {

    /** 涨跌停百分数口径的合法区间下界（{@code 0.0001 %}，保证带宽严格为正）。 */
    private static final double MIN_LIMIT_PERCENT = 0.0001;

    /** 涨跌停百分数口径的合法区间上界（{@code 10.0 %} = ±10%，与 A 股一致）。 */
    private static final double MAX_LIMIT_PERCENT = 10.0;

    private final String code;
    private final String name;
    private final String sector;
    private final double initPrice;
    private long seed;

    private volatile double prevClose;
    private volatile double currentPrice;
    private volatile double autoPrice;
    private volatile double anchor;
    private volatile double limitPercent;
    private volatile double amplitude;
    private volatile double mu;
    private volatile double sigma;
    private volatile double theta;
    private volatile double jumpLambda;
    private volatile double jumpMagnitude;
    private volatile double minPrice;
    private volatile double maxPrice;
    private volatile SimulationVolatilityPreset preset;
    private volatile SimulationBoundary boundary;
    private volatile boolean suspended;
    private volatile boolean tradable;
    private volatile boolean paused;
    private volatile Double manualPrice;
    private volatile Double manualTargetPercent;
    private volatile double slopePerMs;
    private volatile long slopeUntil;
    private volatile long updatedAt;

    /** 价引擎专用随机源；{@link #reseed(long)} 时重建。 */
    private Random random;

    /**
     * 简版构造：{@code initPrice} 同时作为 prevClose / currentPrice / autoPrice / anchor 的初值，
     * 并套用默认的涨跌停、波动预设与边界策略。
     */
    public SimulationStock(String code, String name, String sector, double initPrice, long seed) {
        this(code, name, sector, initPrice, seed,
                10.0, SimulationVolatilityPreset.NORMAL,
                SimulationBoundary.SOFT_REGRESSION, 0.01, 1_000_000.0);
    }

    /**
     * 完整构造。
     *
     * <p>{@code initPrice} 同时作为 init / prevClose / currentPrice / autoPrice / anchor 的初值。
     */
    public SimulationStock(String code, String name, String sector, double initPrice, long seed,
                           double limitPercent, SimulationVolatilityPreset preset,
                           SimulationBoundary boundary, double minPrice, double maxPrice) {
        this.code = code;
        this.name = name;
        this.sector = sector;
        this.initPrice = initPrice;
        this.seed = seed;
        this.random = new Random(seed);
        this.prevClose = initPrice;
        this.currentPrice = initPrice;
        this.autoPrice = initPrice;
        this.anchor = initPrice;
        this.limitPercent = limitPercent;
        this.preset = preset == null ? SimulationVolatilityPreset.NORMAL : preset;
        this.boundary = boundary == null ? SimulationBoundary.SOFT_REGRESSION : boundary;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.tradable = true;
        this.updatedAt = System.currentTimeMillis();
        applyPreset(this.preset);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSector() {
        return sector;
    }

    public double getInitPrice() {
        return initPrice;
    }

    public long getSeed() {
        return seed;
    }

    public double getPrevClose() {
        return prevClose;
    }

    public void setPrevClose(double v) {
        this.prevClose = v;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(double v) {
        this.currentPrice = v;
    }

    public double getAutoPrice() {
        return autoPrice;
    }

    public void setAutoPrice(double v) {
        this.autoPrice = v;
    }

    public double getAnchor() {
        return anchor;
    }

    public void setAnchor(double v) {
        this.anchor = v;
    }

    public double getLimitPercent() {
        return limitPercent;
    }

    /** 夹取到 {@code [0.0001, 10.0]}（百分数口径）。 */
    public void setLimitPercent(double v) {
        this.limitPercent = clampLimitPercent(v);
    }

    public double getAmplitude() {
        return amplitude;
    }

    public void setAmplitude(double v) {
        this.amplitude = v;
    }

    public double getMu() {
        return mu;
    }

    public void setMu(double v) {
        this.mu = v;
    }

    public double getSigma() {
        return sigma;
    }

    public void setSigma(double v) {
        this.sigma = v;
    }

    public double getTheta() {
        return theta;
    }

    public void setTheta(double v) {
        this.theta = v;
    }

    public double getJumpLambda() {
        return jumpLambda;
    }

    public void setJumpLambda(double v) {
        this.jumpLambda = v;
    }

    public double getJumpMagnitude() {
        return jumpMagnitude;
    }

    public void setJumpMagnitude(double v) {
        this.jumpMagnitude = v;
    }

    public double getMinPrice() {
        return minPrice;
    }

    public double getMaxPrice() {
        return maxPrice;
    }

    /**
     * 设置管理员绝对上下限。
     *
     * <p>要求 {@code 0.01 <= min < max <= 1.0e9}；非法参数整组丢弃、保持原值，
     * 保证库存里永不出现 {@code min >= max} 的退化区间。
     */
    public void setBounds(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) return;
        if (min < 0.01 || min >= max || max > 1.0e9) return;
        this.minPrice = min;
        this.maxPrice = max;
    }

    public SimulationVolatilityPreset getPreset() {
        return preset;
    }

    /** 覆写 {@code sigma/theta/jumpLambda/jumpMagnitude/amplitude}。 */
    public void applyPreset(SimulationVolatilityPreset p) {
        if (p == null) return;
        this.preset = p;
        this.sigma = p.sigma();
        this.theta = p.theta();
        this.jumpLambda = p.jumpLambda();
        this.jumpMagnitude = p.jumpMagnitude();
        this.amplitude = p.amplitude();
    }

    public SimulationBoundary getBoundary() {
        return boundary;
    }

    public void setBoundary(SimulationBoundary b) {
        this.boundary = b;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean v) {
        this.suspended = v;
    }

    public boolean isTradable() {
        return tradable;
    }

    public void setTradable(boolean v) {
        this.tradable = v;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean v) {
        this.paused = v;
    }

    /** {@code null} 表示无覆盖。 */
    public Double getManualPrice() {
        return manualPrice;
    }

    /**
     * 设置定值覆盖。
     *
     * <p>与 {@link #setManualTargetPercent(Double)} <b>互斥写</b> —— 设置其一必须把另一个置
     * {@code null}，保证 {@link SimulationDirective#resolve} 的优先级分支永远只有一个命中。
     */
    public void setManualPrice(Double v) {
        this.manualPrice = v;
        if (v != null) {
            this.manualTargetPercent = null;
        }
    }

    /** {@code null} 表示无覆盖。 */
    public Double getManualTargetPercent() {
        return manualTargetPercent;
    }

    /**
     * 设置目标涨幅覆盖（相对昨收）。
     *
     * <p>与 {@link #setManualPrice(Double)} <b>互斥写</b>。
     */
    public void setManualTargetPercent(Double v) {
        this.manualTargetPercent = v;
        if (v != null) {
            this.manualPrice = null;
        }
    }

    public double getSlopePerMs() {
        return slopePerMs;
    }

    /** 夹取到 {@code [-1.0e6, 1.0e6]}（单位：每毫秒的绝对价格增量）。 */
    public void setSlopePerMs(double v) {
        if (!Double.isFinite(v)) {
            this.slopePerMs = 0.0;
            return;
        }
        this.slopePerMs = Math.max(-1.0e6, Math.min(1.0e6, v));
    }

    public long getSlopeUntil() {
        return slopeUntil;
    }

    public void setSlopeUntil(long v) {
        this.slopeUntil = v;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long v) {
        this.updatedAt = v;
    }

    /** 包级：价引擎用；{@link #reseed(long)} 时重建。 */
    Random random() {
        return random;
    }

    /** 重建随机源：{@code random = new Random(seed)}，同种子 ⇒ 同一价格路径。 */
    public void reseed(long seed) {
        this.random = new Random(seed);
        this.seed = seed;
    }

    /**
     * 跌停价：{@code prevClose * (1 - limitPercent / 100.0)}。
     *
     * <p><b>全模块唯一一处 {@code limitPercent / 100.0} 换算</b>：口径为百分数，
     * {@code limitPercent = 10.0} ⇒ ±10%。若宿主本意是比例口径，只需改本方法、
     * {@link #upperLimit()} 与 {@link #MAX_LIMIT_PERCENT} 三处常量。
     */
    public double lowerLimit() {
        return prevClose * (1.0 - limitPercent / 100.0);
    }

    /**
     * 涨停价：{@code prevClose * (1 + limitPercent / 100.0)}。
     *
     * @see #lowerLimit() 百分数口径说明
     */
    public double upperLimit() {
        return prevClose * (1.0 + limitPercent / 100.0);
    }

    /** {@code currentPrice - prevClose}。 */
    public double changeAmount() {
        return currentPrice - prevClose;
    }

    /** {@code prevClose > 0 ? changeAmount / prevClose * 100.0 : 0.0}（不得产生 NaN / Infinity）。 */
    public double changePercent() {
        if (!(prevClose > 0) || !Double.isFinite(prevClose)) {
            return 0.0;
        }
        double change = changeAmount();
        return Double.isFinite(change) ? change / prevClose * 100.0 : 0.0;
    }

    /** 快照成不可变指令对象（避免巡检线程读到半更新状态）。 */
    public SimulationDirective directive() {
        return SimulationDirective.of(this);
    }

    /** 夹取涨跌停百分数：非有限值一律取下界，保证带宽严格为正。 */
    private static double clampLimitPercent(double v) {
        if (!Double.isFinite(v)) {
            return MIN_LIMIT_PERCENT;
        }
        return Math.max(MIN_LIMIT_PERCENT, Math.min(MAX_LIMIT_PERCENT, v));
    }

    @Override
    public String toString() {
        return "SimulationStock{" + code + " " + name + " price=" + currentPrice + "}";
    }
}
