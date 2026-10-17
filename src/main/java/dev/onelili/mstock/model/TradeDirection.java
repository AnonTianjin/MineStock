package dev.onelili.mstock.model;

/** 仓位方向。{@code sign} 为 +1（多头）或 -1（空头），用于统一盈亏与强平价公式。 */
public enum TradeDirection {
    LONG(1),
    SHORT(-1);

    private final int sign;

    TradeDirection(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }

    public TradeDirection opposite() {
        return this == LONG ? SHORT : LONG;
    }

    /** 解析用户输入，失败返回 {@code null}（调用方负责报错，不抛异常）。 */
    public static TradeDirection parse(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "long", "l", "多", "买多", "开多" -> LONG;
            case "short", "s", "空", "卖空", "开空" -> SHORT;
            default -> null;
        };
    }
}
