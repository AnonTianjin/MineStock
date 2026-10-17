package dev.onelili.mstock.model;

import java.util.Locale;

/** 保证金持仓所属市场，仅用于展示与分类，不参与计算。 */
public enum MarketType {
    FOREX("外汇"),
    COMMODITY("贵金属"),
    CRYPTO("加密货币"),
    INDEX("指数"),
    EQUITY("股票"),
    /**
     * 模拟盘。
     *
     * <p><b>作为最后一个枚举值追加</b>，以免影响任何基于 {@code ordinal()} 的逻辑
     * （仓库内无 {@code ordinal()} 使用）。模拟盘仓位以
     * {@code margin_positions.market = 'SIMULATION'} 落库（该列 {@code VARCHAR(16)}
     * 容得下 10 个字符），若缺此值，{@link #parse} 会把模拟仓位静默回落成
     * {@link #FOREX} —— 于是被 biquote 报价估值并触发错误的强平。
     */
    SIMULATION("模拟");

    private final String label;

    MarketType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 把 biquote 的 {@code type} / {@code exchange} 字段映射到本地枚举，未知类型落到 {@link #FOREX}。
     *
     * <p>两个字段的取值风格不同，都要认：
     * <ul>
     *   <li>{@code type}（{@code /api/symbols}）：单数 {@code Forex} / {@code Commodity} /
     *       {@code Crypto} / {@code Index} / {@code Stock}</li>
     *   <li>{@code exchange}（tick 响应）：大小写与单复数都不统一，如 {@code FOREX} /
     *       {@code crypto} / {@code commodities} / {@code stocks}</li>
     * </ul>
     */
    public static MarketType fromBiquoteType(String type) {
        if (type == null) return FOREX;
        return switch (type.strip().toLowerCase(Locale.ROOT)) {
            case "commodity", "commodities" -> COMMODITY;
            case "crypto", "cryptocurrency", "cryptos" -> CRYPTO;
            case "index", "indices", "indexes" -> INDEX;
            case "stock", "stocks", "equity", "equities" -> EQUITY;
            default -> FOREX;
        };
    }

    public static MarketType parse(String raw) {
        if (raw == null) return FOREX;
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return FOREX;
        }
    }
}
