package dev.onelili.mstock.stockio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class HkStockApi implements StockSource {

    private static final Pattern HK_CODE = Pattern.compile("^\\d{1,5}$");
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset GBK = Charset.forName("GBK");
    private final HttpClient http;
    private final Logger logger;

    public HkStockApi(Logger logger) {
        this.logger = logger;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    private String padCode(String code) {
        return String.format("%05d", Integer.parseInt(code));
    }

    @Override
    public boolean supports(String code) {
        return HK_CODE.matcher(code).matches();
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        return fetchSina(code)
                .thenApply(info -> {
                    if (info != null) return info;
                    throw new RuntimeException("sina hk returned null");
                })
                .exceptionally(ex -> null)
                .thenCompose(info -> {
                    if (info != null) return CompletableFuture.completedFuture(info);
                    logger.warning("[MineStock] 新浪港股失败，切换腾讯财经: " + code);
                    return fetchTencent(code);
                });
    }

    private CompletableFuture<StockInfo> fetchSina(String code) {
        String padded = padCode(code);
        String url = "https://hq.sinajs.cn/list=hk" + padded;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.sina.com.cn/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(GB18030))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                    return parseSina(code, resp.body());
                });
    }

    static StockInfo parseSina(String code, String body) {
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start)
            throw new RuntimeException("Sina HK empty response: " + body.substring(0, Math.min(100, body.length())));
        String[] fields = body.substring(start + 1, end).split(",");
        if (fields.length < 9) throw new RuntimeException("Sina HK fields too few: " + fields.length);
        String name = fields[1];
        double prevClose = parseDouble(fields[3]);
        double price = parseDouble(fields[6]);
        double changeAmount = price - prevClose;
        double changePercent = prevClose != 0 ? changeAmount / prevClose * 100.0 : 0.0;
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    private CompletableFuture<StockInfo> fetchTencent(String code) {
        String padded = padCode(code);
        String url = "https://qt.gtimg.cn/q=hk" + padded;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(GBK))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                    return parseTencent(code, resp.body());
                });
    }

    static StockInfo parseTencent(String code, String body) {
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) throw new RuntimeException("Tencent HK empty response");
        String[] fields = body.substring(start + 1, end).split("~");
        if (fields.length < 47) throw new RuntimeException("Tencent HK fields too few: " + fields.length);
        String name = fields[1];
        double price = parseDouble(fields[3]);
        double prevClose = parseDouble(fields[4]);
        double changeAmount = parseDouble(fields[31]);
        double changePercent = parseDouble(fields[32]);
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        String padded = padCode(code);
        String symbol = "hk" + padded;
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
                + symbol + ",day,,," + days + ",qfq";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200)
                        throw new RuntimeException("Tencent HK KLine HTTP " + resp.statusCode());
                    return parseTencentKLine(resp.body(), symbol);
                });
    }

    static List<KLinePoint> parseTencentKLine(String body, String symbol) {
        List<KLinePoint> result = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        // 逐级判空：错误响应可能缺 code / msg / data 任一字段，
        // 裸 get(...).getAsInt() 会 NPE，把一次普通的行情失败变成解析异常。
        JsonElement codeField = root.get("code");
        if (codeField == null || codeField.getAsInt() != 0) {
            JsonElement msg = root.get("msg");
            throw new RuntimeException("Tencent HK KLine error: "
                    + (msg != null && !msg.isJsonNull() ? msg.getAsString() : body));
        }

        JsonObject data = root.getAsJsonObject("data");
        JsonObject stockData = data == null ? null : data.getAsJsonObject(symbol);
        if (stockData == null) {
            throw new RuntimeException("Tencent HK KLine missing symbol: " + symbol);
        }
        JsonArray rows = stockData.has("qfqday")
                ? stockData.getAsJsonArray("qfqday")
                : stockData.getAsJsonArray("day");
        if (rows == null) {
            throw new RuntimeException("Tencent HK KLine missing daily data: " + symbol);
        }
        for (JsonElement element : rows) {
            JsonArray row = element.getAsJsonArray();
            if (row.size() < 5) continue;
            String date = row.get(0).getAsString();
            double open = parseDouble(row.get(1).getAsString());
            double close = parseDouble(row.get(2).getAsString());
            double high = parseDouble(row.get(3).getAsString());
            double low = parseDouble(row.get(4).getAsString());
            if (close != 0) result.add(new KLinePoint(open, high, low, close, date));
        }
        if (result.isEmpty()) {
            throw new RuntimeException("Tencent HK KLine empty result: " + symbol);
        }
        return result;
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
