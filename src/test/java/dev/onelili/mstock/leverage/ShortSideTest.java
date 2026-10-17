package dev.onelili.mstock.leverage;

import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import dev.onelili.mstock.ui.PendingAction;
import dev.onelili.mstock.ui.gui.ForexGui;
import dev.onelili.mstock.ui.gui.FuturesGui;
import dev.onelili.mstock.ui.gui.SimulationGui;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 做空机制的纯单元断言：方向解析、盈亏符号、强平价方向、确认流分类与交易页槽位。
 *
 * <p>这些都是"做空 = 多头取反"的关键点，一旦符号写反，玩家会在价格下跌时亏钱、
 * 上涨时强平 —— 与预期完全相反，所以逐项钉住。
 */
class ShortSideTest {

    private static final double EPS = 1e-9;

    /** 交易页共用的杠杆配置桩：维护保证金率 / 追保 / 强平阈值都是固定值。 */
    private static LeverageEngine engine() {
        return new LeverageEngine(new LeverageConfig(null) {
            @Override
            public double getMaintenanceMarginRate() { return 0.005; }

            @Override
            public double getMarginCallThreshold() { return 0.5; }

            @Override
            public double getLiquidationThreshold() { return 0.1; }
        });
    }

    private static MarginPosition position(TradeDirection direction, double quantity,
                                           double entryPrice, int leverage, double margin) {
        return new MarginPosition(1L, UUID.randomUUID(), MarketType.EQUITY, "600519",
                direction, quantity, entryPrice, leverage, margin, 0.0, 0.0,
                System.currentTimeMillis());
    }

    @Test
    void parseRecognisesChineseAndEnglishShortSpellings() {
        for (String raw : List.of("short", "SHORT", " Short ", "s", "空", "卖空", "开空")) {
            assertEquals(TradeDirection.SHORT, TradeDirection.parse(raw),
                    "「" + raw + "」应解析为空头方向");
        }
        for (String raw : List.of("long", "L", "多", "买多", "开多")) {
            assertEquals(TradeDirection.LONG, TradeDirection.parse(raw),
                    "「" + raw + "」应解析为多头方向");
        }
    }

    @Test
    void shortSignIsNegativeOneAndOppositeFlips() {
        assertEquals(1, TradeDirection.LONG.sign());
        assertEquals(-1, TradeDirection.SHORT.sign());
        assertEquals(TradeDirection.SHORT, TradeDirection.LONG.opposite());
        assertEquals(TradeDirection.LONG, TradeDirection.SHORT.opposite());
    }

    @Test
    void shortProfitsWhenPriceFalls() {
        LeverageEngine engine = engine();
        MarginPosition shortPosition = position(TradeDirection.SHORT, 10, 100.0, 10, 100.0);

        // 价格从 100 跌到 90：空头赚 (100-90)*10 = 100
        assertEquals(100.0, engine.unrealizedPnl(shortPosition, 90.0), 1e-6,
                "空头在价格下跌时必须盈利，方向符号写反会让它变成亏损");
        // 价格涨到 110：空头亏 100
        assertEquals(-100.0, engine.unrealizedPnl(shortPosition, 110.0), 1e-6,
                "空头在价格上涨时必须亏损");
    }

    @Test
    void longAndShortPnlAreMirrorImages() {
        LeverageEngine engine = engine();
        MarginPosition longPosition = position(TradeDirection.LONG, 4, 250.0, 5, 200.0);
        MarginPosition shortPosition = position(TradeDirection.SHORT, 4, 250.0, 5, 200.0);

        assertEquals(engine.unrealizedPnl(longPosition, 260.0),
                -engine.unrealizedPnl(shortPosition, 260.0), 1e-6,
                "同一价格下多空盈亏必须互为相反数");
    }

    @Test
    void shortLiquidationPriceSitsAboveEntry() {
        LeverageEngine engine = engine();
        double entry = 100.0;
        int leverage = 10;

        double longLiquidation = engine.liquidationPrice(entry, leverage, TradeDirection.LONG);
        double shortLiquidation = engine.liquidationPrice(entry, leverage, TradeDirection.SHORT);

        assertTrue(longLiquidation < entry,
                "多头强平价必须在开仓价下方，实际 " + longLiquidation);
        assertTrue(shortLiquidation > entry,
                "空头强平价必须在开仓价上方，实际 " + shortLiquidation);
    }

    @Test
    void partialCloseReturnsProportionalPnlForShort() {
        LeverageEngine engine = engine();
        MarginPosition shortPosition = position(TradeDirection.SHORT, 10, 100.0, 10, 100.0);

        // 平掉 40% 数量，价格 90（空头浮盈 10/股）⇒ 部分盈亏 = 4 * 10 = 40
        assertEquals(40.0, engine.partialPnl(shortPosition, 90.0, 4), 1e-6,
                "部分平仓的空头盈亏必须与全量盈亏同一符号");
    }

    @Test
    void notionalIsDirectionIndependent() {
        MarginPosition shortPosition = position(TradeDirection.SHORT, 3, 200.0, 4, 150.0);
        assertEquals(600.0, shortPosition.getNotional(), EPS,
                "名义价值只看价格与数量，不随方向取反");
        assertEquals(660.0, shortPosition.notionalAt(220.0), EPS);
    }

    @Test
    void pendingActionTreatsShortAndCoverAsStockTrades() {
        PendingAction shortAction = new PendingAction(PendingAction.Type.SHORT, "600519");
        PendingAction coverAction = new PendingAction(PendingAction.Type.COVER, "600519");

        assertTrue(shortAction.isStockTrade(), "做空必须走股票确认流（整数股数 + 确认页）");
        assertTrue(coverAction.isStockTrade(), "回补必须走股票确认流");
        assertTrue(shortAction.isOpeningTrade(), "做空开的是新仓位，确认页按付出计算");
        assertFalse(coverAction.isOpeningTrade(), "回补是平仓，确认页按到账计算");
        assertFalse(shortAction.isTextInput(), "做空不接受文本输入");
    }

    @Test
    void tradePageSlotsAreDistinctAndInsideInventory() {
        List<Integer> forex = List.of(
                ForexGui.SLOT_CLOSE_LONG, ForexGui.SLOT_BUY,
                ForexGui.SLOT_QUANTITY, ForexGui.SLOT_SELL, ForexGui.SLOT_CLOSE_SHORT);
        List<Integer> futures = List.of(
                FuturesGui.SLOT_CLOSE_LONG, FuturesGui.SLOT_OPEN_LONG,
                FuturesGui.SLOT_QUANTITY, FuturesGui.SLOT_OPEN_SHORT, FuturesGui.SLOT_CLOSE_SHORT);
        List<Integer> simulation = List.of(
                SimulationGui.SLOT_SHORT, SimulationGui.SLOT_BUY,
                SimulationGui.SLOT_QUANTITY, SimulationGui.SLOT_SELL, SimulationGui.SLOT_COVER);

        assertDistinctInside(forex, "外汇交易页");
        assertDistinctInside(futures, "期货交易页");
        assertDistinctInside(simulation, "模拟盘交易页");
    }

    private static void assertDistinctInside(List<Integer> slots, String page) {
        assertEquals(slots.size(), slots.stream().distinct().count(),
                page + "的开仓 / 平仓 / 数量按钮不能占用同一个槽位，实际 " + slots);
        for (int slot : slots) {
            assertTrue(slot >= 0 && slot < 54,
                    page + "槽位必须落在 54 格背包内，实际 " + slot);
        }
    }

    @Test
    void shortAndCoverButtonsSitOnOppositeSidesOfQuantity() {
        // 做空在数量左侧、平空在数量右侧：开仓与平仓成对出现，玩家不会点错。
        assertTrue(ForexGui.SLOT_CLOSE_LONG < ForexGui.SLOT_BUY,
                "外汇交易页：平多必须排在做多左侧");
        assertTrue(ForexGui.SLOT_SELL < ForexGui.SLOT_CLOSE_SHORT,
                "外汇交易页：平空必须排在做空右侧");
        assertTrue(FuturesGui.SLOT_CLOSE_LONG < FuturesGui.SLOT_OPEN_LONG,
                "期货页：平多必须排在开多左侧");
        assertTrue(FuturesGui.SLOT_OPEN_SHORT < FuturesGui.SLOT_CLOSE_SHORT,
                "期货页：平空必须排在开空右侧");
        assertTrue(SimulationGui.SLOT_SHORT < SimulationGui.SLOT_BUY,
                "模拟盘交易页：做空必须排在买入左侧");
        assertTrue(SimulationGui.SLOT_SELL < SimulationGui.SLOT_COVER,
                "模拟盘交易页：平空必须排在卖出右侧");
    }

    @Test
    void shortLeverageConfigKnowsStockPolicy() {
        LeverageConfig config = new LeverageConfig(null);
        assertNotNull(config, "杠杆配置必须能构造，做空校验依赖它");
        assertEquals(LeverageConfig.Policy.STOCK, LeverageConfig.Policy.of(MarketType.EQUITY),
                "股票走 STOCK 制度");
        assertEquals(LeverageConfig.Policy.STOCK, LeverageConfig.Policy.of(MarketType.SIMULATION),
                "模拟盘也走 STOCK 制度 —— 模拟做空的杠杆上限不能按外汇 100 倍算");
        assertEquals(LeverageConfig.Policy.FOREX, LeverageConfig.Policy.of(MarketType.FOREX));
    }
}
