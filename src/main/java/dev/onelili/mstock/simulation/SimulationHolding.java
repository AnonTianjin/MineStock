package dev.onelili.mstock.simulation;

import java.util.UUID;

/**
 * 模拟盘的<b>现金</b>持仓模型。
 *
 * <p><b>为什么不复用 {@code Holding}</b>：{@code HoldingRepository.mapRow} 需要
 * {@code rs.getLong("id")}，而 {@code simulation_holdings} 按设计只有
 * {@code UNIQUE(player_uuid, code)}、<b>没有自增主键列</b>；且两张表名不同，
 * 无法共用同一份仓储实现。因此这里单列一个模型。
 */
public final class SimulationHolding {

    private final UUID playerUuid;
    private final String code;
    private int amount;
    private double avgCost;
    private double lastPrice;
    private long lastFetched;

    public SimulationHolding(UUID playerUuid, String code, int amount, double avgCost,
                             double lastPrice, long lastFetched) {
        this.playerUuid = playerUuid;
        this.code = code;
        this.amount = amount;
        this.avgCost = avgCost;
        this.lastPrice = lastPrice;
        this.lastFetched = lastFetched;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getCode() {
        return code;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public double getAvgCost() {
        return avgCost;
    }

    public void setAvgCost(double avgCost) {
        this.avgCost = avgCost;
    }

    public double getLastPrice() {
        return lastPrice;
    }

    public void setLastPrice(double lastPrice) {
        this.lastPrice = lastPrice;
    }

    public long getLastFetched() {
        return lastFetched;
    }

    public void setLastFetched(long lastFetched) {
        this.lastFetched = lastFetched;
    }

    @Override
    public String toString() {
        return "SimulationHolding{" + playerUuid + " " + code + " amount=" + amount
                + " avgCost=" + avgCost + "}";
    }
}
