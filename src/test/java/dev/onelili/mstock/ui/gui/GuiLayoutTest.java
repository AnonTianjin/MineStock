package dev.onelili.mstock.ui.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI 布局约束测试。
 *
 * <p>这些断言不需要启动服务端：槽位是编译期常量，重复占用会让某个按钮
 * 被后写入的物品覆盖而彻底不可点击（本仓库开发过程中就发生过一次）。
 */
class GuiLayoutTest {

    private static final int GUI_SIZE = 54;

    @Test
    void leverageGuiSlotsDoNotOverlap() {
        List<Integer> slots = new ArrayList<>();
        slots.add(LeverageGui.SLOT_INFO);
        slots.add(LeverageGui.SLOT_RESET);
        slots.add(LeverageGui.SLOT_VIEW_FOREX);
        slots.add(LeverageGui.SLOT_VIEW_STOCK);
        for (int slot : LeverageGui.ADJUST_SLOTS) slots.add(slot);

        assertNoDuplicates(slots, "LeverageGui");
        assertEquals(LeverageGui.ADJUST_SLOTS.length, LeverageGui.ADJUST_STEPS.length,
                "槽位与步长必须一一对应");
        // 顶行只读信息不能和任何按钮同槽
        assertFalse(contains(LeverageGui.ADJUST_SLOTS, LeverageGui.SLOT_INFO),
                "调节按钮不能覆盖顶行信息");
    }

    /**
     * 股票 / 外汇两个页签必须可区分，且避开返回按钮。
     *
     * <p>界面上两个制度各占一个页签；若同槽则后写入的会盖掉前一个，
     * 股票杠杆就又没了 GUI 入口（本次修复的正是这个缺口）。
     */
    @Test
    void leverageGuiExposesBothPoliciesOnDistinctSlots() {
        assertTrue(LeverageGui.SLOT_VIEW_FOREX != LeverageGui.SLOT_VIEW_STOCK,
                "外汇与股票页签不能共用槽位");
        assertTrue(LeverageGui.SLOT_VIEW_FOREX != GuiItems.SLOT_BACK_TO_MENU,
                "页签不能占用返回主菜单槽");
        assertTrue(LeverageGui.SLOT_VIEW_STOCK != GuiItems.SLOT_BACK_TO_MENU,
                "页签不能占用返回主菜单槽");
        // 页签不能覆盖调节按钮或重置
        assertFalse(contains(LeverageGui.ADJUST_SLOTS, LeverageGui.SLOT_VIEW_FOREX));
        assertFalse(contains(LeverageGui.ADJUST_SLOTS, LeverageGui.SLOT_VIEW_STOCK));
        assertTrue(LeverageGui.SLOT_VIEW_FOREX != LeverageGui.SLOT_RESET);
        assertTrue(LeverageGui.SLOT_VIEW_STOCK != LeverageGui.SLOT_RESET);
    }

    /** 杠杆界面的制度上下文默认是外汇，切换后可读回。 */
    @Test
    void leverageViewDefaultsToForexAndSwitches() {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.LEVERAGE);
        assertFalse(holder.isStockLeverageView(), "默认应显示外汇杠杆");

        holder.stockLeverageView(true);
        assertTrue(holder.isStockLeverageView());

        holder.stockLeverageView(false);
        assertFalse(holder.isStockLeverageView());
    }

    @Test
    void forexCategorySlotsDoNotOverlap() {
        List<Integer> slots = List.of(
                ForexGui.SLOT_INFO, ForexGui.SLOT_CAT_FIAT, ForexGui.SLOT_CAT_CRYPTO,
                ForexGui.SLOT_CAT_ALL, ForexGui.SLOT_CAT_SEARCH, GuiItems.SLOT_BACK_TO_MENU);

        assertNoDuplicates(slots, "ForexGui 板块页");
    }

    @Test
    void forexCategoryPageExposesBothIndependentSections() {
        // 需求：国家外汇与虚拟货币必须是两个独立入口
        assertTrue(ForexGui.SLOT_CAT_FIAT != ForexGui.SLOT_CAT_CRYPTO,
                "国家外汇与虚拟货币不能共用槽位");
        assertTrue(ForexGui.SLOT_CAT_SEARCH >= 0);
    }

    @Test
    void forexMarketSlotsDoNotOverlapSymbolArea() {
        List<Integer> slots = List.of(
                ForexGui.SLOT_PREV_PAGE, ForexGui.SLOT_NEXT_PAGE,
                ForexGui.SLOT_REFRESH, ForexGui.SLOT_SEARCH, ForexGui.SLOT_CLEAR_SEARCH,
                GuiItems.SLOT_BACK_TO_MENU);

        assertNoDuplicates(slots, "ForexGui 行情页");
        for (int slot : slots) {
            assertTrue(slot >= ForexGui.SYMBOL_SLOTS,
                    "翻页 / 刷新 / 搜索 / 返回必须避开品种区（0.."
                            + (ForexGui.SYMBOL_SLOTS - 1) + "）");
        }
    }

    @Test
    void forexTradeSlotsDoNotOverlap() {
        List<Integer> slots = List.of(
                ForexGui.SLOT_BUY, ForexGui.SLOT_SELL, ForexGui.SLOT_QUANTITY,
                ForexGui.SLOT_CHART, ForexGui.SLOT_BACK_TO_MARKET,
                GuiItems.SLOT_BACK_TO_MENU);

        assertNoDuplicates(slots, "ForexGui 交易页");
    }

    @Test
    void futuresSlotsDoNotOverlapContractOrPositionAreas() {
        List<Integer> slots = List.of(
                FuturesGui.SLOT_SELECTED_INFO, FuturesGui.SLOT_OPEN_LONG,
                FuturesGui.SLOT_OPEN_SHORT, FuturesGui.SLOT_QUANTITY,
                FuturesGui.SLOT_CHART, GuiItems.SLOT_BACK_TO_MENU);

        assertNoDuplicates(slots, "FuturesGui");

        // 选中合约的信息不能占用合约选择区（0..8），否则会盖掉一个合约图标。
        assertTrue(FuturesGui.SLOT_SELECTED_INFO >= FuturesGui.CONTRACT_SLOTS,
                "合约信息槽必须避开合约区");

        // 图表按钮不能落进持仓列表区（36..44），否则点击会被当成"平仓"。
        assertFalse(FuturesGui.SLOT_CHART >= FuturesGui.POSITION_START
                        && FuturesGui.SLOT_CHART < FuturesGui.POSITION_START + FuturesGui.POSITION_SLOTS,
                "图表按钮不能与持仓项抢槽位");
    }

    @Test
    void mainMenuSlotsAreDistinctAndInRange() {
        List<Integer> slots = List.of(
                GuiItems.MENU_PORTFOLIO_HUB, GuiItems.MENU_LEVERAGE, GuiItems.MENU_FOREX,
                GuiItems.MENU_FUTURES, GuiItems.MENU_HELP,
                GuiItems.MENU_SIMULATION, GuiItems.MENU_SIMULATION_ADMIN,
                GuiItems.SLOT_CLOSE);

        assertNoDuplicates(slots, "MainMenuGui");
    }

    /**
     * 主菜单只做入口，不再承载持仓明细。
     *
     * <p>持仓已迁到独立的 {@link PortfolioHubGui}，因此主菜单不需要持仓区常量，
     * 也不该再有任何"持仓概览区"。
     */
    @Test
    void mainMenuOnlyHostsEntryButtons() {
        List<Integer> menuSlots = List.of(
                GuiItems.MENU_PORTFOLIO_HUB, GuiItems.MENU_LEVERAGE, GuiItems.MENU_FOREX,
                GuiItems.MENU_FUTURES, GuiItems.MENU_HELP,
                GuiItems.MENU_SIMULATION, GuiItems.MENU_SIMULATION_ADMIN,
                GuiItems.SLOT_CLOSE);

        for (int slot : menuSlots) {
            assertTrue(slot >= 0 && slot < GUI_SIZE, "主菜单槽位越界: " + slot);
        }
        // 入口按钮之间留出间隔，避免误点（pg 入口已移除，新增模拟盘与模拟盘管理，共 8 个）
        assertEquals(8, new HashSet<>(menuSlots).size());
    }

    @Test
    void portfolioHubSectionsDoNotOverlap() {
        List<Integer> slots = List.of(
                PortfolioHubGui.SLOT_SUMMARY, PortfolioHubGui.SLOT_PREV,
                PortfolioHubGui.SLOT_REFRESH, PortfolioHubGui.SLOT_NEXT,
                GuiItems.SLOT_BACK_TO_MENU);

        assertNoDuplicates(slots, "PortfolioHubGui 固定槽位");

        // 固定槽位不能落进股票区或保证金/期货区
        for (int slot : slots) {
            assertFalse(slot >= PortfolioHubGui.STOCK_START
                            && slot < PortfolioHubGui.STOCK_END,
                    "固定槽位 " + slot + " 落在股票区内，会被持仓条目覆盖");
            assertFalse(slot >= PortfolioHubGui.RISK_START
                            && slot < PortfolioHubGui.RISK_END,
                    "固定槽位 " + slot + " 落在保证金/期货区内，会被持仓条目覆盖");
        }
    }

    @Test
    void portfolioHubSectionsFitInsideChest() {
        assertTrue(PortfolioHubGui.STOCK_END <= PortfolioHubGui.RISK_START,
                "股票区必须在保证金区之前且不重叠");
        assertTrue(PortfolioHubGui.RISK_END <= GUI_SIZE, "保证金/期货区越界");

        int stockCapacity = PortfolioHubGui.STOCK_END - PortfolioHubGui.STOCK_START;
        int riskCapacity = PortfolioHubGui.RISK_END - PortfolioHubGui.RISK_START;
        assertTrue(stockCapacity >= 9, "股票区至少放得下一行，实际 " + stockCapacity);
        assertTrue(riskCapacity >= 9, "保证金/期货区至少放得下一行，实际 " + riskCapacity);
    }

    /** 分页计算：0 条也要算 1 页，避免"第 1/0 页"。 */
    @Test
    void pageCountHandlesEmptyAndExactBoundaries() {
        assertEquals(0, PortfolioHubGui.ceilDiv(0, 27));
        assertEquals(1, PortfolioHubGui.ceilDiv(1, 27));
        assertEquals(1, PortfolioHubGui.ceilDiv(27, 27));
        assertEquals(2, PortfolioHubGui.ceilDiv(28, 27));
        assertEquals(2, PortfolioHubGui.ceilDiv(54, 27));
        assertEquals(3, PortfolioHubGui.ceilDiv(55, 27));
    }

    /** 概览条目的槽位路由：点击必须能反查到目标，不能只靠解析物品名。 */
    @Test
    void portfolioHubSlotTargetsRouteToTheRightPositionKind() {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.PORTFOLIO_HUB);

        holder.putStockTarget(0, 42L, "600519");
        holder.putMarginTarget(27, 7L);
        holder.putFuturesTarget(28, 99L);

        GuiHolder.Target stock = holder.targetAt(0);
        assertEquals("STOCK", stock.kind());
        assertEquals(42L, stock.id());
        assertEquals("600519", stock.code());

        GuiHolder.Target margin = holder.targetAt(27);
        assertEquals("MARGIN", margin.kind());
        assertEquals(7L, margin.id());

        GuiHolder.Target futures = holder.targetAt(28);
        assertEquals("FUTURES", futures.kind());
        assertEquals(99L, futures.id());

        // 非持仓槽位返回 null，固定按钮据此走自己的分支
        assertNull(holder.targetAt(PortfolioHubGui.SLOT_SUMMARY));
        assertTrue(holder.hasTargets());
    }

    /** 重新渲染前必须清空路由，否则旧 ID 会残留在同一槽位上。 */
    @Test
    void resetTargetsClearsStaleRouting() {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.PORTFOLIO_HUB);
        holder.putStockTarget(0, 1L, "600519");
        holder.overflow(5);

        holder.resetTargets();

        assertNull(holder.targetAt(0));
        assertFalse(holder.hasTargets());
        assertEquals(0, holder.getOverflow(), "溢出计数也要一并清零");
    }

    /** 溢出计数不能为负（持仓数少于容量时不该出现"-3 笔未显示"）。 */
    @Test
    void overflowIsNeverNegative() {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.PORTFOLIO_HUB);
        holder.overflow(-3);
        assertEquals(0, holder.getOverflow());
    }

    @Test
    void everySlotFitsInsideA54SlotChest() {
        List<Integer> slots = new ArrayList<>(List.of(
                LeverageGui.SLOT_INFO, LeverageGui.SLOT_RESET,
                LeverageGui.SLOT_VIEW_FOREX, LeverageGui.SLOT_VIEW_STOCK,
                ForexGui.SLOT_PREV_PAGE, ForexGui.SLOT_NEXT_PAGE, ForexGui.SLOT_REFRESH,
                ForexGui.SLOT_BUY, ForexGui.SLOT_SELL, ForexGui.SLOT_QUANTITY,
                ForexGui.SLOT_CHART, ForexGui.SLOT_BACK_TO_MARKET,
                FuturesGui.SLOT_SELECTED_INFO, FuturesGui.SLOT_OPEN_LONG,
                FuturesGui.SLOT_OPEN_SHORT, FuturesGui.SLOT_QUANTITY, FuturesGui.SLOT_CHART,
                FuturesGui.POSITION_START, GuiItems.MENU_PORTFOLIO_HUB, GuiItems.MENU_LEVERAGE,
                GuiItems.MENU_FOREX, GuiItems.MENU_FUTURES,
                GuiItems.MENU_HELP, GuiItems.SLOT_BACK_TO_MENU, GuiItems.SLOT_CLOSE,
                GuiItems.MENU_SIMULATION, GuiItems.MENU_SIMULATION_ADMIN,
                PortfolioHubGui.SLOT_SUMMARY, PortfolioHubGui.SLOT_PREV,
                PortfolioHubGui.SLOT_REFRESH, PortfolioHubGui.SLOT_NEXT));
        for (int slot : LeverageGui.ADJUST_SLOTS) slots.add(slot);
        slots.add(SimulationGui.SLOT_PREV_PAGE);
        slots.add(SimulationGui.SLOT_REFRESH);
        slots.add(SimulationGui.SLOT_NEXT_PAGE);
        slots.add(SimulationGui.SLOT_INFO);
        slots.add(SimulationGui.SLOT_TRADE_INFO);
        slots.add(SimulationGui.SLOT_BUY);
        slots.add(SimulationGui.SLOT_QUANTITY);
        slots.add(SimulationGui.SLOT_SELL);
        slots.add(SimulationGui.SLOT_CHART);
        slots.add(SimulationGui.SLOT_BACK_TO_LIST);
        slots.add(SimulationAdminGui.SLOT_INFO);
        slots.add(SimulationAdminGui.SLOT_FREEZE_DOWN);
        slots.add(SimulationAdminGui.SLOT_FREEZE_UP);
        slots.add(SimulationAdminGui.SLOT_PAUSE);
        slots.add(SimulationAdminGui.SLOT_SUSPEND);
        slots.add(SimulationAdminGui.SLOT_TRADABLE);
        slots.add(SimulationAdminGui.SLOT_BOUNDARY);
        slots.add(SimulationAdminGui.SLOT_PRESET);
        slots.add(SimulationAdminGui.SLOT_RESUME);
        slots.add(SimulationAdminGui.SLOT_RESET);
        slots.add(SimulationAdminGui.SLOT_SEED);
        slots.add(SimulationAdminGui.SLOT_HELP);
        slots.add(SimulationAdminGui.SLOT_PREV_PAGE);
        slots.add(SimulationAdminGui.SLOT_REFRESH);
        slots.add(SimulationAdminGui.SLOT_NEXT_PAGE);

        for (int slot : slots) {
            assertTrue(slot >= 0 && slot < GUI_SIZE,
                    "槽位越界: " + slot + "（箱子只有 " + GUI_SIZE + " 格）");
        }
        assertTrue(FuturesGui.POSITION_START + FuturesGui.POSITION_SLOTS <= GUI_SIZE,
                "持仓列表超出箱子范围");
        assertTrue(ForexGui.SYMBOL_SLOTS <= GUI_SIZE, "品种区超出箱子范围");
        assertTrue(SimulationGui.LIST_SLOTS <= GUI_SIZE, "模拟盘品种区超出箱子范围");
        assertTrue(SimulationAdminGui.SELECT_START + SimulationAdminGui.SELECT_SLOTS <= GUI_SIZE,
                "模拟盘选股区超出箱子范围");
    }

    /** 新增 GUI 类型时必须同步更新监听器的分发分支。 */
    @Test
    void tradingGuiTypeMetadataIsStable() {
        assertEquals(11, GuiHolder.GuiType.values().length,
                "新增 GUI 类型时请同步更新 TradingGuiListener 的分发逻辑");
        assertTrue(java.util.Arrays.asList(GuiHolder.GuiType.values())
                .contains(GuiHolder.GuiType.FOREX_CATEGORY));
        // 持仓总览必须是独立类型，否则点击分发无法与主菜单区分
        assertTrue(java.util.Arrays.asList(GuiHolder.GuiType.values())
                .contains(GuiHolder.GuiType.PORTFOLIO_HUB));
        // 持仓操作子菜单必须是独立类型，否则持仓条目左键无法与"直接跳详情"区分
        assertTrue(java.util.Arrays.asList(GuiHolder.GuiType.values())
                .contains(GuiHolder.GuiType.POSITION_MENU));
        // 模拟盘三件套：列表页 / 交易页 / 管理台
        assertTrue(java.util.Arrays.asList(GuiHolder.GuiType.values())
                .contains(GuiHolder.GuiType.SIMULATION));
        assertTrue(java.util.Arrays.asList(GuiHolder.GuiType.values())
                .contains(GuiHolder.GuiType.SIMULATION_TRADE));
        assertTrue(java.util.Arrays.asList(GuiHolder.GuiType.values())
                .contains(GuiHolder.GuiType.SIMULATION_ADMIN));
    }

    /**
     * 持仓操作子菜单：固定槽互不相同、都落在 27 格内，
     * 且"返回"不占用任何动作槽。
     */
    @Test
    void positionMenuSlotsDoNotOverlapAndFitInsideItsChest() {
        assertEquals(27, PositionMenuGui.SIZE, "子菜单固定 27 格（3 行）");
        assertEquals(0, PositionMenuGui.SIZE % 9, "箱子格数必须是 9 的倍数");

        List<Integer> slots = List.of(PositionMenuGui.SLOT_HOLDING, PositionMenuGui.SLOT_SELL,
                PositionMenuGui.SLOT_CHART, PositionMenuGui.SLOT_DETAIL, PositionMenuGui.SLOT_BACK);
        assertNoDuplicates(slots, "PositionMenuGui");
        for (int slot : slots) {
            assertTrue(slot >= 0 && slot < PositionMenuGui.SIZE, "子菜单槽位越界: " + slot);
        }

        // 三个动作槽必须各占一格：挤在一起会让"卖"和"看K线"互相误触
        Set<Integer> actions = Set.of(PositionMenuGui.SLOT_SELL, PositionMenuGui.SLOT_CHART,
                PositionMenuGui.SLOT_DETAIL);
        assertEquals(3, actions.size(), "卖出 / 查看K线 / 详情 必须落在不同槽位");
        assertFalse(actions.contains(PositionMenuGui.SLOT_HOLDING), "动作槽不得盖住持仓信息条");
        assertFalse(actions.contains(PositionMenuGui.SLOT_BACK), "动作槽不得盖住返回按钮");
    }

    /** 子菜单的 27 格建箱不能顺手退回 54 格（否则会空出一整半）。 */
    @Test
    void chestSizeHonoursRequestedRowCount() {
        assertEquals(27, GuiItems.chestSize(PositionMenuGui.ROWS), "3 行 = 27 格");
        assertEquals(PositionMenuGui.SIZE, GuiItems.chestSize(PositionMenuGui.ROWS),
                "SIZE 常量与 ROWS 必须自洽");
        assertEquals(54, GuiItems.chestSize(6), "6 行 = 54 格（既有界面的默认）");
        // 越界行数被夹到 1..6，永不做成非 9 倍数的箱子
        assertEquals(9, GuiItems.chestSize(0));
        assertEquals(9, GuiItems.chestSize(-3));
        assertEquals(54, GuiItems.chestSize(99));
    }

    // ── 模拟盘 GUI（SimulationGui / SimulationAdminGui）─────────────────────

    /**
     * 列表页：45 格品种区（0..44）与 45..49 的固定按钮互斥。
     *
     * <p>信息槽必须 {@code >= LIST_SLOTS} —— 显式防住 {@code ForexGui} 的
     * "4 号槽被信息面板覆盖"同类 bug（第 5 个品种会被盖住）。
     */
    @Test
    void simulationGuiSlotsDoNotOverlap() {
        assertEquals(45, SimulationGui.LIST_SLOTS, "列表页品种区固定为 45 格（0..44）");
        assertTrue(SimulationGui.SLOT_INFO >= SimulationGui.LIST_SLOTS,
                "信息槽必须避开列表区，否则会盖住一个品种");
        List<Integer> market = List.of(SimulationGui.SLOT_PREV_PAGE, SimulationGui.SLOT_REFRESH,
                SimulationGui.SLOT_NEXT_PAGE, SimulationGui.SLOT_INFO, GuiItems.SLOT_BACK_TO_MENU);
        assertNoDuplicates(market, "SimulationGui 列表页");
        for (int slot : market) {
            assertTrue(slot >= SimulationGui.LIST_SLOTS, "固定按钮不能落进列表区 0..44");
            assertTrue(slot < GUI_SIZE, "槽位越界: " + slot);
        }
    }

    /** 交易页：4/20/22/24/40/45/49 互不相同，且都落在 54 格内。 */
    @Test
    void simulationTradeSlotsDoNotOverlap() {
        List<Integer> trade = List.of(SimulationGui.SLOT_TRADE_INFO, SimulationGui.SLOT_BUY,
                SimulationGui.SLOT_QUANTITY, SimulationGui.SLOT_SELL, SimulationGui.SLOT_CHART,
                SimulationGui.SLOT_BACK_TO_LIST, GuiItems.SLOT_BACK_TO_MENU);
        assertNoDuplicates(trade, "SimulationGui 交易页");
        for (int slot : trade) {
            assertTrue(slot >= 0 && slot < GUI_SIZE, "交易页槽位越界: " + slot);
        }
    }

    /** 管理台：固定槽全部避开选股区 0..8，且互不相同、都在 54 格内。 */
    @Test
    void simulationAdminSlotsAvoidSelectionArea() {
        List<Integer> fixed = List.of(SimulationAdminGui.SLOT_INFO, SimulationAdminGui.SLOT_FREEZE_DOWN,
                SimulationAdminGui.SLOT_FREEZE_UP, SimulationAdminGui.SLOT_PAUSE,
                SimulationAdminGui.SLOT_SUSPEND, SimulationAdminGui.SLOT_TRADABLE,
                SimulationAdminGui.SLOT_BOUNDARY, SimulationAdminGui.SLOT_PRESET,
                SimulationAdminGui.SLOT_RESUME, SimulationAdminGui.SLOT_RESET,
                SimulationAdminGui.SLOT_SEED, SimulationAdminGui.SLOT_HELP,
                SimulationAdminGui.SLOT_PREV_PAGE, SimulationAdminGui.SLOT_REFRESH,
                SimulationAdminGui.SLOT_NEXT_PAGE, GuiItems.SLOT_BACK_TO_MENU);
        assertNoDuplicates(fixed, "SimulationAdminGui");
        for (int slot : fixed) {
            assertTrue(slot >= SimulationAdminGui.SELECT_SLOTS, "管理台固定槽不能落进选股区 0..8");
            assertTrue(slot < GUI_SIZE, "槽位越界: " + slot);
        }
    }

    /** 管理台选股区与分页按钮都在箱子里，且选股区正好 9 格（每页 9 只）。 */
    @Test
    void simulationAdminSlotsFitInsideChest() {
        assertEquals(9, SimulationAdminGui.SELECT_SLOTS, "选股区固定为 9 只");
        assertEquals(0, SimulationAdminGui.SELECT_START);
        assertTrue(SimulationAdminGui.SELECT_START + SimulationAdminGui.SELECT_SLOTS
                <= SimulationAdminGui.SLOT_INFO, "选股区不能覆盖信息槽");
        for (int slot : List.of(SimulationAdminGui.SLOT_INFO, SimulationAdminGui.SLOT_FREEZE_DOWN,
                SimulationAdminGui.SLOT_FREEZE_UP, SimulationAdminGui.SLOT_PAUSE,
                SimulationAdminGui.SLOT_SUSPEND, SimulationAdminGui.SLOT_TRADABLE,
                SimulationAdminGui.SLOT_BOUNDARY, SimulationAdminGui.SLOT_PRESET,
                SimulationAdminGui.SLOT_RESUME, SimulationAdminGui.SLOT_RESET,
                SimulationAdminGui.SLOT_SEED, SimulationAdminGui.SLOT_HELP,
                SimulationAdminGui.SLOT_PREV_PAGE, SimulationAdminGui.SLOT_REFRESH,
                SimulationAdminGui.SLOT_NEXT_PAGE, GuiItems.SLOT_BACK_TO_MENU)) {
            assertTrue(slot >= 0 && slot < GUI_SIZE, "槽位越界: " + slot);
        }
    }

    /** 三个新 GUI 类型都必须在 values() 中（防止只改计数漏加枚举）。 */
    @Test
    void guiTypeCountIncludesSimulationTypes() {
        List<GuiHolder.GuiType> types = java.util.Arrays.asList(GuiHolder.GuiType.values());
        assertTrue(types.contains(GuiHolder.GuiType.SIMULATION));
        assertTrue(types.contains(GuiHolder.GuiType.SIMULATION_TRADE));
        assertTrue(types.contains(GuiHolder.GuiType.SIMULATION_ADMIN));
    }

    /** 模拟盘分页：0 条也算 1 页；每页 9 只的边界逐个断言。 */
    @Test
    void simulationPageCountHandlesEmptyAndExactBoundaries() {
        assertEquals(1, SimulationGui.pageCount(0, SimulationGui.LIST_SLOTS));
        assertEquals(1, SimulationGui.pageCount(1, SimulationGui.LIST_SLOTS));
        assertEquals(1, SimulationGui.pageCount(45, SimulationGui.LIST_SLOTS));
        assertEquals(2, SimulationGui.pageCount(46, SimulationGui.LIST_SLOTS));

        assertEquals(1, SimulationGui.pageCount(0, SimulationAdminGui.SELECT_SLOTS));
        assertEquals(1, SimulationGui.pageCount(9, SimulationAdminGui.SELECT_SLOTS));
        assertEquals(2, SimulationGui.pageCount(10, SimulationAdminGui.SELECT_SLOTS));
        assertEquals(2, SimulationGui.pageCount(18, SimulationAdminGui.SELECT_SLOTS));
        assertEquals(3, SimulationGui.pageCount(19, SimulationAdminGui.SELECT_SLOTS));

        // 非法入参不得返回 0 或负数（否则会渲染出"第 1/0 页"）
        assertEquals(1, SimulationGui.pageCount(-5, 9));
        assertEquals(1, SimulationGui.pageCount(10, 0));
    }

    private static void assertNoDuplicates(List<Integer> slots, String gui) {
        Set<Integer> unique = new HashSet<>(slots);
        assertEquals(slots.size(), unique.size(),
                gui + " 存在重复槽位，后写入的物品会覆盖按钮: " + slots);
    }

    private static boolean contains(int[] array, int value) {
        for (int candidate : array) {
            if (candidate == value) return true;
        }
        return false;
    }
}
