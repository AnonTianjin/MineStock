package dev.onelili.mstock.stockio;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface StockSource extends AutoCloseable {
    boolean supports(String code);
    CompletableFuture<StockInfo> fetch(String code);

    default CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("K-line not supported for: " + code));
    }

    /**
     * 带周期的 K 线。
     *
     * <p>默认实现忽略 {@code interval} 并转调 {@link #fetchKLine(String, int)}：
     * 三源（中/港/美）的接口只接受"天数"，由服务端决定粒度，强行让它们接受周期
     * 只会得到一张假的周期图。因此只有真正理解周期的源（模拟盘）才覆写本方法。
     *
     * <p>{@code interval} 取值见 {@code ForexProvider.INTERVALS}：
     * {@code 1m / 5m / 15m / 30m / 1h / 4h / 1d}；{@code limit} 是<b>最多要几根</b>，
     * 不再是"几天"。
     */
    default CompletableFuture<List<KLinePoint>> fetchKLine(String code, String interval, int limit) {
        return fetchKLine(code, limit);
    }

    @Override
    default void close() {
    }
}
