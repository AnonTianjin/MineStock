package dev.onelili.mstock.futures;

import dev.onelili.mstock.model.FuturesContract;
import dev.onelili.mstock.model.FuturesPosition;
import dev.onelili.mstock.model.TradeDirection;

import java.time.LocalDate;

/**
 * 期货合约的核心计算与结算逻辑。
 *
 * <p>不依赖 Bukkit 运行时，便于单元测试。
 */
public class FuturesEngine {

    /**
     * 开仓保证金 = {@code price × contractSize × quantity × initialMarginRate}。
     *
     * <p>{@code initialMarginRate} 与杠杆互为倒数：{@code 0.1} 即 10 倍。
     */
    public double requiredMargin(FuturesContract contract, double price, double quantity) {
        if (contract == null || price <= 0 || quantity <= 0) return 0.0;
        return price * contract.getContractSize() * quantity * contract.getInitialMarginRate();
    }

    /** 一个 tick 对应的合约价值变动，用于展示。 */
    public double tickValue(FuturesContract contract, double quantity) {
        if (contract == null || quantity <= 0) return 0.0;
        return contract.getTickSize() * contract.getContractSize() * quantity;
    }

    /**
     * 盯市结算盈亏（单次）。
     *
     * <p>{@code (settlePrice - lastSettlePrice) × contractSize × quantity × direction}。
     */
    public double settlementPnl(FuturesPosition position, double settlePrice) {
        double reference = position.getLastSettlePrice() > 0
                ? position.getLastSettlePrice()
                : position.getEntryPrice();
        return (settlePrice - reference)
                * position.getContractSize()
                * position.getQuantity()
                * position.getDirection().sign();
    }

    /** 相对开仓价的累计盈亏（用于展示"总浮动盈亏"）。 */
    public double totalPnl(FuturesPosition position, double currentPrice) {
        return (currentPrice - position.getEntryPrice())
                * position.getContractSize()
                * position.getQuantity()
                * position.getDirection().sign();
    }

    /** 平仓释放金额：保证金 + 累计盯市盈亏，下限为 0。 */
    public double closePayout(FuturesPosition position, double currentPrice) {
        double payout = position.getMargin() + totalPnl(position, currentPrice);
        return Math.max(0.0, payout);
    }

    /** 合约或持仓是否已到期。 */
    public boolean isExpired(FuturesContract contract, LocalDate today) {
        return contract != null && contract.isExpired(today);
    }

    public boolean isExpired(FuturesPosition position, LocalDate today) {
        return position.isExpired(today);
    }

    /**
     * 结算价选择的兜底策略。
     *
     * <p>调用方应优先取 {@code ohlc(symbol, "1d", 2)} 中最后一根<b>已收盘</b>的 bar
     * （{@code limit=1} 会拿到 {@code isOpen: true} 的当日未完成 bar）。
     * 无已收盘 bar 时才退回最新 {@code mid}。
     */
    public double chooseSettlePrice(Double lastClosedDailyClose, Double latestMid) {
        if (lastClosedDailyClose != null && Double.isFinite(lastClosedDailyClose)
                && lastClosedDailyClose > 0) {
            return lastClosedDailyClose;
        }
        return latestMid != null && Double.isFinite(latestMid) ? latestMid : 0.0;
    }

    public TradeDirection directionOf(FuturesPosition position) {
        return position.getDirection();
    }
}
