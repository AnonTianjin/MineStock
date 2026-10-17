package dev.onelili.mstock.model;

import java.util.UUID;

/** 期货持仓。盯市盈亏累计在 {@code realizedPnl}，未结算部分由 {@code lastSettlePrice} 推导。 */
public class FuturesPosition {

    private final long id;
    private final UUID playerUuid;
    private final String symbol;
    private final TradeDirection direction;
    private final double quantity;
    private final double entryPrice;
    private final double contractSize;
    private final double initialMarginRate;
    private final double margin;
    private final String expiryDate;
    private final long openedAt;

    private double lastSettlePrice;
    private double realizedPnl;

    public FuturesPosition(long id, UUID playerUuid, String symbol, TradeDirection direction,
                           double quantity, double entryPrice, double contractSize,
                           double initialMarginRate, double margin, String expiryDate,
                           double lastSettlePrice, double realizedPnl, long openedAt) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.symbol = symbol;
        this.direction = direction;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.contractSize = contractSize;
        this.initialMarginRate = initialMarginRate;
        this.margin = margin;
        this.expiryDate = expiryDate;
        this.lastSettlePrice = lastSettlePrice;
        this.realizedPnl = realizedPnl;
        this.openedAt = openedAt;
    }

    public long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getSymbol() { return symbol; }
    public TradeDirection getDirection() { return direction; }
    public double getQuantity() { return quantity; }
    public double getEntryPrice() { return entryPrice; }
    public double getContractSize() { return contractSize; }
    public double getInitialMarginRate() { return initialMarginRate; }
    public double getMargin() { return margin; }
    public String getExpiryDate() { return expiryDate; }
    public double getLastSettlePrice() { return lastSettlePrice; }
    public double getRealizedPnl() { return realizedPnl; }
    public long getOpenedAt() { return openedAt; }

    public void setLastSettlePrice(double lastSettlePrice) { this.lastSettlePrice = lastSettlePrice; }
    public void setRealizedPnl(double realizedPnl) { this.realizedPnl = realizedPnl; }

    public int getLeverage() {
        return Math.max(1, (int) Math.round(1.0 / initialMarginRate));
    }

    public boolean isExpired(java.time.LocalDate today) {
        if (expiryDate == null || expiryDate.isBlank()) return false;
        try {
            return !java.time.LocalDate.parse(expiryDate.strip()).isAfter(today);
        } catch (java.time.format.DateTimeParseException ignored) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "#" + id + " " + symbol + " " + direction + " qty=" + quantity
                + " entry=" + entryPrice + " margin=" + margin + " expiry=" + expiryDate;
    }
}
