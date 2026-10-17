package dev.onelili.mstock.stock;

import dev.onelili.mstock.config.LeverageConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 杠杆"生效"的端到端算术验证。
 *
 * <p>用户目标是"杠杆可以正常调整倍数并且<b>生效</b>"。前面的测试覆盖了
 * "倍数能不能被接受"，这里补上"倍数是否真的改变了金钱结果"——
 * 即同一个仓位在不同杠杆下，冻结的保证金、强平价、盈亏放大倍数都不同。
 *
 * <p>若只改校验而不改计算，玩家会看到"杠杆已设置为 50x"但实际仍按 1x 冻结保证金，
 * 这正是"调整了但不生效"的典型症状。
 */
class LeverageEffectTest {

    private static LeverageConfig config() {
        return new LeverageConfig(null) {
            @Override public int getMaxLeverage() { return 100; }
            @Override public int getDefaultLeverage() { return 10; }
            @Override public int getForexDefaultLeverage() { return 30; }
            @Override public int getStockMaxLeverage() { return 10; }
            @Override public int getStockDefaultLeverage() { return 1; }
            @Override public boolean isStockLeverageEnabled() { return true; }
            @Override public double getMaintenanceMarginRate() { return 0.005; }
            @Override public double getMarginCallThreshold() { return 0.5; }
            @Override public double getLiquidationThreshold() { return 0.1; }
        };
    }

    private static final dev.onelili.mstock.leverage.LeverageEngine ENGINE =
            new dev.onelili.mstock.leverage.LeverageEngine(config());

    private static final double PRICE = 100.0;
    private static final double QTY = 10.0;
    /** 名义价值 = 1000。 */
    private static final double NOTIONAL = PRICE * QTY;

    /** 倍数越高，冻结的保证金越少 —— 这是杠杆最直接的可观测效果。 */
    @Test
    void higherLeverageFreezesLessMargin() {
        double margin1 = ENGINE.requiredMargin(PRICE, QTY, 1);
        double margin10 = ENGINE.requiredMargin(PRICE, QTY, 10);
        double margin100 = ENGINE.requiredMargin(PRICE, QTY, 100);

        assertEquals(NOTIONAL, margin1, 1e-9, "1x 应冻结全部名义价值");
        assertEquals(NOTIONAL / 10, margin10, 1e-9);
        assertEquals(NOTIONAL / 100, margin100, 1e-9);
        assertTrue(margin100 < margin10 && margin10 < margin1);
    }

    /**
     * 倍数越高，强平价离入场价越近（风险越大）。
     *
     * <p>这条断言证明杠杆真的进入了强平计算，而不只是一个被存起来的数字。
     */
    @Test
    void higherLeverageMovesLiquidationPriceCloser() {
        double liq1 = ENGINE.liquidationPrice(PRICE, 1, dev.onelili.mstock.model.TradeDirection.LONG);
        double liq10 = ENGINE.liquidationPrice(PRICE, 10, dev.onelili.mstock.model.TradeDirection.LONG);
        double liq100 = ENGINE.liquidationPrice(PRICE, 100, dev.onelili.mstock.model.TradeDirection.LONG);

        // 1x 几乎不会强平（只剩维持保证金）
        assertEquals(PRICE * 0.005, liq1, 1e-9);
        // 10x: 100 * (1 - 0.1 + 0.005) = 90.5
        assertEquals(90.5, liq10, 1e-9);
        // 100x: 100 * (1 - 0.01 + 0.005) = 99.5
        assertEquals(99.5, liq100, 1e-9);

        // 杠杆越高，强平价越靠近入场价
        assertTrue(liq100 > liq10 && liq10 > liq1,
                "高杠杆应当更容易被强平：" + liq1 + " / " + liq10 + " / " + liq100);
    }

    /**
     * 同样的价格波动，高杠杆下保证金率跌得更狠（更快触发追保 / 强平）。
     *
     * <p>用同一笔名义价值、同一跌幅，比较不同杠杆的保证金率。
     */
    @Test
    void samePriceDropHitsHighLeverageHarder() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        double dropTo = PRICE * 0.95;           // 跌 5%

        double ratio10 = ENGINE.marginRatio(
                ENGINE.open(uuid, 1L, dev.onelili.mstock.model.MarketType.FOREX, "EURUSD",
                        dev.onelili.mstock.model.TradeDirection.LONG,
                        PRICE, QTY, 10, ENGINE.requiredMargin(PRICE, QTY, 10)),
                dropTo);
        double ratio100 = ENGINE.marginRatio(
                ENGINE.open(uuid, 2L, dev.onelili.mstock.model.MarketType.FOREX, "EURUSD",
                        dev.onelili.mstock.model.TradeDirection.LONG,
                        PRICE, QTY, 100, ENGINE.requiredMargin(PRICE, QTY, 100)),
                dropTo);

        // 10x: 亏 50，保证金 100 → (100-50)/100 = 0.5
        assertEquals(0.5, ratio10, 1e-9);
        // 100x: 亏 50，保证金 10 → (10-50)/10 = -4.0（已亏穿）
        assertEquals(-4.0, ratio100, 1e-9);

        assertTrue(ratio100 < ratio10);
        // 5% 跌幅下 10x 触及追保线、100x 已远超强平线
        assertTrue(ratio10 <= config().getMarginCallThreshold());
        assertTrue(ratio100 <= config().getLiquidationThreshold());
    }

    /** 开仓瞬间保证金率恒为 1.0，与杠杆倍数无关（保证默认值有意义）。 */
    @Test
    void marginRatioAtOpenIsOneForEveryLeverage() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        for (int lev : new int[]{1, 5, 10, 30, 50, 100}) {
            var pos = ENGINE.open(uuid, 1L, dev.onelili.mstock.model.MarketType.FOREX,
                    "EURUSD", dev.onelili.mstock.model.TradeDirection.LONG,
                    PRICE, QTY, lev, ENGINE.requiredMargin(PRICE, QTY, lev));
            assertEquals(1.0, ENGINE.marginRatio(pos, PRICE), 1e-9,
                    lev + "x 开仓保证金率应为 1.0");
        }
    }

    /**
     * 空头方向的强平价在入场价<b>上方</b>，且同样随杠杆靠近。
     *
     * <p>防止杠杆只对多头生效这类方向性错误。
     */
    @Test
    void shortLiquidationIsAboveEntryAndLeverageSensitive() {
        double liq10 = ENGINE.liquidationPrice(PRICE, 10, dev.onelili.mstock.model.TradeDirection.SHORT);
        double liq100 = ENGINE.liquidationPrice(PRICE, 100, dev.onelili.mstock.model.TradeDirection.SHORT);

        assertTrue(liq10 > PRICE, "空头强平价应在入场价上方");
        assertEquals(109.5, liq10, 1e-9);
        assertEquals(100.5, liq100, 1e-9);
        assertTrue(liq100 < liq10, "空头杠杆越高，强平价越靠近入场价");
    }

    /** 部分平仓：保证金按比例释放，且释放额与杠杆一致。 */
    @Test
    void partialCloseReleasesMarginProportionallyToLeverage() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        double margin = ENGINE.requiredMargin(PRICE, QTY, 10);
        var pos = ENGINE.open(uuid, 1L, dev.onelili.mstock.model.MarketType.FOREX,
                "EURUSD", dev.onelili.mstock.model.TradeDirection.LONG,
                PRICE, QTY, 10, margin);

        var half = ENGINE.partialClose(pos, QTY / 2);

        assertEquals(QTY / 2, half.closedQuantity(), 1e-9);
        assertEquals(margin / 2, half.releasedMargin(), 1e-9, "平一半应释放一半保证金");
        assertEquals(margin / 2, half.remainingMargin(), 1e-9);
        // 减仓不改变杠杆与强平价
        assertEquals(margin, half.releasedMargin() + half.remainingMargin(), 1e-9);
        assertNotEquals(0.0, half.remainingMargin());
    }
}
