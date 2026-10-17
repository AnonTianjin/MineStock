package dev.onelili.mstock.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外汇板块划分测试：确保国家外汇与虚拟货币被正确分开，且不互相误判。 */
class ForexCategoryTest {

    @Test
    void stockCfdsWithUsdtSuffixAreNotCrypto() {
        // 关键回归：biquote 上有约 186 个股票 CFD 带 USDT 后缀（type=Stock），
        // 例如 MSFTUSDT / SPYUSDT / GDXUSDT。只看后缀会把微软、标普500 ETF
        // 当成虚拟货币。元数据是权威判据。
        assertEquals(null, ForexCategory.of(MarketType.EQUITY, "MSFTUSDT"));
        assertEquals(null, ForexCategory.of(MarketType.EQUITY, "SPYUSDT"));
        assertEquals(null, ForexCategory.of(MarketType.EQUITY, "GDXUSDT"));
        assertEquals(null, ForexCategory.of(MarketType.EQUITY, "RKLBUSDT"));
        assertEquals(null, ForexCategory.of(MarketType.EQUITY, "QQQUSDT"));

        // 它们既不在国家外汇，也不在虚拟货币板块
        MarketSymbol msft = new MarketSymbol("MSFTUSDT", "Microsoft", MarketType.EQUITY,
                "stocks", 2, 0.01, 1, "USD");
        assertEquals(null, ForexCategory.of(msft));
        assertFalse(ForexCategory.isSectionMember(msft));
        assertEquals("股票 CFD", ForexCategory.labelOf(msft));

        // 但真正的加密标的仍然归入虚拟货币
        MarketSymbol btc = new MarketSymbol("BTCUSDT", "BTC / USDT", MarketType.CRYPTO,
                "crypto", 4, 0.0001, 1, "USDT");
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(btc));
    }

    @Test
    void sectionMembershipExcludesStockCfds() {
        MarketSymbol fiat = new MarketSymbol("EURUSD", "Euro vs US Dollar", MarketType.FOREX,
                "FOREX", 5, 0.00001, 1, "USD");
        MarketSymbol crypto = new MarketSymbol("SOLUSDT", "SOL / USDT", MarketType.CRYPTO,
                "crypto", 4, 0.0001, 1, "USDT");
        MarketSymbol stock = new MarketSymbol("NFLXUSDT", "Netflix", MarketType.EQUITY,
                "stocks", 2, 0.01, 1, "USD");

        assertTrue(ForexCategory.isSectionMember(fiat));
        assertTrue(ForexCategory.isSectionMember(crypto));
        assertFalse(ForexCategory.isSectionMember(stock));
        assertFalse(ForexCategory.isSectionMember(null));

        assertEquals("国家外汇", ForexCategory.labelOf(fiat));
        assertEquals("虚拟货币", ForexCategory.labelOf(crypto));
    }

    @Test
    void biquoteCryptoTypeAlwaysGoesToCrypto() {
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.CRYPTO, "ANYTHING"));
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.CRYPTO, "BTCXAU"));
    }

    @Test
    void commoditiesWithUsdtSuffixStayOnTheTraditionalSide() {
        // 关键回归：XAUUSDT / CLUSDT / COPPERUSDT 等大宗商品也带 USDT 后缀，
        // 但它们是贵金属与能源，不是加密资产。元数据是权威判据。
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.COMMODITY, "XAUUSDT"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.COMMODITY, "XAGUSDT"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.COMMODITY, "CLUSDT"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.COMMODITY, "COPPERUSDT"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.COMMODITY, "XPDUSDT"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.INDEX, "US500"));
    }

    @Test
    void stablecoinQuotedPairsAreCryptoEvenWhenMetadataSaysOtherwise() {
        // 只有"外汇"口径才走符号名兜底：少数经纪商把币对标成 Forex。
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.FOREX, "ETHUSDC"));
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.FOREX, "SOLBUSD"));
    }

    @Test
    void knownCryptoCodesWithUsdQuoteAreCrypto() {
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.FOREX, "BTCUSD"));
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.FOREX, "ETHUSD"));
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.FOREX, "XRPUSD"));
        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(MarketType.FOREX, "DOGEUSD"));
    }

    @Test
    void fiatPairsAreNeverMistakenForCrypto() {
        // 关键回归：USD/EUR/GBP/AUD/CAD/CHF 这些 ISO 代码不能被当成币种前缀。
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "EURUSD"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "USDJPY"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "GBPUSD"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "AUDCAD"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "USDCHF"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "USDTRY"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "USDSGD"));
    }

    @Test
    void metalsAndIndicesBelongToTheTraditionalSide() {
        // 贵金属与指数属于"传统金融"，与国家外汇同板块，不与加密混淆。
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.COMMODITY, "XAUUSD"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.COMMODITY, "XAGUSD"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.INDEX, "US500"));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.INDEX, "DE30"));
    }

    @Test
    void nullAndBlankSymbolsFallBackToFiat() {
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, null));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(MarketType.FOREX, "   "));
        assertEquals(ForexCategory.FIAT, ForexCategory.of((MarketSymbol) null));
    }

    @Test
    void categoryIsDerivedFromMarketSymbolMetadata() {
        MarketSymbol crypto = new MarketSymbol("BTCUSDT", "BTC / USDT", MarketType.CRYPTO,
                "crypto", 4, 0.0001, 1, "USDT");
        MarketSymbol fiat = new MarketSymbol("EURUSD", "Euro vs US Dollar", MarketType.FOREX,
                "FOREX", 5, 0.00001, 1, "USD");

        assertEquals(ForexCategory.CRYPTO, ForexCategory.of(crypto));
        assertEquals(ForexCategory.FIAT, ForexCategory.of(fiat));
    }

    @Test
    void parsesBothEnglishIdsAndChineseLabels() {
        assertEquals(ForexCategory.FIAT, ForexCategory.parse("fiat"));
        assertEquals(ForexCategory.FIAT, ForexCategory.parse("FOREX"));
        assertEquals(ForexCategory.FIAT, ForexCategory.parse("国家外汇"));
        assertEquals(ForexCategory.FIAT, ForexCategory.parse(" 外汇 "));

        assertEquals(ForexCategory.CRYPTO, ForexCategory.parse("crypto"));
        assertEquals(ForexCategory.CRYPTO, ForexCategory.parse("虚拟货币"));
        assertEquals(ForexCategory.CRYPTO, ForexCategory.parse("加密货币"));

        assertNull(ForexCategory.parse("nonsense"));
        assertNull(ForexCategory.parse(null));
        assertNull(ForexCategory.parse("  "));
    }

    @Test
    void exposesStableIdsAndBothCategories() {
        assertEquals("fiat", ForexCategory.FIAT.id());
        assertEquals("crypto", ForexCategory.CRYPTO.id());
        assertEquals(2, ForexCategory.all().size());
        assertTrue(ForexCategory.all().contains(ForexCategory.FIAT));
        assertTrue(ForexCategory.all().contains(ForexCategory.CRYPTO));
    }

    @Test
    void symbolNameMatchingIsCaseInsensitive() {
        assertTrue(ForexCategory.looksLikeCrypto("btcusdt"));
        assertTrue(ForexCategory.looksLikeCrypto("EthUsd"));
        assertFalse(ForexCategory.looksLikeCrypto("eurusd"));
    }
}
