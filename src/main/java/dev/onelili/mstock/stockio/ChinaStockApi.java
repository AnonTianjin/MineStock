package dev.onelili.mstock.stockio;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ChinaStockApi implements StockSource {

    private static final Pattern CHINA_CODE = Pattern.compile("^\\d{6}$");
    private final HttpClient http;
    private final Logger logger;

    public ChinaStockApi(Logger logger) {
        this.logger = logger;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean supports(String code) {
        return CHINA_CODE.matcher(code).matches();
    }

    /**
     * 按代码段映射交易所前缀（新浪 / 腾讯通用）。
     *
     * <p>只看首位字符会把 ETF / 科创 / B 股 / 北交所全部拼成无效代码
     * （{@code 510300 → sz510300}），新浪返回空串，详情页、K 线、持仓估值稳定失败。
     *
     * <ul>
     *   <li>{@code sh}：60 沪主板、68 科创板、51/56/58 场内基金（ETF/LOF）、90 沪 B 股</li>
     *   <li>{@code sz}：00 深主板、30 创业板、12/15/16 深场内基金与债券</li>
     *   <li>{@code bj}：4 / 8 开头的北交所与新三板</li>
     * </ul>
     */
    private static String exchangePrefix(String code) {
        if (code.startsWith("4") || code.startsWith("8")) return "bj";
        String two = code.length() >= 2 ? code.substring(0, 2) : code;
        return switch (two) {
            case "60", "68", "51", "56", "58", "90" -> "sh";
            default -> "sz";
        };
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        // 新浪财经，失败后备用腾讯财经
        return fetchSina(code)
                .thenApply(info -> {
                    if (info != null) return info;
                    throw new RuntimeException("sina returned null");
                })
                .exceptionally(ex -> null)
                .thenCompose(info -> {
                    if (info != null) return CompletableFuture.completedFuture(info);
                    logger.warning("[MineStock] 新浪财经失败，切换腾讯财经: " + code);
                    return fetchTencent(code);
                });
    }

    private CompletableFuture<StockInfo> fetchSina(String code) {
        String prefix = exchangePrefix(code);
        String url = "https://hq.sinajs.cn/list=" + prefix + code;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.sina.com.cn/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                    return parseSina(code, resp.body());
                });
    }

    private StockInfo parseSina(String code, String body) {
        // var hq_str_sz000001="平安银行,10.50,10.49,10.45,10.51,10.40,10.44,10.45,..."
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) throw new RuntimeException("Sina empty response: " + body.substring(0, Math.min(100, body.length())));
        String[] fields = body.substring(start + 1, end).split(",");
        if (fields.length < 7) throw new RuntimeException("Sina fields too few: " + fields.length);
        String name = fields[0];
        double prevClose = parsePrice(fields[2]);
        double price = parsePrice(fields[3]);
        double changeAmount = price - prevClose;
        double changePercent = prevClose != 0 ? changeAmount / prevClose * 100.0 : 0.0;
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    private CompletableFuture<StockInfo> fetchTencent(String code) {
        String prefix = exchangePrefix(code);
        String url = "https://qt.gtimg.cn/q=" + prefix + code;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                    return parseTencent(code, resp.body());
                });
    }

    private StockInfo parseTencent(String code, String body) {
        // v_sz000001="51~平安银行~000001~10.45~10.49~10.50~...~-0.04~-0.38~..."
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) throw new RuntimeException("Tencent empty response");
        String[] fields = body.substring(start + 1, end).split("~");
        if (fields.length < 47) throw new RuntimeException("Tencent fields too few: " + fields.length);
        String name = fields[1];
        double price = parsePrice(fields[3]);
        double prevClose = parsePrice(fields[4]);
        double changeAmount = parseDouble(fields[31]);
        double changePercent = parseDouble(fields[32]);
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    /**
     * 严格解析价格 / 昨收字段：失败或非正数一律抛出，走备用源 / 整次失败。
     *
     * <p>宽松解析会把停牌股、空响应当成「成功且价格 0」，涨跌幅变
     * {@code -100%} 并写入报价缓存，污染详情页、持仓页、告示牌与推荐排序。
     */
    private double parsePrice(String s) {
        double value = parseDouble(s);
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new RuntimeException("Invalid price field: \"" + s + "\"");
        }
        return value;
    }

    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        String prefix = exchangePrefix(code);
        String url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php"
                + "/CN_MarketData.getKLineData?symbol=" + prefix + code
                + "&scale=240&datalen=" + days + "&ma=no";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.sina.com.cn/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200)
                        throw new RuntimeException("Sina KLine HTTP " + resp.statusCode());
                    return parseSinaKLine(resp.body());
                });
    }

    /**
     * Parses Sina historical K-line JSON.
     * Format: [{"day":"2024-01-02","open":"9.50","high":"9.90","low":"9.40","close":"9.80","volume":"..."},...]
     */
    private List<KLinePoint> parseSinaKLine(String body) {
        List<KLinePoint> result = new ArrayList<>();
        String trimmed = body.trim();
        if (!trimmed.startsWith("["))
            throw new RuntimeException("Unexpected Sina KLine response: "
                    + trimmed.substring(0, Math.min(100, trimmed.length())));
        int i = 0;
        while (i < trimmed.length()) {
            int objStart = trimmed.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = trimmed.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = trimmed.substring(objStart, objEnd + 1);
            String date  = extractStr(obj, "day");
            double open  = extractDouble(obj, "open");
            double high  = extractDouble(obj, "high");
            double low   = extractDouble(obj, "low");
            double close = extractDouble(obj, "close");
            if (date != null && close != 0) {
                result.add(new KLinePoint(open, high, low, close, date));
            }
            i = objEnd + 1;
        }
        if (result.isEmpty())
            throw new RuntimeException("Sina KLine empty result: "
                    + trimmed.substring(0, Math.min(200, trimmed.length())));
        return result;
    }

    private String extractStr(String obj, String field) {
        String key = "\"" + field + "\":\"";
        int s = obj.indexOf(key);
        if (s < 0) return null;
        s += key.length();
        int e = obj.indexOf('"', s);
        return e < 0 ? null : obj.substring(s, e);
    }

    private double extractDouble(String obj, String field) {
        String val = extractStr(obj, field);
        return val == null ? 0.0 : parseDouble(val);
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
