package dev.onelili.mstock.leverage;

import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 部分平仓结算测试：数量与保证金同比例释放，强平价与杠杆不变。 */
class PartialCloseTest {

    private static final double EPS = 1e-9;

    private static LeverageConfig config() {
        return new LeverageConfig(null) {
            @Override public double getMaintenanceMarginRate() { return 0.005; }
            @Override public double getMarginCallThreshold() { return 0.5; }
            @Override public double getLiquidationThreshold() { return 0.1; }
        };
    }

    private static MarginPosition position(double entry, double quantity, int leverage,
                                           double margin) {
        return new MarginPosition(1L, UUID.randomUUID(), MarketType.EQUITY, "600519",
                TradeDirection.LONG, quantity, entry, leverage, margin, 0.0, 0.0,
                System.currentTimeMillis());
    }

    @Test
    void partialCloseReleasesMarginProportionally() {
        LeverageEngine engine = new LeverageEngine(config());
        // 100 股，保证金 1000；平掉 40 股
        MarginPosition position = position(10.0, 100, 10, 1000.0);

        LeverageEngine.PartialSettlement settlement = engine.partialClose(position, 40);

        assertEquals(40, settlement.closedQuantity(), EPS);
        assertEquals(400.0, settlement.releasedMargin(), EPS);
        assertEquals(60, settlement.remainingQuantity(), EPS);
        assertEquals(600.0, settlement.remainingMargin(), EPS);
    }

    @Test
    void fullCloseReleasesEverything() {
        LeverageEngine engine = new LeverageEngine(config());
        MarginPosition position = position(10.0, 100, 10, 1000.0);

        LeverageEngine.PartialSettlement settlement = engine.partialClose(position, 100);

        assertEquals(100, settlement.closedQuantity(), EPS);
        assertEquals(1000.0, settlement.releasedMargin(), EPS);
        assertEquals(0, settlement.remainingQuantity(), EPS);
        assertEquals(0, settlement.remainingMargin(), EPS);
    }

    @Test
    void overCloseIsClampedToFullPosition() {
        LeverageEngine engine = new LeverageEngine(config());
        MarginPosition position = position(10.0, 100, 10, 1000.0);

        // 想平 250 股，但只有 100 股 —— 全平，不产生负数量
        LeverageEngine.PartialSettlement settlement = engine.partialClose(position, 250);

        assertEquals(100, settlement.closedQuantity(), EPS);
        assertEquals(1000.0, settlement.releasedMargin(), EPS);
        assertEquals(0, settlement.remainingQuantity(), EPS);
    }

    @Test
    void zeroOrNegativeQuantityClosesNothing() {
        LeverageEngine engine = new LeverageEngine(config());
        MarginPosition position = position(10.0, 100, 10, 1000.0);

        LeverageEngine.PartialSettlement zero = engine.partialClose(position, 0);
        assertEquals(0, zero.closedQuantity(), EPS);
        assertEquals(0, zero.releasedMargin(), EPS);
        assertEquals(100, zero.remainingQuantity(), EPS);

        LeverageEngine.PartialSettlement negative = engine.partialClose(position, -50);
        assertEquals(0, negative.closedQuantity(), EPS);
        assertEquals(100, negative.remainingQuantity(), EPS);
    }

    @Test
    void partialPnlMatchesProRataShare() {
        LeverageEngine engine = new LeverageEngine(config());
        // 100 股 @10，保证金 1000（10x）；价格涨到 11 → 整仓盈亏 +100
        MarginPosition position = position(10.0, 100, 10, 1000.0);

        assertEquals(100.0, engine.unrealizedPnl(position, 11.0), EPS);
        // 平掉 40 股 → 分摊 40% = +40
        assertEquals(40.0, engine.partialPnl(position, 11.0, 40), EPS);
        // 全平 → 整仓盈亏
        assertEquals(100.0, engine.partialPnl(position, 11.0, 100), EPS);
        // 亏损方向：价格跌到 9 → 平 25 股分摊 -25
        assertEquals(-25.0, engine.partialPnl(position, 9.0, 25), EPS);
    }

    @Test
    void partialCloseKeepsLiquidationPriceUnchanged() {
        LeverageEngine engine = new LeverageEngine(config());
        MarginPosition position = position(10.0, 100, 10, 1000.0);

        // 强平价由开仓时入场价与杠杆决定：部分平仓前后必须一致。
        // （仓位对象上存的 liquidationPrice 是开仓时固化的快照，这里直接对比重算值。）
        double before = engine.liquidationPrice(position);

        LeverageEngine.PartialSettlement settlement = engine.partialClose(position, 40);

        // 剩余仓位仍用原 entry/leverage 计算，强平价一致
        double after = engine.liquidationPrice(position.getEntryPrice(),
                position.getLeverage(), position.getDirection());
        assertEquals(before, after, EPS);
        assertEquals(60, settlement.remainingQuantity(), EPS);
        // 剩余保证金也按比例保留
        assertEquals(600.0, settlement.remainingMargin(), EPS);
    }

    @Test
    void shortPositionPartialCloseAlsoReleasesProportionally() {
        LeverageEngine engine = new LeverageEngine(config());
        // 空头 200 股 @5，保证金 200（5x）
        MarginPosition position = new MarginPosition(2L, UUID.randomUUID(), MarketType.EQUITY,
                "000001", TradeDirection.SHORT, 200, 5.0, 5, 200.0, 0.0, 0.0,
                System.currentTimeMillis());

        LeverageEngine.PartialSettlement settlement = engine.partialClose(position, 50);

        assertEquals(50, settlement.closedQuantity(), EPS);
        assertEquals(50.0, settlement.releasedMargin(), EPS);
        assertEquals(150, settlement.remainingQuantity(), EPS);
        assertEquals(150.0, settlement.remainingMargin(), EPS);
    }
}
