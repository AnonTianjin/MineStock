package dev.onelili.mstock.leverage;

/**
 * 交易操作的统一返回值。
 *
 * <p>Service 层不直接发消息：它返回一个语言键与占位符，由指令或 GUI 决定怎么呈现。
 * 这样"指令与 GUI 调用同一 Service 方法"时，两边提示完全一致。
 */
public record TradeOutcome(boolean success, String langKey, String[] placeholders) {

    public static TradeOutcome ok(String langKey, String... placeholders) {
        return new TradeOutcome(true, langKey, placeholders);
    }

    public static TradeOutcome fail(String langKey, String... placeholders) {
        return new TradeOutcome(false, langKey, placeholders);
    }

    public boolean failed() {
        return !success;
    }
}
