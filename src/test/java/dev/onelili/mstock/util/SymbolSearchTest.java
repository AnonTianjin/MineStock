package dev.onelili.mstock.util;

import dev.onelili.mstock.model.MarketSymbol;
import dev.onelili.mstock.model.MarketType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 品种模糊搜索测试。 */
class SymbolSearchTest {

    private static final List<MarketSymbol> SYMBOLS = List.of(
            sym("EURUSD", "Euro vs US Dollar", MarketType.FOREX),
            sym("EURJPY", "Euro vs Japanese Yen", MarketType.FOREX),
            sym("EURNOK", "Euro vs Norwegian Krona", MarketType.FOREX),
            sym("GBPUSD", "British Pound vs US Dollar", MarketType.FOREX),
            sym("USDJPY", "US Dollar vs Japanese Yen", MarketType.FOREX),
            sym("XAUUSD", "Gold vs US Dollar", MarketType.COMMODITY),
            sym("BTCUSD", "Bitcoin vs US Dollar", MarketType.CRYPTO),
            sym("BTCUSDT", "BTC / USDT", MarketType.CRYPTO),
            sym("ETHUSDT", "ETH / USDT", MarketType.CRYPTO),
            sym("SOLUSDT", "SOL / USDT", MarketType.CRYPTO));

    private static MarketSymbol sym(String name, String description, MarketType market) {
        return new MarketSymbol(name, description, market, "TEST", 5, 0.00001, 1, "USD");
    }

    private static List<String> names(List<MarketSymbol> symbols) {
        List<String> result = new ArrayList<>(symbols.size());
        for (MarketSymbol symbol : symbols) result.add(symbol.getName());
        return result;
    }

    @Test
    void blankKeywordReturnsEverythingUnchanged() {
        assertEquals(SYMBOLS.size(), SymbolSearch.filter(SYMBOLS, null, 0).size());
        assertEquals(SYMBOLS.size(), SymbolSearch.filter(SYMBOLS, "", 0).size());
        assertEquals(SYMBOLS.size(), SymbolSearch.filter(SYMBOLS, "   ", 0).size());
        // 顺序也要保持原样
        assertEquals(names(SYMBOLS), names(SymbolSearch.filter(SYMBOLS, "  ", 0)));
    }

    @Test
    void exactCodeWinsOverPrefixAndContains() {
        List<MarketSymbol> result = SymbolSearch.filter(SYMBOLS, "EURUSD", 0);

        assertFalse(result.isEmpty());
        assertEquals("EURUSD", result.get(0).getName());
    }

    @Test
    void codePrefixMatchesRankAboveDescriptionMatches() {
        List<MarketSymbol> result = SymbolSearch.filter(SYMBOLS, "EUR", 0);
        List<String> found = names(result);

        assertTrue(found.contains("EURUSD"));
        assertTrue(found.contains("EURJPY"));
        assertTrue(found.contains("EURNOK"));
        // "Euro vs ..." 的描述命中不能压过代码前缀命中
        assertEquals("EURJPY", found.get(0));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals(names(SymbolSearch.filter(SYMBOLS, "eurusd", 0)),
                names(SymbolSearch.filter(SYMBOLS, "EURUSD", 0)));
        assertEquals(names(SymbolSearch.filter(SYMBOLS, "BtcUsd", 0)),
                names(SymbolSearch.filter(SYMBOLS, "btcusd", 0)));
    }

    @Test
    void subsequenceFuzzyMatchWorks() {
        // EUU 不是任何代码的子串，但字符按顺序出现在 EURUSD 中
        List<String> found = names(SymbolSearch.filter(SYMBOLS, "EUU", 0));
        assertTrue(found.contains("EURUSD"), "子序列应命中 EURUSD: " + found);

        // BTD 命中 BTCUSD / BTCUSDT 这类
        List<String> btc = names(SymbolSearch.filter(SYMBOLS, "BTD", 0));
        assertTrue(btc.contains("BTCUSD"), "子序列应命中 BTCUSD: " + btc);
    }

    @Test
    void matchesDescriptionText() {
        // 中文/英文描述命中
        List<String> bitcoin = names(SymbolSearch.filter(SYMBOLS, "bitcoin", 0));
        assertTrue(bitcoin.contains("BTCUSD"), "应按描述命中: " + bitcoin);

        List<String> euro = names(SymbolSearch.filter(SYMBOLS, "euro", 0));
        assertTrue(euro.contains("EURUSD"));
        assertTrue(euro.contains("EURJPY"));
    }

    @Test
    void multiTokenQueryRequiresEveryToken() {
        // 两个词都要命中：只有 EURJPY 同时含 euro 与 yen
        List<String> found = names(SymbolSearch.filter(SYMBOLS, "euro yen", 0));
        assertTrue(found.contains("EURJPY"), "多词查询应命中 EURJPY: " + found);
        assertFalse(found.contains("EURUSD"), "缺一个词不应命中: " + found);
    }

    @Test
    void respectsLimit() {
        List<MarketSymbol> result = SymbolSearch.filter(SYMBOLS, "USD", 3);
        assertEquals(3, result.size());

        // limit <= 0 表示不限制
        assertTrue(SymbolSearch.filter(SYMBOLS, "USD", 0).size() > 3);
        assertTrue(SymbolSearch.filter(SYMBOLS, "USD", -1).size() > 3);
    }

    @Test
    void limitAlsoAppliesWhenKeywordIsBlank() {
        List<MarketSymbol> result = SymbolSearch.filter(SYMBOLS, "", 2);
        assertEquals(2, result.size());
        assertEquals("EURUSD", result.get(0).getName());
    }

    @Test
    void noMatchReturnsEmptyList() {
        assertTrue(SymbolSearch.filter(SYMBOLS, "ZZZZZZZ", 0).isEmpty());
        assertFalse(SymbolSearch.matches(SYMBOLS.get(0), "ZZZZZZZ"));
        assertTrue(SymbolSearch.matches(SYMBOLS.get(0), "EUR"));
    }

    @Test
    void emptyOrNullSourceIsSafe() {
        assertTrue(SymbolSearch.filter(List.of(), "EUR", 0).isEmpty());
        assertTrue(SymbolSearch.filter(null, "EUR", 0).isEmpty());
    }

    @Test
    void resultsAreDeterministicForTies() {
        // 同档次内按代码字典序，保证结果稳定可复现
        List<String> first = names(SymbolSearch.filter(SYMBOLS, "USDT", 0));
        List<String> second = names(SymbolSearch.filter(SYMBOLS, "USDT", 0));
        assertEquals(first, second);
        assertEquals(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"), first);
    }

    @Test
    void subsequenceHelperRejectsOutOfOrderCharacters() {
        assertTrue(SymbolSearch.isSubsequence("EUU", "EURUSD"));
        assertTrue(SymbolSearch.isSubsequence("USD", "USDT"));
        // 顺序颠倒不匹配
        assertFalse(SymbolSearch.isSubsequence("DSU", "USD"));
        assertFalse(SymbolSearch.isSubsequence("", "USD"));
    }
}
