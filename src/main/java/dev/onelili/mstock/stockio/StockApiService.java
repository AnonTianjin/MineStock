package dev.onelili.mstock.stockio;

import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class StockApiService implements AutoCloseable {
    private final List<StockSource> sources = new ArrayList<>();
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    // K-line cache: key = "CODE:days", value = cached result + timestamp
    private final Map<String, KLineCacheEntry> klineCache = new ConcurrentHashMap<>();
    private long klineCacheMs = 300_000L; // 5 minutes default

    /**
     * 报价缓存：吃掉"同一次操作里的多次取价"。
     *
     * <p>原实现每次 {@link #fetch} 都直接打 HTTP。而一次完整买入会依次经过
     * {@code initBuy}（取价→提示数量）、{@code showTradeConfirm}（取价→确认页）、
     * {@code executeBuy}（取价→结算）三次同代码取价 —— 玩家体感就是
     * "点了买入要等很久"，但三次问的其实是同一件事。
     *
     * <p>TTL 刻意很短（默认 1 秒）：只为合并同一次点击内的重复请求，
     * 不会让详情页显示一个明显过期的价格。{@code <= 0} 表示关闭缓存
     * （包级构造器默认关闭，保证既有单元测试的取价次数逐字不变）。
     */
    private final Map<String, PriceCacheEntry> priceCache = new ConcurrentHashMap<>();
    private long priceCacheMs = 1_000L;

    /** 报价缓存条目上限；超过就丢弃过期项，仍超过则本次不写缓存。 */
    private static final int PRICE_CACHE_SWEEP_AT = 128;
    private static final int PRICE_CACHE_HARD_CAP = 1024;

    /** K 线缓存条目上限；与报价缓存同一策略（见 {@link #cacheKLine}）。 */
    private static final int KLINE_CACHE_SWEEP_AT = 128;
    private static final int KLINE_CACHE_HARD_CAP = 1024;

    /**
     * @param leadingSources 插到 {@code sources} 最前面的数据源（可变参数，可为空）。
     *                       {@code StockApiService} 的遍历语义是"首个 {@code supports} 者胜出"，
     *                       因此模拟盘行情源必须排在这里的 index 0。
     *                       既有 2 参调用点不受影响（varargs 为空，行为逐字不变）。
     */
    public StockApiService(MainConfig config, Logger logger, StockSource... leadingSources) {
        this.logger = logger;
        for (StockSource leading : leadingSources) {
            if (leading != null) sources.add(leading);
        }
        sources.add(new ChinaStockApi(logger));
        sources.add(new HkStockApi(logger));

        List<String> finnhubKeys    = new ArrayList<>();
        List<String> twelveDataKeys = new ArrayList<>();

        for (Map<?, ?> entry : config.getUsStockApis()) {
            String name = String.valueOf(entry.get("name"));
            List<String> keys = resolveKeys(entry);
            if ("finnhub".equalsIgnoreCase(name))       finnhubKeys.addAll(keys);
            else if ("twelvedata".equalsIgnoreCase(name)) twelveDataKeys.addAll(keys);
        }
        sources.add(new UsStockApi(logger, finnhubKeys, twelveDataKeys));

        long cfgCacheMs = config.getKlineCacheMs();
        if (cfgCacheMs > 0) klineCacheMs = cfgCacheMs;
    }

    StockApiService(List<StockSource> sources, long klineCacheMs, Logger logger) {
        this.logger = logger;
        this.sources.addAll(sources);
        if (klineCacheMs > 0) this.klineCacheMs = klineCacheMs;
        // 包级构造器是测试专用的：默认关掉报价缓存，让"取了几次价"的断言保持原有语义
        this.priceCacheMs = 0L;
    }

    /**
     * Reads API keys from a config entry.
     * Supports both the new list form (apikeys: [...]) and the legacy single-key form (apikey: "...").
     */
    private static List<String> resolveKeys(Map<?, ?> entry) {
        Object listVal = entry.get("apikeys");
        if (listVal instanceof List<?> rawList) {
            List<String> keys = new ArrayList<>();
            for (Object o : rawList) {
                String k = o != null ? String.valueOf(o).strip() : "";
                if (!k.isBlank()) keys.add(k);
            }
            if (!keys.isEmpty()) return keys;
        }
        // Fall back to legacy single apikey
        Object single = entry.get("apikey");
        String k = single != null ? String.valueOf(single).strip() : "";
        return k.isBlank() ? List.of() : List.of(k);
    }

    public CompletableFuture<StockInfo> fetch(String code) {
        if (closed.get()) return closedFuture();
        if (priceCacheMs > 0) {
            PriceCacheEntry cached = priceCache.get(code);
            if (cached != null && !cached.isExpired(priceCacheMs)) {
                return CompletableFuture.completedFuture(cached.data);
            }
        }
        for (StockSource source : sources) {
            if (source.supports(code)) {
                return track(() -> source.fetch(code).thenApply(info -> {
                    cachePrice(code, info);
                    return info;
                }));
            }
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code: " + code));
    }

    /** 写入报价缓存；映射过大时先清过期项，仍超硬上限就放弃本次缓存（宁可不缓存也不无界增长）。 */
    private void cachePrice(String code, StockInfo info) {
        if (priceCacheMs <= 0 || info == null || code == null) {
            return;
        }
        if (priceCache.size() >= PRICE_CACHE_SWEEP_AT) {
            priceCache.entrySet().removeIf(e -> e.getValue().isExpired(priceCacheMs));
            if (priceCache.size() >= PRICE_CACHE_HARD_CAP) {
                return;
            }
        }
        priceCache.put(code, new PriceCacheEntry(info));
    }

    /**
     * 写入 K 线缓存；映射过大时先清过期项，仍超硬上限就放弃本次缓存
     * （宁可不缓存也不无界增长）。
     *
     * <p>K 线缓存键含周期与根数（{@code code:interval:limit}），组合数远多于报价缓存，
     * 不设上限的话玩家每翻一页图表就多几个常驻条目。清扫阈值用基准 TTL
     * （{@link #klineCacheMs}）：短周期条目本就更快过期，只是晚一点被回收，无碍正确性。
     */
    private void cacheKLine(String cacheKey, List<KLinePoint> data) {
        if (data == null || cacheKey == null) {
            return;
        }
        if (klineCache.size() >= KLINE_CACHE_SWEEP_AT) {
            klineCache.entrySet().removeIf(e -> e.getValue().isExpired(klineCacheMs));
            if (klineCache.size() >= KLINE_CACHE_HARD_CAP) {
                return;
            }
        }
        klineCache.put(cacheKey, new KLineCacheEntry(data));
    }

    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        if (closed.get()) return closedFuture();
        String cacheKey = code + ":" + days;
        KLineCacheEntry cached = klineCache.get(cacheKey);
        if (cached != null && !cached.isExpired(klineCacheMs)) {
            return CompletableFuture.completedFuture(cached.data);
        }
        for (StockSource source : sources) {
            if (source.supports(code)) {
                return track(() -> source.fetchKLine(code, days).thenApply(data -> {
                    cacheKLine(cacheKey, data);
                    return data;
                }));
            }
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code for K-line: " + code));
    }

    /**
     * 带周期的 K 线。
     *
     * <p>缓存键必须带上周期：同一代码在 {@code 1m} 与 {@code 1d} 下的根数与时间标签完全不同，
     * 只按 {@code code:limit} 缓存会让先请求的那个周期污染另一个（玩家在 1m 与 1d 之间来回切
     * 就会看到同一个图）。
     *
     * <p>非模拟源走 {@link StockSource#fetchKLine(String, String, int)} 的默认实现，
     * 即忽略周期、把 {@code limit} 当"天数"传下去 —— 与旧行为逐字一致。
     */
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, String interval, int limit) {
        if (closed.get()) return closedFuture();
        String tf = interval == null || interval.isBlank()
                ? "1m" : interval.strip().toLowerCase(java.util.Locale.ROOT);
        String cacheKey = code + ":" + tf + ":" + limit;
        long ttl = Math.min(klineCacheMs, intervalTtlMillis(tf));
        KLineCacheEntry cached = klineCache.get(cacheKey);
        if (cached != null && !cached.isExpired(ttl)) {
            return CompletableFuture.completedFuture(cached.data);
        }
        for (StockSource source : sources) {
            if (source.supports(code)) {
                return track(() -> source.fetchKLine(code, tf, limit).thenApply(data -> {
                    cacheKLine(cacheKey, data);
                    return data;
                }));
            }
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code for K-line: " + code));
    }

    /**
     * 周期越短，缓存越短。
     *
     * <p>K 线在时间上是有粒度的：正在走的那根 1m 柱子每秒都在变，而 K 线缓存默认 5 分钟，
     * 于是玩家在 5 分钟内反复打开同一张图都是同一份数据 —— 这就是"刷新不及时"的直接来源。
     * 缓存时长必须压到该周期的一个零头之内，否则它缓存的是"上一根柱子"而不是"这张图"。
     *
     * <p>日线及以上保持配置值（默认 5 分钟）即可：一根日线一天才动一次。
     */
    private static long intervalTtlMillis(String interval) {
        return switch (interval) {
            case "1m" -> 5_000L;
            case "5m" -> 20_000L;
            case "15m" -> 60_000L;
            case "30m" -> 90_000L;
            case "1h" -> 180_000L;
            default -> 300_000L;
        };
    }

    public boolean isSupported(String code) {
        if (closed.get()) return false;
        return sources.stream().anyMatch(s -> s.supports(code));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        for (CompletableFuture<?> future : List.copyOf(inFlight)) future.cancel(true);
        inFlight.clear();
        klineCache.clear();
        priceCache.clear();
        for (StockSource source : sources) {
            try {
                source.close();
            } catch (RuntimeException e) {
                logger.warning("[MineStock] 关闭行情数据源失败: " + e.getMessage());
            }
        }
    }

    private <T> CompletableFuture<T> track(Supplier<CompletableFuture<T>> operation) {
        if (closed.get()) return closedFuture();

        CompletableFuture<T> future;
        try {
            future = operation.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        inFlight.add(future);
        future.whenComplete((ignored, error) -> inFlight.remove(future));
        if (closed.get() && inFlight.remove(future)) future.cancel(true);
        return future;
    }

    private static <T> CompletableFuture<T> closedFuture() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("MineStock stock service is closed"));
    }

    private static class KLineCacheEntry {
        final List<KLinePoint> data;
        final long timestamp;

        KLineCacheEntry(List<KLinePoint> data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }

    /** 报价缓存条目；结构同 {@link KLineCacheEntry}，只是载荷类型不同。 */
    private static class PriceCacheEntry {
        final StockInfo data;
        final long timestamp;

        PriceCacheEntry(StockInfo data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }
}
