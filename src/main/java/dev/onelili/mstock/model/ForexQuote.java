package dev.onelili.mstock.model;

/**
 * biquote tick 报价。
 *
 * <p>价格一律取 {@code mid}：外汇 / CFD 数据源的 {@code last} 与 {@code volume} 恒为 0。
 */
public class ForexQuote {

    private final String symbol;
    private final String description;
    private final double mid;
    private final double bid;
    private final double ask;
    private final double high;
    private final double low;
    private final double dayDiffPercent;
    private final String marketState;
    private final boolean stale;
    private final long quoteAgeSeconds;
    private final MarketType market;

    public ForexQuote(String symbol, String description, double mid, double bid, double ask,
                      double high, double low, double dayDiffPercent,
                      String marketState, boolean stale, long quoteAgeSeconds,
                      MarketType market) {
        this.symbol = symbol;
        this.description = description;
        this.mid = mid;
        this.bid = bid;
        this.ask = ask;
        this.high = high;
        this.low = low;
        this.dayDiffPercent = dayDiffPercent;
        this.marketState = marketState;
        this.stale = stale;
        this.quoteAgeSeconds = quoteAgeSeconds;
        this.market = market;
    }

    public String getSymbol() { return symbol; }
    public String getDescription() { return description; }
    public double getMid() { return mid; }
    public double getBid() { return bid; }
    public double getAsk() { return ask; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getDayDiffPercent() { return dayDiffPercent; }
    public String getMarketState() { return marketState; }
    public boolean isStale() { return stale; }
    public long getQuoteAgeSeconds() { return quoteAgeSeconds; }
    public MarketType getMarket() { return market; }

    /** 休市 / 报价过期时 UI 应显示"休市·最后价"而非日内涨跌。 */
    public boolean isMarketClosed() {
        return "closed".equalsIgnoreCase(marketState) || stale;
    }
}
