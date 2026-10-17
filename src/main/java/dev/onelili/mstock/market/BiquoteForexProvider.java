package dev.onelili.mstock.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onelili.mstock.model.ForexQuote;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.MarketSymbol;
import dev.onelili.mstock.model.MarketType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * biquote REST 行情实现。
 *
 * <p>端点（均无需 API Key）：
 * <ul>
 *   <li>{@code GET /api/{symbol}} — 最新 tick，价格取 {@code mid}</li>
 *   <li>{@code GET /api/{symbol}/ohlc?interval=&limit=} — K 线 envelope
 *       {@code {symbol, interval, bars:[...]}}</li>
 *   <li>{@code GET /api/symbols?liveOnly=true} — 当前有实时报价的活跃品种</li>
 * </ul>
 *
 * <p>实证结论（2026-10 实测）：
 * <ul>
 *   <li>tick 的 {@code last} 恒为 {@code 0.00000}、{@code volume} 恒为 {@code 0} —— 必须用 {@code mid}</li>
 *   <li>{@code bars} 是<b>最新在前</b>（{@code openTime} 递减），
 *       因此解析后需反转成最早在前，再截取最后 {@code limit} 根</li>
 *   <li>首根带 {@code isOpen: true}，是未收盘 bar</li>
 * </ul>
 */
public class BiquoteForexProvider implements ForexProvider {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_OHLC_LIMIT = 1000;
    /** 连接级失败（GOAWAY / 连接重置 / 5xx）的最大重试次数。 */
    private static final int MAX_SEND_RETRIES = 2;

    private final HttpClient http;
    private final Logger logger;
    private final String baseUrl;
    private final long symbolCacheMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** {@link #close()} 被调用的时刻；只用于关闭后的报错诊断，{@code 0} 表示从未关闭。 */
    private volatile long closedAtMs;

    private final Map<String, CachedSymbols> symbolCache = new ConcurrentHashMap<>();

    /**
     * 报价缓存 TTL：只为合并"同一次点击内的重复取价"。
     *
     * <p>交易页已经显示了一个报价，玩家点"买入"时 {@code ForexService.trade}
     * 还会再打一次 HTTP 取价 —— 同一笔交易要等两个往返，玩家体感就是
     * "点了没反应"。TTL 1 秒既省掉这个往返，又不会让成交价明显偏离
     * 玩家屏幕上看到的那个数。
     */
    private static final long PRICE_CACHE_MS = 1_000L;
    /** 报价缓存条目上限；超限先清过期项，仍超限就放弃本次缓存。 */
    private static final int PRICE_CACHE_SWEEP_AT = 128;
    private static final int PRICE_CACHE_HARD_CAP = 512;

    private final Map<String, CachedQuote> priceCache = new ConcurrentHashMap<>();

    public BiquoteForexProvider(String baseUrl, long symbolCacheMs, Logger logger) {
        this.logger = logger;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.symbolCacheMs = Math.max(0L, symbolCacheMs);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    // ── 实时报价 ──────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<ForexQuote> getPrice(String symbol) {
        String normalized = normalize(symbol);
        if (normalized == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("非法的品种代码: " + symbol));
        }
        CachedQuote cached = priceCache.get(normalized);
        if (cached != null && !cached.isExpired(PRICE_CACHE_MS)) {
            return CompletableFuture.completedFuture(cached.quote);
        }
        HttpRequest request = request("/api/" + encode(normalized));
        return send(request).thenApply(body -> {
            ForexQuote quote = parseTick(normalized, body);
            cacheQuote(normalized, quote);
            return quote;
        });
    }

    /** 写入报价缓存；映射过大时先清过期项，仍超硬上限就放弃本次缓存（宁可不缓存也不无界增长）。 */
    private void cacheQuote(String symbol, ForexQuote quote) {
        if (quote == null) return;
        if (priceCache.size() >= PRICE_CACHE_SWEEP_AT) {
            priceCache.entrySet().removeIf(e -> e.getValue().isExpired(PRICE_CACHE_MS));
            if (priceCache.size() >= PRICE_CACHE_HARD_CAP) return;
        }
        priceCache.put(symbol, new CachedQuote(quote));
    }

    /**
     * 批量报价：一次 {@code /api/latest?symbols=A&symbols=B} 请求取回多个品种。
     *
     * <p>官方文档明确写着 {@code ?symbols=A&symbols=B} 必须<b>重复参数</b>，
     * 逗号形式不被支持。行情页有 45 个格子，批量后从 45 次请求降到 1 次，
     * 既省配额也大幅降低撞上 GOAWAY 的概率。
     */
    @Override
    public CompletableFuture<Map<String, ForexQuote>> getQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<String> normalized = new ArrayList<>();
        for (String symbol : symbols) {
            String value = normalize(symbol);
            if (value != null && !normalized.contains(value)) normalized.add(value);
        }
        if (normalized.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        StringBuilder query = new StringBuilder("/api/latest?symbols=");
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) query.append("&symbols=");
            query.append(encode(normalized.get(i)));
        }
        return send(request(query.toString()))
                .thenApply(body -> parseTicks(normalized, body));
    }

    /**
     * 解析 {@code /api/latest} 的响应。
     *
     * <p>响应形如 {@code {"EURUSD": {...tick...}, "UNIUSDT": {...tick...}}}。
     * 单个品种缺数据或字段损坏只跳过该品种，不影响整批 —— 这正是批量请求的价值：
     * 一个冷门品种拿不到报价，不应该让整页行情变空。
     */
    static Map<String, ForexQuote> parseTicks(List<String> requested, String body) {
        JsonObject root = parseObject(body, "latest");
        Map<String, ForexQuote> result = new LinkedHashMap<>();

        for (String symbol : requested) {
            JsonElement element = root.get(symbol);
            if (element == null || element.isJsonNull() || !element.isJsonObject()) continue;
            try {
                result.put(symbol, parseTick(symbol, element.getAsJsonObject().toString()));
            } catch (RuntimeException e) {
                // 单品种字段异常（如 mid 缺失）只跳过它
                Logger.getLogger(BiquoteForexProvider.class.getName())
                        .fine("[MineStock] 跳过无有效报价的品种 " + symbol + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 解析 tick JSON。
     *
     * <p>价格严格取 {@code mid}；{@code last} / {@code volume} 在外汇 / CFD 上恒为 0，不可使用。
     *
     * <p>市场类型：tick 响应<b>没有</b> {@code type} 字段（那是 {@code /api/symbols} 才有的），
     * 只有 {@code exchange}（{@code FOREX} / {@code crypto} / {@code commodities} / {@code stocks}）。
     * 因此先读 {@code type}，缺失时回退到 {@code exchange}，否则所有品种都会被误判成 {@code FOREX}。
     */
    static ForexQuote parseTick(String symbol, String body) {
        JsonObject root = parseObject(body, "tick");
        double mid = number(root, "mid", Double.NaN);
        if (!Double.isFinite(mid) || mid <= 0) {
            throw new IllegalStateException("biquote tick 缺少有效的 mid 字段: " + symbol);
        }
        String description = string(root, "description", symbol);
        MarketType market = resolveMarketType(root);
        return new ForexQuote(
                string(root, "symbol", symbol),
                description,
                mid,
                number(root, "bid", 0.0),
                number(root, "ask", 0.0),
                number(root, "high", 0.0),
                number(root, "low", 0.0),
                number(root, "dayDiffPercent", 0.0),
                string(root, "marketState", "open"),
                bool(root, "stale", false),
                (long) number(root, "quoteAgeSeconds", 0.0),
                market
        );
    }

    /** 从 tick / symbols 响应里推断市场类型：优先 {@code type}，回退 {@code exchange}。 */
    static MarketType resolveMarketType(JsonObject root) {
        String type = string(root, "type", null);
        if (type != null) return MarketType.fromBiquoteType(type);
        String exchange = string(root, "exchange", null);
        if (exchange != null) return MarketType.fromBiquoteType(exchange);
        return MarketType.FOREX;
    }

    // ── K 线 ──────────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<List<KLinePoint>> getOHLC(String symbol, String interval, int limit) {
        String normalized = normalize(symbol);
        if (normalized == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("非法的品种代码: " + symbol));
        }
        String tf = interval == null ? "1h" : interval.strip().toLowerCase(Locale.ROOT);
        if (!ForexProvider.isSupportedInterval(tf)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("不支持的 K 线周期: " + interval));
        }
        int capped = Math.max(1, Math.min(MAX_OHLC_LIMIT, limit));
        String path = "/api/" + encode(normalized) + "/ohlc?interval=" + encode(tf) + "&limit=" + capped;
        return send(request(path))
                .thenApply(body -> parseOhlcResponse(body, capped));
    }

    /**
     * 解析 OHLC envelope {@code {symbol, interval, bars:[...]}}。
     *
     * <p>biquote 返回的 {@code bars} 为最新在前；这里反转成最早在前，再取最后 {@code limit} 根，
     * 与 {@code KLineRenderer} 期望的时间顺序一致。
     */
    static List<KLinePoint> parseOhlcResponse(String body, int limit) {
        JsonObject root = parseObject(body, "ohlc");
        JsonElement barsElement = root.get("bars");
        if (barsElement == null || !barsElement.isJsonArray()) {
            throw new IllegalStateException("biquote ohlc 响应缺少 bars 数组");
        }
        JsonArray bars = barsElement.getAsJsonArray();
        List<KLinePoint> chronological = new ArrayList<>(bars.size());
        for (JsonElement element : bars) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject bar = element.getAsJsonObject();
            double close = number(bar, "close", Double.NaN);
            if (!Double.isFinite(close)) continue;
            chronological.add(new KLinePoint(
                    number(bar, "open", close),
                    number(bar, "high", close),
                    number(bar, "low", close),
                    close,
                    formatLabel(string(bar, "openTime", ""))
            ));
        }
        // bars 为最新在前 → 反转为最早在前
        Collections.reverse(chronological);
        if (chronological.size() > limit) {
            return new ArrayList<>(chronological.subList(chronological.size() - limit, chronological.size()));
        }
        return chronological;
    }

    /** {@code 2026-10-07T03:00:00Z} → {@code 2026-10-07 03:00}；无法解析时原样返回。 */
    static String formatLabel(String openTime) {
        if (openTime == null) return "";
        String value = openTime.strip();
        int t = value.indexOf('T');
        if (t < 0 || value.length() < t + 6) return value;
        String date = value.substring(0, t);
        String time = value.substring(t + 1, t + 6);
        return date + " " + time;
    }

    // ── 活跃品种 ──────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<List<MarketSymbol>> getActiveSymbols() {
        CachedSymbols cached = symbolCache.get("live");
        if (cached != null && !cached.isExpired(symbolCacheMs)) {
            return CompletableFuture.completedFuture(cached.symbols);
        }
        // liveOnly=true 只返回"此刻正在报价"的品种（目录约 1700，活跃约 280）。
        HttpRequest request = request("/api/symbols?liveOnly=true");
        return send(request).thenApply(body -> {
            List<MarketSymbol> symbols = parseSymbols(body);
            symbolCache.put("live", new CachedSymbols(symbols));
            return symbols;
        });
    }

    /**
     * 解析 {@code /api/symbols} 数组。
     *
     * <p>顺带带出 {@code tickSize} / {@code contractSize}，期货合约派生时可直接使用。
     */
    static List<MarketSymbol> parseSymbols(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (parsed == null || !parsed.isJsonArray()) {
            throw new IllegalStateException("biquote symbols 响应不是 JSON 数组");
        }
        JsonArray array = parsed.getAsJsonArray();
        List<MarketSymbol> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String name = string(object, "name", null);
            if (name == null || name.isBlank()) continue;
            String rawTick = string(object, "tickSize", null);
            String rawContract = string(object, "contractSize", null);
            result.add(new MarketSymbol(
                    name,
                    string(object, "description", name),
                    MarketType.fromBiquoteType(string(object, "type", null)),
                    string(object, "exchange", ""),
                    (int) number(object, "digits", 5.0),
                    parseLoose(rawTick, 0.01),
                    parseLoose(rawContract, 1.0),
                    string(object, "currency", "")
            ));
        }
        return result;
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closedAtMs = System.currentTimeMillis();
        symbolCache.clear();
        priceCache.clear();
        http.shutdownNow();
        // 关闭是一次性且不可逆的：之后任何 getPrice/getQuotes/getOHLC/getActiveSymbols
        // 都会失败。本插件里客户端是长命的，正常只在 onDisable 关一次 ——
        // 所以每次关闭都值得留一条带调用栈的 FINE 记录，用来定位"谁在运行中把它关了"。
        if (logger.isLoggable(Level.FINE)) {
            StackTraceElement[] trace = new Throwable().getStackTrace();
            StringBuilder where = new StringBuilder("biquote 行情客户端关闭，实例 #")
                    .append(System.identityHashCode(this)).append("，调用链:");
            for (int i = 1; i < Math.min(trace.length, 8); i++) {
                where.append("\n    at ").append(trace[i]);
            }
            logger.fine(where.toString());
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "MineStock/1.0")
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    /**
     * 发送请求，并对可恢复的连接级失败做有限重试。
     *
     * <p><b>为什么需要重试</b>：JDK {@code HttpClient} 默认使用 HTTP/2，服务端会通过
     * {@code GOAWAY} 帧主动要求客户端停止复用某个连接（常见于负载均衡摘除节点、连接空闲
     * 超时、或服务端滚动重启）。此时已经建立的连接上正在进行的请求会以
     * {@code java.io.IOException: ... GOAWAY received} 失败，而
     * <b>JDK 不会自动重试</b>，于是一次连接抖动就会让某个品种的报价直接报错。
     *
     * <p>重试只针对连接级异常（{@code IOException} / {@code GOAWAY}），
     * 业务级错误（429 限流、404 无此品种）不重试 —— 重试它们只会加重限流。
     * 重试前新建请求对象，让客户端有机会选择另一条连接。
     */
    private CompletableFuture<String> send(HttpRequest request) {
        return send(request, 0);
    }

    private CompletableFuture<String> send(HttpRequest request, int attempt) {
        if (closed.get()) {
            // 这条报错曾经极难定位：它看起来像网络故障，实际是"有人拿着已关闭的实例在发请求"。
            // 把实例身份与关闭原因一起带出来，下次一眼能看出是同一个 provider 被复用，
            // 还是每次新建都立刻被关（后者指向 onEnable 重入 / 插件管理器热重载）。
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "biquote 行情客户端已关闭（实例 #" + System.identityHashCode(this)
                            + "，关闭于 " + closedAtMs + "ms，当前 " + System.currentTimeMillis()
                            + "ms）"));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status == 200) return response.body();
                    if (status == 429) {
                        String retryAfter = response.headers().firstValue("Retry-After").orElse("60");
                        throw new BiquoteHttpException(429, "biquote 触发限流，请在 " + retryAfter + " 秒后重试");
                    }
                    if (status == 404) {
                        throw new BiquoteHttpException(404, "biquote 无此品种或已停止报价");
                    }
                    // 5xx 属于服务端临时故障，可重试
                    throw new BiquoteHttpException(status, "biquote HTTP " + status);
                })
                .exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    if (attempt >= MAX_SEND_RETRIES || !isRetryable(cause)) {
                        return CompletableFuture.failedFuture(cause);
                    }
                    logger.fine("[MineStock] biquote 请求失败，重试 "
                            + (attempt + 1) + "/" + MAX_SEND_RETRIES + ": " + cause.getMessage());
                    // 重建请求对象：同一个 HttpRequest 实例可复用，但新建更利于重新选连接
                    HttpRequest retry = request.newBuilder().build();
                    return send(retry, attempt + 1);
                });
    }

    /** 是否为可重试的连接级 / 服务端临时故障。 */
    static boolean isRetryable(Throwable cause) {
        if (cause == null) return false;
        // GOAWAY / 连接被重置 / 读取超时等都属于 IOException 族
        if (cause instanceof java.io.IOException) return true;
        if (cause instanceof BiquoteHttpException httpError) {
            return httpError.status() >= 500;
        }
        return false;
    }

    /** 剥掉 {@code CompletionException} 包装，拿到真实原因。 */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** 带状态码的 biquote 业务异常，便于区分"可重试"与"不该重试"。 */
    static final class BiquoteHttpException extends RuntimeException {
        private final int status;

        BiquoteHttpException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private static JsonObject parseObject(String body, String what) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IllegalStateException("biquote " + what + " 响应不是 JSON 对象");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("解析 biquote " + what + " 响应失败: " + e.getMessage(), e);
        }
    }

    private static String normalize(String symbol) {
        if (symbol == null) return null;
        String value = symbol.strip().toUpperCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "https://biquote.io";
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isEmpty() ? "https://biquote.io" : trimmed;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        String value = element.getAsString();
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** tickSize / contractSize 可能是数字或字符串，统一宽松解析。 */
    private static double parseLoose(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record CachedSymbols(List<MarketSymbol> symbols, long timestamp) {
        CachedSymbols(List<MarketSymbol> symbols) {
            this(symbols, System.currentTimeMillis());
        }

        boolean isExpired(long ttlMs) {
            return ttlMs <= 0 || System.currentTimeMillis() - timestamp > ttlMs;
        }
    }

    /** 单品种报价缓存条目；结构同 {@link CachedSymbols}，载荷是 {@link ForexQuote}。 */
    private record CachedQuote(ForexQuote quote, long timestamp) {
        CachedQuote(ForexQuote quote) {
            this(quote, System.currentTimeMillis());
        }

        boolean isExpired(long ttlMs) {
            return ttlMs <= 0 || System.currentTimeMillis() - timestamp > ttlMs;
        }
    }
}
