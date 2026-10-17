package dev.onelili.mstock.simulation;

/**
 * 模拟股的人工覆盖指令快照（不可变值类型，纯函数可单测）。
 *
 * <p><b>为什么要快照</b>：tick 线程与管理员写操作并发，直接读 {@link SimulationStock}
 * 会在一次 {@code resolve} 里读到半更新状态。{@code of(stock)} 一次性把需要的字段拷出来，
 * 之后 {@code resolve} 只依赖这份快照。
 */
public record SimulationDirective(Double manualPrice, Double manualTargetPercent,
                                  double slopePerMs, long slopeUntil, boolean paused) {

    /** 无任何人工覆盖。 */
    public static SimulationDirective none() {
        return new SimulationDirective(null, null, 0.0, 0L, false);
    }

    /** 把当前股票状态快照成不可变指令对象。 */
    public static SimulationDirective of(SimulationStock stock) {
        return new SimulationDirective(stock.getManualPrice(), stock.getManualTargetPercent(),
                stock.getSlopePerMs(), stock.getSlopeUntil(), stock.isPaused());
    }

    /** 是否设置了人工价格覆盖（定值或目标涨幅）。 */
    public boolean hasOverride() {
        return manualPrice != null || manualTargetPercent != null;
    }

    /** 斜率项此刻是否仍在生效窗口内（{@code slopePerMs == 0} 时恒为 {@code false}）。 */
    public boolean slopeActive(long nowMillis) {
        return slopePerMs != 0.0 && nowMillis < slopeUntil;
    }

    /** 所有人工干预都失效（无斜率、未暂停、无价格覆盖）。 */
    public boolean expired(long nowMillis) {
        return !slopeActive(nowMillis) && !paused && !hasOverride();
    }

    /**
     * 解析出本 tick 的"基准价"。
     *
     * <p>优先级：定值 {@code manualPrice} → 目标涨幅 {@code manualTargetPercent}
     * （相对昨收）→ 自动价 {@code autoPrice}。注意<b>斜率不在这里生效</b>，
     * 它由价格引擎的 {@code slopePerMs} 项叠加（见 {@code advance}）。
     */
    public double resolve(double autoPrice, double prevClose) {
        if (manualPrice != null) return manualPrice;
        if (manualTargetPercent != null) return prevClose * (1 + manualTargetPercent / 100.0);
        return autoPrice;
    }
}
