package dev.onelili.mstock.leverage;

import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补交保证金计算测试。
 *
 * <p>补交 = 给浮亏仓位去杠杆：名义价值不变、保证金变多，
 * 保证金率回升、有效杠杆降低、强平价被推远。
 */
class MarginTopUpTest {

    private static final double EPS = 1e-6;

    private static LeverageConfig config() {
        return new LeverageConfig(null) {
            @Override public double getMaintenanceMarginRate() { return 0.005; }
            @Override public double getMarginCallThreshold() { return 0.5; }
            @Override public double getLiquidationThreshold() { return 0.1; }
        };
    }

    private static final LeverageEngine engine = new LeverageEngine(config());

    /** 10x 多头：100 股 @10，保证金 100，强平价 9.1。 */
    private static MarginPosition longPosition() {
        return new MarginPosition(1L, UUID.randomUUID(), MarketType.EQUITY, "600519",
                TradeDirection.LONG, 100, 10.0, 10, 100.0,
                engine.liquidationPrice(10.0, 10, TradeDirection.LONG), 0.0,
                System.currentTimeMillis());
    }

    @Test
    void marginRatioAtEntryIsOne() {
        assertEquals(1.0, engine.marginRatio(longPosition(), 10.0), EPS);
    }

    @Test
    void requiredTopUpIsZeroWhenFloatingProfit() {
        // 浮盈：不需要补交
        MarginPosition position = longPosition();
        assertEquals(0.0, engine.requiredTopUp(position, 11.0, 0.8), EPS);
        assertEquals(0.0, engine.requiredTopUp(position, 10.0, 0.8), EPS);
    }

    @Test
    void requiredTopUpBringsRatioBackToTarget() {
        // 价格跌到 9.5：uPnL = -50，保证金率 = (100-50)/100 = 0.5（正好在追保线）
        MarginPosition position = longPosition();
        double ratio = engine.marginRatio(position, 9.5);
        assertEquals(0.5, ratio, EPS);

        // 补交 X 使 (100 + X - 50) / (100 + X) = 0.8 → X = 150
        double topUp = engine.requiredTopUp(position, 9.5, 0.8);
        assertEquals(150.0, topUp, EPS);

        // 验证：补交后保证金率确实回到 0.8
        double newMargin = 100 + topUp;
        double ratioAfter = (newMargin + engine.unrealizedPnl(position, 9.5)) / newMargin;
        assertEquals(0.8, ratioAfter, EPS);
    }

    @Test
    void deeperLossNeedsLargerTopUp() {
        MarginPosition position = longPosition();

        // 跌到 9.1：uPnL = -90，保证金率 0.1 —— 已在强平线上
        double shallow = engine.requiredTopUp(position, 9.5, 0.8);
        double deep = engine.requiredTopUp(position, 9.1, 0.8);
        assertTrue(deep > shallow, "亏得越深，需要补交越多");
    }

    @Test
    void invalidTargetRatioNeedsNothing() {
        MarginPosition position = longPosition();
        // target >= 1 无意义（保证金率不可能靠补交超过 1）
        assertEquals(0.0, engine.requiredTopUp(position, 9.0, 1.0), EPS);
        assertEquals(0.0, engine.requiredTopUp(position, 9.0, 1.2), EPS);
        assertEquals(0.0, engine.requiredTopUp(position, 9.0, 0.0), EPS);
    }

    @Test
    void topUpDerisksLeverageAndPushesLiquidationPrice() {
        MarginPosition position = longPosition();
        double liqBefore = engine.liquidationPrice(position);

        // 补交 150 → 新保证金 250，名义价值 1000 → 有效杠杆 4x
        double newMargin = 250.0;
        int newLeverage = engine.leverageAfterTopUp(position, newMargin);
        assertEquals(4, newLeverage);

        double liqAfter = engine.liquidationPriceAfterTopUp(position, newMargin);
        // 多头强平价被推得更低（离现价更远），风险变小
        assertTrue(liqAfter < liqBefore,
                "补交后强平价应更低（更难触发强平）：" + liqBefore + " → " + liqAfter);
        // 10x → 强平价 9.1；4x → 1 - (1 - 0.1) / 4 = 0.775 → 7.75
        assertEquals(10.0 * (1 - 0.9 / 4), liqAfter, EPS);
    }

    @Test
    void heavyTopUpLeverageFloorsAtOne() {
        MarginPosition position = longPosition();

        // 补交到保证金超过名义价值：有效杠杆下限为 1，不会变成 0 或负数
        int leverage = engine.leverageAfterTopUp(position, 2000.0);
        assertEquals(1, leverage);

        // 杠杆 1 的强平价 = entry * (1 - (1 - 0.1)) = entry * 0.1，跌 90% 才触发
        double liq = engine.liquidationPriceAfterTopUp(position, 2000.0);
        assertEquals(10.0 * (1 - 0.9), liq, EPS);
    }

    @Test
    void topUpWorksForShortPositionsSymmetrically() {
        // 10x 空头：100 股 @10，保证金 100
        MarginPosition position = new MarginPosition(2L, UUID.randomUUID(), MarketType.FOREX,
                "EURUSD", TradeDirection.SHORT, 100, 10.0, 10, 100.0,
                engine.liquidationPrice(10.0, 10, TradeDirection.SHORT), 0.0,
                System.currentTimeMillis());

        // 空头亏损 = 价格上涨：涨到 10.5 → uPnL = -50
        assertEquals(-50.0, engine.unrealizedPnl(position, 10.5), EPS);
        double topUp = engine.requiredTopUp(position, 10.5, 0.8);
        assertEquals(150.0, topUp, EPS);

        // 空头强平价被推得更高（更难触发）
        double liqBefore = engine.liquidationPrice(position);
        double liqAfter = engine.liquidationPriceAfterTopUp(position, 250.0);
        assertTrue(liqAfter > liqBefore, "空头补交后强平价应更高");
    }
}
