package dev.onelili.mstock.model;

import java.util.UUID;

/**
 * 保证金持仓。
 *
 * <p>{@code leverage} 与 {@code margin} 在开仓时确定后不再变化：既有仓位永远使用开仓时的
 * 杠杆与保证金，玩家后续调整个人杠杆只影响新开仓位。
 */
public class MarginPosition {

    private final long id;
    private final UUID playerUuid;
    private final MarketType market;
    private final String symbol;
    private final TradeDirection direction;
    private final double quantity;
    private final double entryPrice;
    private final int leverage;
    private final double margin;
    private final double liquidationPrice;
    private final long openedAt;

    private double unrealizedPnl;

    public MarginPosition(long id, UUID playerUuid, MarketType market, String symbol,
                          TradeDirection direction, double quantity, double entryPrice,
                          int leverage, double margin, double liquidationPrice,
                          double unrealizedPnl, long openedAt) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.market = market;
        this.symbol = symbol;
        this.direction = direction;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.leverage = leverage;
        this.margin = margin;
        this.liquidationPrice = liquidationPrice;
        this.unrealizedPnl = unrealizedPnl;
        this.openedAt = openedAt;
    }

    public long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public MarketType getMarket() { return market; }
    public String getSymbol() { return symbol; }
    public TradeDirection getDirection() { return direction; }
    public double getQuantity() { return quantity; }
    public double getEntryPrice() { return entryPrice; }
    public int getLeverage() { return leverage; }
    public double getMargin() { return margin; }
    public double getLiquidationPrice() { return liquidationPrice; }
    public double getUnrealizedPnl() { return unrealizedPnl; }
    public long getOpenedAt() { return openedAt; }

    public void setUnrealizedPnl(double unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; }

    /** 开仓名义价值。 */
    public double getNotional() {
        return entryPrice * quantity;
    }

    /** 当前名义价值（按给定价格）。 */
    public double notionalAt(double price) {
        return price * quantity;
    }

    @Override
    public String toString() {
        return "#" + id + " " + symbol + " " + direction + " qty=" + quantity
                + " entry=" + entryPrice + " lev=" + leverage + " margin=" + margin;
    }
}
