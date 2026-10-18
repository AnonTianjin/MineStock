package dev.onelili.mstock.leverage;

import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 杠杆与保证金计算测试。
 *
 * <p>直接构造 {@link LeverageConfig} 的子类以注入固定阈值，从而不依赖 Bukkit 运行时。
 */
class LeverageEngineTest {

    private static final double EPS = 1e-6;

    /** 默认值：max 100 / default 10 / call 0.5 / liq 0.1 / maintenance 0.005。 */
    private static LeverageConfig config() {
        return new LeverageConfig(null) {
            @Override public int getMaxLeverage() { return 100; }
            @Override public int getDefaultLeverage() { return 10; }
            @Override public double getMarginCallThreshold() { return 0.5; }
            @Override public double getLiquidationThreshold() { return 0.1; }
            @Override public double getMaintenanceMarginRate() { return 0.005; }
        };
    }

    private static MarginPosition position(double entry, double quantity, int leverage,
                                           TradeDirection direction, double margin) {
        return new MarginPosition(1L, UUID.randomUUID(), MarketType.FOREX, "EURUSD",
                direction, quantity, entry, leverage, margin, 0.0, 0.0,
                System.currentTimeMillis());
    }

    @Test
    void freezesNotionalDividedByLeverageAsMargin() {
        LeverageEngine engine = new LeverageEngine(config());

        // 1.0 × 10000 / 10 = 1000
        assertEquals(1000.0, engine.requiredMargin(1.0, 10000, 10), EPS);
        // 1.0 × 10000 / 100 = 100
        assertEquals(100.0, engine.requiredMargin(1.0, 10000, 100), EPS);
    }

    @Test
    void unrealizedPnlHasOppositeSignForLongAndShort() {
        LeverageEngine engine = new LeverageEngine(config());
        MarginPosition longPosition = position(1.10, 1000, 10, TradeDirection.LONG, 110.0);
        MarginPosition shortPosition = position(1.10, 1000, 10, TradeDirection.SHORT, 110.0);

        // 多：价格涨 → 盈利
        assertEquals(20.0, engine.unrealizedPnl(longPosition, 1.12), EPS);
        // 空：价格涨 → 亏损
        assertEquals(-20.0, engine.unrealizedPnl(shortPosition, 1.12), EPS);
    }

    @Test
    void marginRatioIsOneAtOpenRegardlessOfLeverage() {
        LeverageEngine engine = new LeverageEngine(config());

        // 这是把保证金率定义为 (margin + pnl)/margin 的关键理由：
        // 若用"权益/名义价值"，10 倍杠杆开仓即为 0.1，会立刻触发强平。
        for (int leverage : new int[]{1, 10, 30, 100}) {
            double margin = engine.requiredMargin(1.10, 1000, leverage);
            MarginPosition open = position(1.10, 1000, leverage, TradeDirection.LONG, margin);
            assertEquals(1.0, engine.marginRatio(open, 1.10), EPS,
                    "开仓时保证金率必须为 1.0（杠杆 " + leverage + "x）");
        }
    }

    @Test
    void liquidationTriggersAtThresholdAndNotBefore() {
        LeverageEngine engine = new LeverageEngine(config());
        int leverage = 10;
        double entry = 1.0;
        double quantity = 1000;
        double margin = engine.requiredMargin(entry, quantity, leverage); // 100

        MarginPosition position = position(entry, quantity, leverage,
                TradeDirection.LONG, margin);

        // 亏损 40% → 保证金率 0.6，仅高于追保线
        assertEquals(0.6, engine.marginRatio(position, 0.96), EPS);
        assertFalse(engine.shouldMarginCall(position, 0.96));
        assertFalse(engine.shouldLiquidate(position, 0.96));

        // 亏损 50% → 保证金率 0.5，触发追保
        assertEquals(0.5, engine.marginRatio(position, 0.95), EPS);
        assertTrue(engine.shouldMarginCall(position, 0.95));
        assertFalse(engine.shouldLiquidate(position, 0.95));

        // 亏损 90% → 保证金率 0.1，触发强平
        assertEquals(0.1, engine.marginRatio(position, 0.91), EPS);
        assertTrue(engine.shouldLiquidate(position, 0.91));
    }

    @Test
    void thresholdComparisonToleratesFloatingPointNoise() {
        LeverageEngine engine = new LeverageEngine(config());
        double margin = engine.requiredMargin(1.0, 1000, 10); // 100
        MarginPosition position = position(1.0, 1000, 10, TradeDirection.LONG, margin);

        // 0.90 会让 (100 + (-100.00000000000003)) / 100 落在强平线下方一点点；
        // 容差必须吸收它，否则本该强平的仓位会侥幸逃过巡检。
        assertTrue(engine.shouldLiquidate(position, 0.90));
    }

    @Test
    void liquidationPriceIsSymmetricForLongAndShort() {
        LeverageEngine engine = new LeverageEngine(config());
        double entry = 100.0;
        int leverage = 10;

        double longLiq = engine.liquidationPrice(entry, leverage, TradeDirection.LONG);
        double shortLiq = engine.liquidationPrice(entry, leverage, TradeDirection.SHORT);

        // 多头在下方，空头在上方
        assertTrue(longLiq < entry, "多头强平价应低于开仓价");
        assertTrue(shortLiq > entry, "空头强平价应高于开仓价");

        // 1 - (1 - 0.1) / 10 = 0.91
        assertEquals(entry * (1 - (1 - 0.1) / leverage), longLiq, EPS);
        // 1 + (1 - 0.1) / 10 = 1.09
        assertEquals(entry * (1 + (1 - 0.1) / leverage), shortLiq, EPS);
    }

    /**
     * 显示的强平价与后台巡检必须同口径：价格恰好落在强平价上时，
     * 保证金率等于 {@code liquidationThreshold} 且 {@code shouldLiquidate} 判真。
     */
    @Test
    void displayedLiquidationPriceMatchesShouldLiquidate() {
        LeverageEngine engine = new LeverageEngine(config());
        double entry = 100.0;
        double quantity = 1000;

        for (int leverage : new int[]{1, 5, 10, 50, 100}) {
            double margin = engine.requiredMargin(entry, quantity, leverage);
            MarginPosition position = position(entry, quantity, leverage,
                    TradeDirection.LONG, margin);
            double liq = engine.liquidationPrice(entry, leverage, TradeDirection.LONG);

            assertEquals(engine.getLiquidationThreshold(),
                    engine.marginRatio(position, liq), 1e-6,
                    "触及显示强平价时保证金率必须恰为强平阈值（杠杆 " + leverage + "x）");
            assertTrue(engine.shouldLiquidate(position, liq),
                    "显示强平价必须被巡检判为强平（杠杆 " + leverage + "x）");
        }
    }

    @Test
    void existingPositionsKeepTheirOwnLeverageAndMargin() {
        LeverageEngine engine = new LeverageEngine(config());

        // 两笔同品种仓位，开仓杠杆不同：各自的保证金与强平价互不影响。
        double price = 1.10;
        double quantity = 1000;
        MarginPosition low = engine.open(UUID.randomUUID(), 1L, MarketType.FOREX, "EURUSD",
                TradeDirection.LONG, price, quantity, 5, engine.requiredMargin(price, quantity, 5));
        MarginPosition high = engine.open(UUID.randomUUID(), 2L, MarketType.FOREX, "EURUSD",
                TradeDirection.LONG, price, quantity, 50, engine.requiredMargin(price, quantity, 50));

        assertEquals(5, low.getLeverage());
        assertEquals(50, high.getLeverage());
        assertEquals(220.0, low.getMargin(), EPS);   // 1100 / 5
        assertEquals(22.0, high.getMargin(), EPS);   // 1100 / 50
        assertTrue(low.getLiquidationPrice() < high.getLiquidationPrice(),
                "杠杆越低，强平价离开仓价越远（多头方向即更低的价位）");

        // 无论玩家之后把个人杠杆改成多少，这两笔仓位的记录都不变。
        assertEquals(5, low.getLeverage());
        assertEquals(22.0, high.getMargin(), EPS);
        assertEquals(engine.liquidationPrice(price, 5, TradeDirection.LONG),
                low.getLiquidationPrice(), EPS);
    }

    @Test
    void closePayoutNeverGoesBelowZero() {
        LeverageEngine engine = new LeverageEngine(config());
        MarginPosition position = position(1.0, 1000, 10, TradeDirection.LONG, 100.0);

        assertEquals(120.0, engine.closePayout(position, 1.02), EPS);
        // 价格暴跌：亏损超过保证金时只退还 0，不会向玩家倒扣。
        assertEquals(0.0, engine.closePayout(position, 0.80), EPS);
    }

    @Test
    void leverageConfigClampsIntoValidRange() {
        LeverageConfig config = config();
        assertEquals(1, config.clamp(0));
        assertEquals(1, config.clamp(-50));
        assertEquals(100, config.clamp(1000));
        assertTrue(config.isValidLeverage(1));
        assertTrue(config.isValidLeverage(100));
        assertFalse(config.isValidLeverage(0));
        assertFalse(config.isValidLeverage(101));
    }
}
