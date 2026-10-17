package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.model.ForexCategory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 交易类 GUI 的持有者标识。
 *
 * <p>用 {@link InventoryHolder} 而不是静态 UUID 集合来识别界面：
 * 玩家断线、界面被顶替、插件热重载都不会留下悬挂状态，
 * 点击事件也能直接读到界面上下文（当前页、选中品种、当前杠杆等）。
 */
public class GuiHolder implements InventoryHolder {

    /** GUI 种类。 */
    public enum GuiType {
        MAIN_MENU,
        /** 持仓总览：与主菜单分离的独立界面。 */
        PORTFOLIO_HUB,
        LEVERAGE,
        /** 板块选择页：国家外汇 / 虚拟货币 / 搜索。 */
        FOREX_CATEGORY,
        FOREX_MARKET,
        FOREX_TRADE,
        FUTURES,
        /** 模拟盘行情列表页。 */
        SIMULATION,
        /** 模拟盘交易页。 */
        SIMULATION_TRADE,
        /** 模拟盘管理控制台（仅管理员）。 */
        SIMULATION_ADMIN,
        /**
         * 单个股票持仓的操作子菜单（持仓总览左键进入）。
         *
         * <p>把"卖出 / 查看K线 / 详情"摆成显式按钮，见 {@link PositionMenuGui}。
         */
        POSITION_MENU
    }

    private final GuiType type;
    private Inventory inventory;

    /**
     * 主菜单持仓概览条目：槽位 → 路由目标。
     *
     * <p>点击时直接查这张表拿目标，不去解析物品名 —— 物品名是给人看的，
     * 随时可能为排版调整，用它当路由键太脆弱（期货界面已踩过这个坑）。
     */
    private final java.util.Map<Integer, Target> slotTargets = new java.util.HashMap<>();
    /** 主菜单持仓概览的未显示条目数。 */
    private int overflow;

    /**
     * 概览条目的路由目标。
     *
     * @param kind  {@code STOCK} / {@code FUTURES} / {@code MARGIN}
     * @param id    持仓主键（股票持仓即 holdings.id）
     * @param code  股票代码；非股票为 {@code null}
     */
    public record Target(String kind, long id, String code) {
        /** 股票现金持仓。 */
        public static final String KIND_STOCK = "STOCK";
        /** 期货持仓。 */
        public static final String KIND_FUTURES = "FUTURES";
        /** 保证金 / 外汇持仓。 */
        public static final String KIND_MARGIN = "MARGIN";

        /**
         * 模拟盘持仓。{@code id} 恒为 0，{@code code} 是模拟代码 ——
         * 模拟持仓在库里以 (player_uuid, code) 为主键，没有自增主键可用。
         */
        public static final String KIND_SIM = "SIM";
    }

    // 上下文：按界面类型使用
    private int page;
    private String symbol;
    private double quantity = 1.0;
    /** 行情页当前板块；{@code null} 表示不限板块。 */
    private ForexCategory category;
    /** 行情页当前搜索关键词；空白表示未搜索。 */
    private String keyword = "";
    /**
     * 杠杆界面当前制度：{@code true} = 股票，{@code false} = 外汇。
     *
     * <p>界面上有切换按钮，减 / 加 / 重置都必须作用在<b>当前显示</b>的那一套杠杆上，
     * 否则界面显示股票杠杆、点击却在改外汇杠杆。
     */
    private boolean stockLeverageView;

    /**
     * 子菜单正在操作的持仓（仅 {@link GuiType#POSITION_MENU} 使用）。
     *
     * <p>复用 {@link Target} 而不是另设 (id, symbol, kind) 三件套：子菜单要的
     * 信息与概览条目要的完全相同，"这笔持仓是谁"只能有一个答案。
     */
    private Target focusTarget;

    public Target getFocusTarget() {
        return focusTarget;
    }

    public GuiHolder focusTarget(Target target) {
        this.focusTarget = target;
        return this;
    }

    public GuiHolder(GuiType type) {
        this.type = type;
    }

    public GuiType getType() {
        return type;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("GUI 尚未绑定 Inventory: " + type);
        }
        return inventory;
    }

    /** 是否已绑定界面的实际实例（用于判断刷新目标是否仍然打开）。 */
    public boolean isBoundTo(Inventory candidate) {
        return inventory != null && inventory == candidate;
    }

    public int getPage() {
        return page;
    }

    public GuiHolder page(int page) {
        this.page = page;
        return this;
    }

    public String getSymbol() {
        return symbol;
    }

    public GuiHolder symbol(String symbol) {
        this.symbol = symbol;
        return this;
    }

    public double getQuantity() {
        return quantity;
    }

    public GuiHolder quantity(double quantity) {
        this.quantity = quantity > 0 ? quantity : 1.0;
        return this;
    }

    /**
     * 数量上下限：纯防御性兜底，Trade 类 Service 本来就会拒绝非正数与非有限值
     * （{@code ForexService} / {@code FuturesService} / {@code LeverageService} /
     * {@code SimulationService} 各自都有一道 {@code trade-invalid-quantity} 校验）。
     *
     * <p>这里再夹一道的原因是 GUI 的加减按钮可以连续点击，没有上限的话
     * 一次误触 Shift 左键就会把数量顶到 {@link #MAX_QUANTITY} 之外的离谱值，
     * 虽然最终会被服务层拒绝，但玩家看到的是"点了没反应"。
     */
    public static final double MIN_QUANTITY = 1.0;
    public static final double MAX_QUANTITY = 1_000_000.0;

    /**
     * 按点击方式调整数量（GUI「数量」按钮的共享实现）。
     *
     * <p>步长：左键 {@code +1}、右键 {@code -1}、Shift+左键 {@code +10}、
     * Shift+右键 {@code -10}；其余点击方式不做调整并返回 {@code false}，
     * 由调用方回落到聊天输入通道——这条兜底是刻意保留的，
     * 聊天输入能表达任意小数，按钮只能表达整数步长。
     *
     * @return 是否真的改动了数量
     */
    public boolean adjustQuantity(boolean left, boolean right, boolean shift) {
        double step;
        if (left) {
            step = shift ? 10.0 : 1.0;
        } else if (right) {
            step = shift ? -10.0 : -1.0;
        } else {
            return false;
        }
        double next = quantity + step;
        if (next < MIN_QUANTITY) next = MIN_QUANTITY;
        if (next > MAX_QUANTITY) next = MAX_QUANTITY;
        this.quantity = next;
        return true;
    }

    /** 行情页当前板块；{@code null} 表示"全部板块"或尚未选择。 */
    public ForexCategory getCategory() {
        return category;
    }

    public GuiHolder category(ForexCategory category) {
        this.category = category;
        return this;
    }

    /** 行情页当前搜索关键词；空白表示未搜索。 */
    public String getKeyword() {
        return keyword;
    }

    public GuiHolder keyword(String keyword) {
        this.keyword = keyword == null ? "" : keyword.strip();
        return this;
    }

    /** 杠杆界面当前是否在看股票杠杆（否则为外汇杠杆）。 */
    public boolean isStockLeverageView() {
        return stockLeverageView;
    }

    public GuiHolder stockLeverageView(boolean stockLeverageView) {
        this.stockLeverageView = stockLeverageView;
        return this;
    }

    /** 是否处于"搜索结果"视图。 */
    public boolean isSearching() {
        return !keyword.isBlank();
    }

    // ── 主菜单持仓概览 ────────────────────────────────────────────────────────

    /** 登记一个可点击的持仓槽位。 */
    public GuiHolder putTarget(int slot, Target target) {
        slotTargets.put(slot, target);
        return this;
    }

    /** 清空槽位路由（重新渲染前调用，避免残留旧条目的目标）。 */
    public GuiHolder resetTargets() {
        slotTargets.clear();
        overflow = 0;
        return this;
    }

    /** 该槽位对应的路由目标；不是持仓槽位时返回 {@code null}。 */
    public Target targetAt(int slot) {
        return slotTargets.get(slot);
    }

    public boolean hasTargets() {
        return !slotTargets.isEmpty();
    }

    /** 便捷登记：股票持仓。 */
    public GuiHolder putStockTarget(int slot, long id, String code) {
        return putTarget(slot, new Target(Target.KIND_STOCK, id, code));
    }

    /** 便捷登记：保证金持仓。 */
    public GuiHolder putMarginTarget(int slot, long id) {
        return putTarget(slot, new Target(Target.KIND_MARGIN, id, null));
    }

    /**
     * 便捷登记：带品种代码的保证金持仓（外汇 / 贵金属 / 加密货币 / 指数）。
     *
     * <p>带上 {@code symbol} 才能从持仓总览直接进操作子菜单看 K 线 ——
     * 保证金条目只知道主键是不够的，图表要的是品种。
     */
    public GuiHolder putMarginTarget(int slot, long id, String symbol) {
        return putTarget(slot, new Target(Target.KIND_MARGIN, id, symbol));
    }

    /** 便捷登记：期货持仓。 */
    public GuiHolder putFuturesTarget(int slot, long id) {
        return putTarget(slot, new Target(Target.KIND_FUTURES, id, null));
    }

    /** 便捷登记：模拟盘持仓（按模拟代码定位，无自增主键）。 */
    public GuiHolder putSimulationTarget(int slot, String code) {
        return putTarget(slot, new Target(Target.KIND_SIM, 0L, code));
    }

    /** 未能显示出来的持仓数量（超出概览区容量时 > 0）。 */
    public int getOverflow() {
        return overflow;
    }

    public GuiHolder overflow(int overflow) {
        this.overflow = Math.max(0, overflow);
        return this;
    }
}
