package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.simulation.SimulationService;
import dev.onelili.mstock.simulation.SimulationStock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟盘界面，分两级。
 *
 * <ol>
 *   <li><b>行情列表页</b>：45 格品种区（{@code 0..44}），每格显示代码 / 名称 / 板块 /
 *       现价 / 涨跌幅（涨绿跌红，停牌置灰并标注），支持翻页 / 刷新。</li>
 *   <li><b>交易页</b>：买入 / 卖出 / 设置数量 / 查看 K 线，并记住来源页码便于返回列表。</li>
 * </ol>
 *
 * <p><b>槽位为什么这样切</b>：{@code ForexGui} 的行情页把信息面板写在 4 号槽，
 * 而 4 号槽同时被 45 格品种区覆盖（{@code ForexGui.java:236-270}）——
 * 第 5 个品种会被信息面板盖住。模拟盘把<b>全部固定按钮</b>放在
 * {@code 45..49}（{@code >= }{@link #LIST_SLOTS}），从结构上消除同类覆盖，
 * 并由 {@code GuiLayoutTest.simulationGuiSlotsDoNotOverlap} 立测。
 *
 * <p><b>本类只读</b>：{@link org.bukkit.event.inventory.InventoryClickEvent} 由
 * {@code TradingGuiListener} 统一取消并分发；这里只负责渲染与暴露槽位常量，
 * 不持有任何业务规则。
 */
public class SimulationGui {

    public static final String LIST_TITLE = "模拟盘行情";
    public static final String TRADE_TITLE = "模拟盘交易";

    // ── 行情列表页 ────────────────────────────────────────────────────────────
    /** 品种占 {@code 0..44}。 */
    public static final int LIST_SLOTS = 45;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_REFRESH = 46;
    public static final int SLOT_NEXT_PAGE = 47;
    /** 信息面板：必须 {@code >= LIST_SLOTS}，否则会盖住一个品种。 */
    public static final int SLOT_INFO = 48;
    // 49 = GuiItems.SLOT_BACK_TO_MENU

    // ── 交易页 ────────────────────────────────────────────────────────────────
    public static final int SLOT_TRADE_INFO = 4;
    public static final int SLOT_SHORT = 19;
    public static final int SLOT_BUY = 20;
    /**
     * 「设置数量」按钮。
     *
     * <p>点击由监听器复用既有聊天输入通道（与 {@code ForexGui} 完全同一条路径）：
     * <pre>{@code
     * command.startQuantityInput(player, holder.getSymbol(),
     *         PendingAction.Type.FOREX_BUY,
     *         quantity -> { holder.quantity(quantity); renderTrade(player, holder); });
     * }</pre>
     * <b>不新增 {@code PendingAction.Type} 值</b>：{@code FOREX_BUY} 已走小数分支。
     * 该回调由 {@code TradingGuiListener}（接线切片）持有，故本类不引用 {@code MStockCommand}。
     */
    public static final int SLOT_QUANTITY = 22;
    public static final int SLOT_SELL = 24;
    public static final int SLOT_COVER = 25;
    public static final int SLOT_CHART = 40;
    public static final int SLOT_BACK_TO_LIST = 45;
    // 49 = GuiItems.SLOT_BACK_TO_MENU

    private final MineStock plugin;

    public SimulationGui(MineStock plugin) {
        this.plugin = plugin;
    }

    // ── 行情列表页 ────────────────────────────────────────────────────────────

    /** 打开行情列表页。 */
    public void open(Player player, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.SIMULATION).page(page);
        Inventory inventory = GuiItems.create(holder, LIST_TITLE, NamedTextColor.GOLD);
        renderList(holder);
        player.openInventory(inventory);
    }

    /** 重新渲染当前页（翻页 / 刷新共用；交易页也走这里，避免调用方分叉）。 */
    public void reload(Player player, GuiHolder holder) {
        if (holder == null) return;
        if (holder.getType() == GuiHolder.GuiType.SIMULATION_TRADE) {
            renderTrade(player, holder);
            return;
        }
        renderList(holder);
    }

    /** 列表页纯渲染：品种区 {@code 0..44} + 固定按钮 {@code 45..49}。 */
    private void renderList(GuiHolder holder) {
        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < LIST_SLOTS; slot++) inventory.setItem(slot, null);
        SimulationService service = simulationService(plugin);
        List<SimulationStock> stocks = service == null ? List.of() : service.stocks();

        int perPage = LIST_SLOTS;
        int pages = pageCount(stocks.size(), perPage);
        int safePage = Math.max(0, Math.min(pages - 1, holder.getPage()));
        int start = safePage * perPage;

        for (int i = 0; i < perPage && start + i < stocks.size(); i++) {
            inventory.setItem(i, buildStockItem(stocks.get(start + i)));
        }

        List<Component> infoLore = new ArrayList<>();
        infoLore.add(GuiItems.line("在册品种", stocks.size() + " 只", NamedTextColor.WHITE));
        infoLore.add(GuiItems.line("页码", (safePage + 1) + " / " + pages, NamedTextColor.WHITE));
        infoLore.add(GuiItems.line("每页", perPage + " 只", NamedTextColor.GRAY));
        if (service == null) {
            infoLore.add(GuiItems.line("状态", "模拟盘未就绪", NamedTextColor.RED));
        } else if (!service.isEnabled()) {
            infoLore.add(GuiItems.line("状态", "已关闭（simulation.enabled: false）",
                    NamedTextColor.RED));
        } else {
            infoLore.add(GuiItems.line("推进周期", format2(service.tickSeconds()) + " 秒",
                    NamedTextColor.GRAY));
        }
        if (stocks.isEmpty()) infoLore.add(GuiItems.line("提示", "暂无模拟股", NamedTextColor.YELLOW));
        infoLore.add(GuiItems.label("点击品种进入交易页"));
        inventory.setItem(SLOT_INFO, GuiItems.info(Material.PAPER,
                Component.text(LIST_TITLE).color(NamedTextColor.GOLD), infoLore));

        if (safePage > 0) {
            inventory.setItem(SLOT_PREV_PAGE, GuiItems.button(Material.ARROW,
                    Component.text("上一页").color(NamedTextColor.YELLOW)));
        }
        if (safePage < pages - 1) {
            inventory.setItem(SLOT_NEXT_PAGE, GuiItems.button(Material.ARROW,
                    Component.text("下一页").color(NamedTextColor.YELLOW)));
        }
        inventory.setItem(SLOT_REFRESH, GuiItems.button(Material.ARROW,
                Component.text("刷新").color(NamedTextColor.GREEN),
                List.of(
                        GuiItems.label("重新读取模拟盘行情"),
                        GuiItems.label("等价于 /mstock simulation list " + (safePage + 1)))));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());

        holder.page(safePage);
        GuiItems.fillEmpty(inventory, LIST_SLOTS, inventory.getSize());
    }

    /**
     * 单个品种图标。
     *
     * <p><b>显示名恰好是代码</b>（与 {@code ForexGui} 的行情页一致）：点击分发靠解析
     * 显示名拿代码，名称 / 板块 / 现价 / 涨跌幅全部放 lore，改动排版不会影响路由。
     */
    private static ItemStack buildStockItem(SimulationStock stock) {
        boolean suspended = stock.isSuspended();
        boolean paused = stock.isPaused();
        boolean tradable = stock.isTradable();
        double change = stock.changePercent();
        boolean up = change >= 0;
        NamedTextColor trend = up ? NamedTextColor.GREEN : NamedTextColor.RED;
        // 停牌置灰：价格与涨跌幅都停在冻结值上，不该再按涨跌染色
        NamedTextColor color = suspended ? NamedTextColor.GRAY : trend;

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("名称", stock.getName(), NamedTextColor.WHITE));
        lore.add(GuiItems.line("板块", stock.getSector(), NamedTextColor.GRAY));
        lore.add(GuiItems.line("现价", format2(stock.getCurrentPrice()),
                suspended ? NamedTextColor.GRAY : NamedTextColor.WHITE));
        lore.add(GuiItems.line("涨跌幅", (up ? "+" : "") + format2(change) + "%", color));
        if (suspended) {
            lore.add(GuiItems.line("状态", "停牌", NamedTextColor.GRAY));
        } else if (!tradable) {
            lore.add(GuiItems.line("状态", "禁售", NamedTextColor.YELLOW));
        } else if (paused) {
            lore.add(GuiItems.line("状态", "暂停推进", NamedTextColor.YELLOW));
        }
        lore.add(Component.empty());
        lore.add(GuiItems.label("点击进入交易页"));

        return GuiItems.button(Material.PAPER, Component.text(stock.getCode()).color(color), lore);
    }

    // ── 交易页 ────────────────────────────────────────────────────────────────

    /** 打开交易页，并记住来源页码，便于"返回列表"回到原处。 */
    public void openTrade(Player player, String code, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.SIMULATION_TRADE)
                .symbol(code)
                .quantity(1.0)
                .page(page);
        Inventory inventory = GuiItems.create(holder, TRADE_TITLE + " - " + code,
                NamedTextColor.GOLD);
        renderTrade(player, holder);
        player.openInventory(inventory);
    }

    /**
     * 交易页纯渲染（数量变化后由指令层的数量回调再次调用）。
     *
     * <p>模拟盘行情是内存态，无需异步拉取：进页面时价格已经是最新的一档。
     */
    public void renderTrade(Player player, GuiHolder holder) {
        Inventory inventory = holder.getInventory();
        String code = holder.getSymbol();
        SimulationStock stock = findStock(code);
        double quantity = holder.getQuantity();

        boolean suspended = stock != null && stock.isSuspended();
        boolean tradable = stock == null || stock.isTradable();
        double change = stock == null ? 0.0 : stock.changePercent();
        boolean up = change >= 0;
        double price = stock == null ? 0.0 : stock.getCurrentPrice();

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.label(stock == null ? "代码未注册" : stock.getName()));
        lore.add(GuiItems.line("现价", stock == null ? "-" : format2(price),
                suspended ? NamedTextColor.GRAY : NamedTextColor.WHITE));
        lore.add(GuiItems.line("涨跌幅",
                stock == null ? "-" : (up ? "+" : "") + format2(change) + "%",
                suspended ? NamedTextColor.GRAY : (up ? NamedTextColor.GREEN : NamedTextColor.RED)));
        lore.add(GuiItems.line("数量", ForexGui.formatQty(quantity), NamedTextColor.WHITE));
        lore.add(GuiItems.line("名义价值", stock == null ? "-" : format2(price * quantity),
                NamedTextColor.WHITE));
        if (stock == null) {
            lore.add(GuiItems.line("状态", "模拟盘未就绪", NamedTextColor.RED));
        } else if (suspended) {
            lore.add(GuiItems.line("状态", "停牌 · 不可交易", NamedTextColor.YELLOW));
        } else if (!tradable) {
            lore.add(GuiItems.line("状态", "禁售 · 不可交易", NamedTextColor.YELLOW));
        }
        inventory.setItem(SLOT_TRADE_INFO, GuiItems.info(Material.PAPER,
                Component.text(code == null ? TRADE_TITLE : code)
                        .color(suspended ? NamedTextColor.GRAY : (up ? NamedTextColor.GREEN
                                : NamedTextColor.RED)),
                lore));

        inventory.setItem(SLOT_SHORT, GuiItems.button(Material.MAGENTA_DYE,
                Component.text("做空").color(NamedTextColor.LIGHT_PURPLE),
                List.of(
                        GuiItems.line("数量", ForexGui.formatQty(quantity), NamedTextColor.WHITE),
                        GuiItems.label("等价于 /mstock simulation short " + code + " "
                                + ForexGui.formatQty(quantity)),
                        GuiItems.label("做空走保证金：1 倍即全额保证金"),
                        GuiItems.label("权限 minestock.simulation.use"))));

        inventory.setItem(SLOT_BUY, GuiItems.button(Material.LIME_DYE,
                Component.text("买入（做多）").color(NamedTextColor.GREEN),
                List.of(
                        GuiItems.line("数量", ForexGui.formatQty(quantity), NamedTextColor.WHITE),
                        GuiItems.label("等价于 /mstock simulation buy " + code + " "
                                + ForexGui.formatQty(quantity)),
                        GuiItems.label("权限 minestock.simulation.use"))));

        inventory.setItem(SLOT_QUANTITY, GuiItems.button(Material.PAPER,
                Component.text("数量 " + ForexGui.formatQty(quantity)).color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.label("左键 +1　右键 -1"),
                        GuiItems.label("Shift+左键 +10　Shift+右键 -10"),
                        GuiItems.label("中键 / 其他：聊天框输入数量"),
                        GuiItems.label("输入 cancel 取消"))));

        inventory.setItem(SLOT_SELL, GuiItems.button(Material.RED_DYE,
                Component.text("卖出（平多）").color(NamedTextColor.RED),
                List.of(
                        GuiItems.line("数量", ForexGui.formatQty(quantity), NamedTextColor.WHITE),
                        GuiItems.label("等价于 /mstock simulation sell " + code + " "
                                + ForexGui.formatQty(quantity)),
                        GuiItems.label("只平多头，不动空单"),
                        GuiItems.label("权限 minestock.simulation.use"))));

        inventory.setItem(SLOT_COVER, GuiItems.button(Material.ORANGE_DYE,
                Component.text("平空").color(NamedTextColor.GOLD),
                List.of(
                        GuiItems.line("数量", ForexGui.formatQty(quantity), NamedTextColor.WHITE),
                        GuiItems.label("等价于 /mstock simulation cover " + code + " "
                                + ForexGui.formatQty(quantity)),
                        GuiItems.label("按数量回补空头，不动多单"),
                        GuiItems.label("权限 minestock.simulation.use"))));

        inventory.setItem(SLOT_CHART, GuiItems.button(Material.FILLED_MAP,
                Component.text("查看K线").color(NamedTextColor.LIGHT_PURPLE),
                List.of(
                        GuiItems.label("等价于 /mstock chart " + code),
                        GuiItems.label("图表输出到聊天框"))));

        inventory.setItem(SLOT_BACK_TO_LIST, GuiItems.button(Material.ARROW,
                Component.text("返回行情").color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.line("页码", String.valueOf(holder.getPage() + 1),
                                NamedTextColor.GRAY),
                        GuiItems.label("回到模拟盘行情列表"))));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    // ── 分页 ──────────────────────────────────────────────────────────────────
    /** 页数：{@code ceil(entries / perPage)}，空集合也算 1 页（避免"第 1/0 页"）。 */
    public static int pageCount(int entries, int perPage) {
        if (entries <= 0 || perPage <= 0) return 1;
        return Math.max(1, (int) Math.ceil(entries / (double) perPage));
    }

    // ── 数据 / 格式化辅助 ─────────────────────────────────────────────────────

    /** 当前页品种；代码未注册或模拟盘未就绪时为 {@code null}。 */
    private SimulationStock findStock(String code) {
        if (code == null) return null;
        SimulationService service = simulationService(plugin);
        return service == null ? null : service.findStock(code).orElse(null);
    }

    /**
     * 取 {@link SimulationService}。
     *
     * <p>直接调用 {@code MineStock.getSimulationService()}（接线切片 C4b 已落地）。
     * 早期版本因为 getter 尚不存在而临时用反射，现已删除 —— 反射既掩盖了
     * "接线未完成"的事实，也让编译期无法发现签名漂移。
     *
     * <p>{@code plugin == null} 或服务尚未创建（插件重载 / 已关闭）时返回 {@code null}，
     * 由各渲染分支降级成"模拟盘未就绪"占位，绝不抛异常打断界面。
     */
    static SimulationService simulationService(MineStock plugin) {
        if (plugin == null) return null;
        return plugin.getSimulationService();
    }

    /** 两位小数；非有限值回落 {@code 0.00}，避免 lore 里出现 "NaN"。 */
    static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
