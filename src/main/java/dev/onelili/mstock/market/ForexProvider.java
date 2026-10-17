package dev.onelili.mstock.market;

import dev.onelili.mstock.model.ForexQuote;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.MarketSymbol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 外汇 / CFD 行情来源。
 *
 * <p>所有方法都是异步的：调用方不得在主线程阻塞等待网络。
 *
 * <p>价格语义：biquote 的 {@code last} 与 {@code volume} 在外汇 / CFD 数据源上恒为 0，
 * 因此实现必须返回 {@code mid} 字段。
 */
public interface ForexProvider extends AutoCloseable {

    /** 实时中间价报价。 */
    CompletableFuture<ForexQuote> getPrice(String symbol);

    /**
     * 批量实时报价。
     *
     * <p>biquote 官方明确要求：轮询时用 {@code /api/latest} 一次取多个品种，
     * <b>绝不要一个品种一次请求</b>。GUI 行情页有 45 个格子，逐个请求既浪费配额，
     * 也更容易撞上 HTTP/2 的 GOAWAY 断连。
     *
     * <p>默认实现逐个回退到 {@link #getPrice}，具体实现应覆盖为真正的批量端点。
     *
     * @param symbols 品种代码列表
     * @return 以品种代码为键的报价；拿不到报价的品种<b>不会</b>出现在结果里，
     *         也不会让整批失败
     */
    default CompletableFuture<Map<String, ForexQuote>> getQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<String> normalized = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) continue;
            normalized.add(symbol.strip().toUpperCase(Locale.ROOT));
        }
        List<CompletableFuture<Map.Entry<String, ForexQuote>>> requests = new ArrayList<>();
        for (String symbol : normalized) {
            requests.add(getPrice(symbol)
                    .thenApply(quote -> Map.entry(symbol, quote))
                    .exceptionally(error -> null));
        }
        return CompletableFuture
                .allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, ForexQuote> result = new LinkedHashMap<>();
                    for (CompletableFuture<Map.Entry<String, ForexQuote>> request : requests) {
                        Map.Entry<String, ForexQuote> entry = request.getNow(null);
                        if (entry != null && entry.getValue() != null) {
                            result.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return result;
                });
    }

    /**
     * K 线数据，返回顺序为最早在前。
     *
     * @param symbol   品种代码
     * @param interval {@code 1m} / {@code 5m} / {@code 15m} / {@code 30m} / {@code 1h} / {@code 4h} / {@code 1d}
     * @param limit    返回的最大根数
     */
    CompletableFuture<List<KLinePoint>> getOHLC(String symbol, String interval, int limit);

    /** 当前有实时报价的活跃品种（{@code liveOnly: true}）。 */
    CompletableFuture<List<MarketSymbol>> getActiveSymbols();

    /** 该品种是否被数据源认可。默认乐观返回 {@code true}，由实际请求结果兜底。 */
    default boolean supports(String symbol) {
        return symbol != null && !symbol.isBlank();
    }

    /** 支持的 K 线周期。 */
    List<String> INTERVALS = List.of("1m", "5m", "15m", "30m", "1h", "4h", "1d");

    static boolean isSupportedInterval(String interval) {
        return interval != null && INTERVALS.contains(interval.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    default void close() {
    }
}
