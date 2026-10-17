package dev.onelili.mstock.ui;

public class PendingAction {
    /**
     * BUY/SELL 为原股票交易流程；FOREX_* 与 FUTURES_* 由 GUI 的数量输入复用同一通道；
     * SYMBOL_SEARCH 为文本输入（品种模糊搜索关键词）。
     */
    public enum Type { BUY, SELL, FOREX_BUY, FOREX_SELL, FUTURES_OPEN, SYMBOL_SEARCH }

    private final Type type;
    private final String stockCode;
    private final long timestamp;
    // Set after the player enters an amount; used during the confirm step.
    private volatile int amount = 0;
    // 数量输入允许小数（外汇手数 / 加密币数量 / 期货手数）。
    private volatile double decimalAmount = 0.0;
    // 文本输入（品种搜索关键词）。
    private volatile String textInput = "";

    public PendingAction(Type type, String stockCode) {
        this.type = type;
        this.stockCode = stockCode;
        this.timestamp = System.currentTimeMillis();
    }

    public Type getType() { return type; }
    public String getStockCode() { return stockCode; }
    public long getTimestamp() { return timestamp; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public double getDecimalAmount() { return decimalAmount; }
    public void setDecimalAmount(double decimalAmount) { this.decimalAmount = decimalAmount; }

    public String getTextInput() { return textInput; }
    public void setTextInput(String textInput) { this.textInput = textInput == null ? "" : textInput; }

    /** 是否是股票买卖流程（需要走确认页）。 */
    public boolean isStockTrade() { return type == Type.BUY || type == Type.SELL; }

    /** 是否是纯文本输入（品种搜索）。 */
    public boolean isTextInput() { return type == Type.SYMBOL_SEARCH; }

    /** A pending action is in the confirm stage once an amount has been set. */
    public boolean isAwaitingConfirm() { return amount > 0; }

    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - timestamp > timeoutMillis;
    }
}
