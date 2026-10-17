package dev.onelili.mstock.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 外汇界面内的板块划分。
 *
 * <p>把"国家外汇"与"虚拟货币"分成两个独立部分：两者虽然都走 biquote 的同一套
 * REST 接口，但品种命名、波动特性与玩家心理预期完全不同，混在一个列表里既难找
 * 也不便于分别统计。
 *
 * <p>板块归属是<b>纯展示层</b>概念：不影响保证金计算、杠杆、强平或期货逻辑。
 */
public enum ForexCategory {

    /** 国家外汇：法定货币对，如 EURUSD、USDJPY。 */
    FIAT("国家外汇", "法定货币对"),

    /** 虚拟货币：加密资产，如 BTCUSD、ETHUSDT。 */
    CRYPTO("虚拟货币", "加密资产");

    private final String label;
    private final String description;

    ForexCategory(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 指令 / 配置中使用的英文标识，稳定不变。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 把品种归入板块。
     *
     * <p><b>判据优先级</b>：biquote 的 {@code type} 元数据是权威来源，符号名启发式
     * <b>只在元数据不足以区分时</b>才生效。
     *
     * <p>这一点很关键：biquote 上有约 186 个股票 CFD 带 {@code USDT} 后缀
     * （{@code MSFTUSDT}、{@code SPYUSDT}、{@code GDXUSDT} …），但它们的
     * {@code type} 是 {@code Stock}。若只看后缀就会把微软、标普 500 ETF 之类
     * 当成虚拟货币。
     *
     * <p>股票 CFD 既不属于国家外汇也不属于虚拟货币，返回 {@code null}；
     * 这类品种仍可在"全部板块"页访问。
     *
     * @return 板块归属；{@code null} 表示不属于这两个板块（股票 CFD）
     */
    public static ForexCategory of(MarketType market, String symbol) {
        if (market == MarketType.CRYPTO) return CRYPTO;
        // 元数据明确是股票 → 不参与外汇 / 虚拟货币的板块划分
        if (market == MarketType.EQUITY) return null;
        // 贵金属 / 能源 / 指数等传统标的：即使带 USDT 后缀（XAUUSDT、CLUSDT），
        // 也属于"传统金融"一侧，直接用元数据定性，不走符号名启发式。
        if (market == MarketType.COMMODITY || market == MarketType.INDEX) return FIAT;
        // 只有外汇（含少数经纪商错标的口径）才需要符号名兜底
        return looksLikeCrypto(symbol) ? CRYPTO : FIAT;
    }

    public static ForexCategory of(MarketSymbol symbol) {
        if (symbol == null) return FIAT;
        return of(symbol.getMarket(), symbol.getName());
    }

    /** 是否属于这两个板块之一（股票 CFD 返回 {@code false}）。 */
    public static boolean isSectionMember(MarketSymbol symbol) {
        return symbol != null && of(symbol) != null;
    }

    /** 展示用板块名；不属于任何板块时给出"股票 CFD"。 */
    public static String labelOf(MarketSymbol symbol) {
        ForexCategory category = of(symbol);
        return category != null ? category.label() : "股票 CFD";
    }

    /**
     * 按符号名判断是否像加密货币。
     *
     * <p>两种模式：
     * <ul>
     *   <li>以 {@code USDT} / {@code USDC} 等稳定币计价结尾，如 {@code BTCUSDT}</li>
     *   <li>形如 {@code BTCUSD}：6~10 位、全大写字母、以已知币种代码开头</li>
     * </ul>
     */
    static boolean looksLikeCrypto(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String value = symbol.strip().toUpperCase(Locale.ROOT);

        // BTCUSDT / ETHUSDC：稳定币计价
        for (String quote : CRYPTO_QUOTE_SUFFIXES) {
            if (value.length() > quote.length() && value.endsWith(quote)) return true;
        }
        // BTCUSD / ETHUSD：直接以 USD 计价
        for (String base : CRYPTO_BASE_CODES) {
            if (value.startsWith(base)) return true;
        }
        // USDT 本身作为货币对（USDTRY 之类不能误判，故仅匹配极短的）
        return false;
    }

    /** 稳定币计价后缀。 */
    private static final List<String> CRYPTO_QUOTE_SUFFIXES =
            List.of("USDT", "USDC", "BUSD", "TUSD", "DAI", "FDUSD");

    /**
     * 常见加密货币代码。
     *
     * <p>刻意避开与法定货币 ISO 代码冲突的三字母组合（如 {@code USD} / {@code EUR} /
     * {@code GBP} / {@code AUD} / {@code CAD} / {@code CHF} / {@code SEK} / {@code NOK}），
     * 否则 {@code USDJPY} 会被误判成加密。
     */
    private static final List<String> CRYPTO_BASE_CODES = List.of(
            "BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "DOT", "LTC", "BCH", "LINK",
            "MATIC", "AVAX", "SHIB", "TRX", "XLM", "ATOM", "ETC", "FIL", "APT", "ARB",
            "OP", "SUI", "TON", "NEAR", "INJ", "PEPE", "WIF", "BONK", "SEI", "TIA",
            "FTM", "RNDR", "IMX", "GRT", "SAND", "MANA", "AXS", "CRV", "ENS", "FLOW",
            "JUP", "CAKE", "AAVE", "UNI", "MKR", "COMP", "SNX", "1INCH", "RUNE", "KAS");

    /**
     * 全部板块，用于 GUI 分类标签与指令补全。
     *
     * <p>返回可变副本，避免调用方误改内部状态。
     */
    public static List<ForexCategory> all() {
        return new ArrayList<>(List.of(values()));
    }

    /**
     * 解析用户输入。
     *
     * <p>接受英文标识（{@code fiat} / {@code crypto}）与中文标签（{@code 国家外汇} /
     * {@code 虚拟货币}），另兼容 {@code forex} / {@code 外汇} 作为"国家外汇"的别名 ——
     * 这是最常用的默认板块，值得给一个更短的写法。
     *
     * @return 匹配到的板块；无法识别时返回 {@code null}（调用方负责报错，不抛异常）
     */
    public static ForexCategory parse(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        return switch (value) {
            case "fiat", "forex", "fx", "国家外汇", "外汇", "法币" -> FIAT;
            case "crypto", "cryptocurrency", "coin", "虚拟货币", "加密货币", "加密", "虚拟币" -> CRYPTO;
            default -> null;
        };
    }
}
