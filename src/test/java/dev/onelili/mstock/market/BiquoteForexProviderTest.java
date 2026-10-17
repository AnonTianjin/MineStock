package dev.onelili.mstock.market;

import dev.onelili.mstock.model.ForexQuote;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.MarketSymbol;
import dev.onelili.mstock.model.MarketType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * biquote 响应解析测试。
 *
 * <p>测试数据取自 biquote 的真实响应结构（2026-10 实测）。
 */
class BiquoteForexProviderTest {

    @Test
    void usesMidRatherThanLastWhichIsAlwaysZero() {
        // last 恒为 0，必须取 mid。
        String body = """
                {"symbol":"EURUSD","description":"Euro vs US Dollar",
                 "bid":1.12380,"ask":1.12386,"last":0.00000,"volume":0,
                 "mid":1.12383,"high":1.12546,"low":1.12330,
                 "dayDiffPercent":-0.1289,"marketState":"open","stale":false,
                 "quoteAgeSeconds":4}
                """;

        ForexQuote quote = BiquoteForexProvider.parseTick("EURUSD", body);

        assertEquals(1.12383, quote.getMid(), 1e-9);
        assertEquals("EURUSD", quote.getSymbol());
        assertEquals("Euro vs US Dollar", quote.getDescription());
        assertEquals(-0.1289, quote.getDayDiffPercent(), 1e-9);
        assertFalse(quote.isMarketClosed());
    }

    @Test
    void rejectsTickWithoutUsableMid() {
        String body = "{\"symbol\":\"EURUSD\",\"last\":0.0,\"volume\":0,\"mid\":0}";

        assertThrows(IllegalStateException.class,
                () -> BiquoteForexProvider.parseTick("EURUSD", body));
    }

    @Test
    void flagsClosedMarketSoDayChangeIsHidden() {
        String body = "{\"symbol\":\"EURUSD\",\"mid\":1.1,\"marketState\":\"closed\","
                + "\"stale\":true,\"dayDiffPercent\":0.5}";

        ForexQuote quote = BiquoteForexProvider.parseTick("EURUSD", body);

        assertTrue(quote.isMarketClosed());
    }

    @Test
    void reversesNewestFirstBarsIntoChronologicalOrder() {
        // 实测：bars 的 openTime 是递减的（最新在前）。
        String body = """
                {"symbol":"EURUSD","interval":"1h","bars":[
                  {"openTime":"2026-10-07T03:00:00Z","open":1.12359,"high":1.12389,
                   "low":1.12349,"close":1.12381,"volume":0,"tickVolume":679,"isOpen":true},
                  {"openTime":"2026-10-07T02:00:00Z","open":1.12389,"high":1.12394,
                   "low":1.12333,"close":1.12358,"volume":0,"tickVolume":1716,"isOpen":false},
                  {"openTime":"2026-10-07T01:00:00Z","open":1.12488,"high":1.12488,
                   "low":1.12385,"close":1.12390,"volume":0,"tickVolume":1918,"isOpen":false}
                ]}
                """;

        List<KLinePoint> bars = BiquoteForexProvider.parseOhlcResponse(body, 10);

        assertEquals(3, bars.size());
        // 最早在前
        assertEquals("2026-10-07 01:00", bars.get(0).getDate());
        assertEquals(1.12390, bars.get(0).getClose(), 1e-9);
        assertEquals("2026-10-07 03:00", bars.get(2).getDate());
        assertEquals(1.12381, bars.get(2).getClose(), 1e-9);
    }

    @Test
    void keepsOnlyTheLastLimitBarsAfterReordering() {
        String body = """
                {"symbol":"EURUSD","interval":"1h","bars":[
                  {"openTime":"2026-10-07T03:00:00Z","open":3,"high":3,"low":3,"close":3},
                  {"openTime":"2026-10-07T02:00:00Z","open":2,"high":2,"low":2,"close":2},
                  {"openTime":"2026-10-07T01:00:00Z","open":1,"high":1,"low":1,"close":1}
                ]}
                """;

        List<KLinePoint> bars = BiquoteForexProvider.parseOhlcResponse(body, 2);

        assertEquals(2, bars.size());
        assertEquals(2.0, bars.get(0).getClose(), 1e-9);
        assertEquals(3.0, bars.get(1).getClose(), 1e-9);
    }

    @Test
    void rejectsOhlcEnvelopeWithoutBarsArray() {
        assertThrows(IllegalStateException.class,
                () -> BiquoteForexProvider.parseOhlcResponse("{\"symbol\":\"EURUSD\"}", 10));
    }

    @Test
    void parsesSymbolMetadataIncludingTickAndContractSize() {
        String body = """
                [{"name":"EURUSD","description":"Euro vs US Dollar","exchange":"FOREX",
                  "type":"Forex","digits":5,"tickSize":0.00001,"tickValue":0.00001,
                  "contractSize":1,"currency":"USD","isActive":true,"hasData":true},
                 {"name":"XAUUSDT","description":"Gold","exchange":"commodities",
                  "type":"Commodity","digits":2,"tickSize":0.01,"tickValue":0.01,
                  "contractSize":1,"currency":"USD"}]
                """;

        List<MarketSymbol> symbols = BiquoteForexProvider.parseSymbols(body);

        assertEquals(2, symbols.size());
        assertEquals("EURUSD", symbols.get(0).getName());
        assertEquals(MarketType.FOREX, symbols.get(0).getMarket());
        assertEquals(0.00001, symbols.get(0).getTickSize(), 1e-12);
        assertEquals(MarketType.COMMODITY, symbols.get(1).getMarket());
    }

    @Test
    void supportsOnlyDocumentedIntervals() {
        assertTrue(ForexProvider.isSupportedInterval("1h"));
        assertTrue(ForexProvider.isSupportedInterval("1D"));
        assertFalse(ForexProvider.isSupportedInterval("2h"));
        assertFalse(ForexProvider.isSupportedInterval(null));
    }

    // ── 批量报价（/api/latest）────────────────────────────────────────────────

    @Test
    void parsesBatchLatestResponseIntoPerSymbolQuotes() {
        // 实测结构：顶层以品种代码为键，值为单个 tick 对象。
        // 注意 tick 响应没有 `type`，只有 `exchange`（大小写/单复数都不统一）。
        String body = """
                {"EURUSD":{"symbol":"EURUSD","bid":1.12331,"ask":1.12337,"last":0.00000,
                  "volume":0,"mid":1.12334,"high":1.12546,"low":1.12279,
                  "dayDiffPercent":-0.1724,"description":"Euro vs US Dollar",
                  "exchange":"FOREX","marketState":"open","stale":false,"quoteAgeSeconds":4},
                 "UNIUSDT":{"symbol":"UNIUSDT","bid":8.11000000,"ask":8.11100000,
                  "last":8.11000000,"volume":0,"mid":8.11050000,"high":8.94600000,
                  "low":8.06000000,"dayDiffPercent":-8.537,"description":"UNI / USDT",
                  "exchange":"crypto","marketState":"open","stale":false,"quoteAgeSeconds":0}}
                """;

        Map<String, ForexQuote> quotes = BiquoteForexProvider.parseTicks(
                List.of("EURUSD", "UNIUSDT"), body);

        assertEquals(2, quotes.size());
        assertEquals(1.12334, quotes.get("EURUSD").getMid(), 1e-9);
        assertEquals(-0.1724, quotes.get("EURUSD").getDayDiffPercent(), 1e-9);
        assertEquals(8.1105, quotes.get("UNIUSDT").getMid(), 1e-9);
        // 加密标的的 last 非 0，但价格仍必须取 mid
        assertEquals(MarketType.CRYPTO, quotes.get("UNIUSDT").getMarket());
        assertEquals(MarketType.FOREX, quotes.get("EURUSD").getMarket());
    }

    @Test
    void infersMarketTypeFromExchangeWhenTypeIsAbsent() {
        // 关键回归：tick 响应没有 type 字段。若只读 type，所有品种（含加密）
        // 都会被判成 FOREX，板块划分随之失效。
        assertEquals(MarketType.CRYPTO, BiquoteForexProvider.resolveMarketType(
                com.google.gson.JsonParser.parseString("{\"exchange\":\"crypto\"}").getAsJsonObject()));
        assertEquals(MarketType.COMMODITY, BiquoteForexProvider.resolveMarketType(
                com.google.gson.JsonParser.parseString("{\"exchange\":\"commodities\"}").getAsJsonObject()));
        assertEquals(MarketType.EQUITY, BiquoteForexProvider.resolveMarketType(
                com.google.gson.JsonParser.parseString("{\"exchange\":\"stocks\"}").getAsJsonObject()));
        assertEquals(MarketType.FOREX, BiquoteForexProvider.resolveMarketType(
                com.google.gson.JsonParser.parseString("{\"exchange\":\"FOREX\"}").getAsJsonObject()));

        // type 存在时优先（/api/symbols 的写法）
        assertEquals(MarketType.CRYPTO, BiquoteForexProvider.resolveMarketType(
                com.google.gson.JsonParser.parseString(
                        "{\"type\":\"Crypto\",\"exchange\":\"FOREX\"}").getAsJsonObject()));

        // 两者都缺 → 保守落到 FOREX
        assertEquals(MarketType.FOREX, BiquoteForexProvider.resolveMarketType(
                com.google.gson.JsonParser.parseString("{}").getAsJsonObject()));
    }

    @Test
    void cryptoQuoteIsClassifiedAsCryptoSection() {
        // 端到端串起来：批量报价 → ForexCategory 板块划分
        String body = """
                {"UNIUSDT":{"symbol":"UNIUSDT","mid":8.1105,"exchange":"crypto",
                  "description":"UNI / USDT","marketState":"open"}}
                """;
        ForexQuote quote = BiquoteForexProvider.parseTicks(List.of("UNIUSDT"), body).get("UNIUSDT");

        assertEquals(dev.onelili.mstock.model.ForexCategory.CRYPTO,
                dev.onelili.mstock.model.ForexCategory.of(quote.getMarket(), quote.getSymbol()));
    }

    @Test
    void batchSkipsMissingSymbolsInsteadOfFailingTheWholeBatch() {
        // 冷门品种拿不到报价是常态，不能因此让整页行情变空。
        String body = """
                {"EURUSD":{"symbol":"EURUSD","mid":1.1,"marketState":"open"},
                 "NOSUCHPAIR":{"symbol":"NOSUCHPAIR","last":0,"volume":0,"mid":0}}
                """;

        Map<String, ForexQuote> quotes = BiquoteForexProvider.parseTicks(
                List.of("EURUSD", "NOSUCHPAIR", "ABSENT"), body);

        assertEquals(1, quotes.size());
        assertTrue(quotes.containsKey("EURUSD"));
        // mid 为 0 的品种被跳过，而不是以 0 价进入结果
        assertFalse(quotes.containsKey("NOSUCHPAIR"));
        assertNull(quotes.get("ABSENT"));
    }

    @Test
    void batchRejectsNonObjectEnvelope() {
        assertThrows(IllegalStateException.class,
                () -> BiquoteForexProvider.parseTicks(List.of("EURUSD"), "[1,2,3]"));
    }

    // ── 重试策略（GOAWAY 修复）──────────────────────────────────────────────

    @Test
    void retriesConnectionLevelFailuresSuchAsGoaway() {
        // GOAWAY 以 IOException 形式抛出，属于可恢复的连接级失败，必须重试。
        assertTrue(BiquoteForexProvider.isRetryable(
                new IOException("/172.18.0.63:51254: GOAWAY received")));
        assertTrue(BiquoteForexProvider.isRetryable(new IOException("Connection reset by peer")));
        assertTrue(BiquoteForexProvider.isRetryable(
                new java.net.http.HttpTimeoutException("request timed out")));
    }

    @Test
    void retriesServerErrorsButNotClientErrors() {
        // 5xx 是服务端临时故障 → 重试
        assertTrue(BiquoteForexProvider.isRetryable(
                new BiquoteForexProvider.BiquoteHttpException(503, "store unreachable")));
        assertTrue(BiquoteForexProvider.isRetryable(
                new BiquoteForexProvider.BiquoteHttpException(500, "server error")));

        // 429 限流重试只会加重限流；404 是"确实没有这个品种" → 都不重试
        assertFalse(BiquoteForexProvider.isRetryable(
                new BiquoteForexProvider.BiquoteHttpException(429, "rate limited")));
        assertFalse(BiquoteForexProvider.isRetryable(
                new BiquoteForexProvider.BiquoteHttpException(404, "no tick data")));

        assertFalse(BiquoteForexProvider.isRetryable(null));
        assertFalse(BiquoteForexProvider.isRetryable(new IllegalStateException("closed")));
    }
}
