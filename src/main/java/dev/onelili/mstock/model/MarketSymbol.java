package dev.onelili.mstock.model;

/** biquote 品种元数据（来自 {@code /api/symbols}）。 */
public class MarketSymbol {

    private final String name;
    private final String description;
    private final MarketType market;
    private final String exchange;
    private final int digits;
    private final double tickSize;
    private final double contractSize;
    private final String currency;

    public MarketSymbol(String name, String description, MarketType market, String exchange,
                        int digits, double tickSize, double contractSize, String currency) {
        this.name = name;
        this.description = description;
        this.market = market;
        this.exchange = exchange;
        this.digits = digits;
        this.tickSize = tickSize;
        this.contractSize = contractSize;
        this.currency = currency;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public MarketType getMarket() { return market; }
    public String getExchange() { return exchange; }
    public int getDigits() { return digits; }
    public double getTickSize() { return tickSize; }
    public double getContractSize() { return contractSize; }
    public String getCurrency() { return currency; }

    @Override
    public String toString() {
        return name + " (" + description + ")";
    }
}
