package dev.onelili.mstock.simulation;

/**
 * 模拟盘的一根 1 分钟 OHLC bar。
 *
 * @param bucketStart 桶起点（epoch 毫秒，且已按 60 秒对齐：{@code epochMillis - epochMillis % 60_000L}）
 * @param open        桶内首个价格
 * @param high        桶内最高价
 * @param low         桶内最低价
 * @param close       桶内末个价格
 */
public record SimulationBar(long bucketStart, double open, double high, double low, double close) {

    /** 平盘 bar：四个价相等，用于缓冲为空时合成"上一分钟"占位（见 {@code withFallback}）。 */
    public static SimulationBar flat(long bucketStart, double price) {
        return new SimulationBar(bucketStart, price, price, price, price);
    }
}
