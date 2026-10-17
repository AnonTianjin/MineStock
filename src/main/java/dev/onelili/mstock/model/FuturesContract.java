package dev.onelili.mstock.model;

/**
 * 期货合约定义。
 *
 * <p>{@code initialMarginRate} 与杠杆互为倒数：{@code 0.1} 即 10 倍杠杆。
 */
public class FuturesContract {

    private final String symbol;
    /** ISO-8601 日期，形如 {@code 2026-12-31}。 */
    private final String expiryDate;
    private final double contractSize;
    private final double tickSize;
    private final double initialMarginRate;

    public FuturesContract(String symbol, String expiryDate,
                           double contractSize, double tickSize, double initialMarginRate) {
        this.symbol = symbol;
        this.expiryDate = expiryDate;
        this.contractSize = contractSize > 0 ? contractSize : 1.0;
        this.tickSize = tickSize > 0 ? tickSize : 0.01;
        this.initialMarginRate = initialMarginRate > 0 && initialMarginRate <= 1
                ? initialMarginRate : 0.1;
    }

    public String getSymbol() { return symbol; }
    public String getExpiryDate() { return expiryDate; }
    public double getContractSize() { return contractSize; }
    public double getTickSize() { return tickSize; }
    public double getInitialMarginRate() { return initialMarginRate; }

    /** 由保证金率推算的杠杆倍数。 */
    public int getLeverage() {
        return Math.max(1, (int) Math.round(1.0 / initialMarginRate));
    }

    public boolean isExpired(java.time.LocalDate today) {
        java.time.LocalDate expiry = parseExpiry();
        return expiry == null || !expiry.isAfter(today);
    }

    public java.time.LocalDate parseExpiry() {
        if (expiryDate == null || expiryDate.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(expiryDate.strip());
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    @Override
    public String toString() {
        return symbol + "@" + expiryDate + " x" + contractSize + " tick=" + tickSize
                + " imr=" + initialMarginRate;
    }
}
