package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.simulation.SimulationBoundary;
import dev.onelili.mstock.simulation.SimulationService;
import dev.onelili.mstock.simulation.SimulationStock;
import dev.onelili.mstock.simulation.SimulationVolatilityPreset;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟盘管理控制台（54 格，仅管理员）。
 *
 * <p><b>零业务逻辑</b>：本类只渲染界面，所有动作按钮的等价指令都以文本形式
 * 写在按钮 lore 里（{@code /mstock simulation admin <动作> <代码> [参数]}）；
 * 点击后由监听器统一 {@code player.performCommand(...)} 走<b>与手输指令完全相同</b>的
 * Service 路径与同一套双重校验，因此这里不复制任何规则。
 *
 * <p><b>槽位</b>：选股区 {@code 0..8}（{@link #SELECT_SLOTS} = 9，每页 9 只），
 * 固定按钮全部 {@code >= SELECT_SLOTS} 且互不相同 —— 由
 * {@code GuiLayoutTest.simulationAdminSlotsAvoidSelectionArea} 立测。
 *
 * <p><b>为什么信息面板放在 13</b>：{@code ForexGui} 的行情页把信息写进 4 号槽、
 * 而 4 号槽又在品种区里，导致第 5 个品种被覆盖（{@code ForexGui.java:268}）。
 * 管理台把信息放到选股区之外，从结构上消除同类覆盖。
 */
public class SimulationAdminGui {

    public static final String TITLE = "模拟盘管理";

    /** 股票选择区起点。 */
    public static final int SELECT_START = 0;
    /** 股票选择区容量（9 只，即每页 9 只）。 */
    public static final int SELECT_SLOTS = 9;
    /** 选中股信息（避开 0..8）。 */
    public static final int SLOT_INFO = 13;
    /** 绝对下限调低一档。 */
    public static final int SLOT_MIN_DOWN = 9;
    /** 绝对下限调高一档。 */
    public static final int SLOT_MIN_UP = 10;
    /** 绝对上限调低一档。 */
    public static final int SLOT_MAX_DOWN = 11;
    /** 绝对上限调高一档。 */
    public static final int SLOT_MAX_UP = 12;
    /** 定值 -10%。 */
    public static final int SLOT_FREEZE_DOWN = 19;
    /** 定值 +10%。 */
    public static final int SLOT_FREEZE_UP = 21;
    /** 暂停推进 on/off。 */
    public static final int SLOT_PAUSE = 23;
    /** 停牌 on/off。 */
    public static final int SLOT_SUSPEND = 25;
    /** 可交易 on/off。 */
    public static final int SLOT_TRADABLE = 28;
    /** 边界策略循环。 */
    public static final int SLOT_BOUNDARY = 30;
    /** 波动预设循环。 */
    public static final int SLOT_PRESET = 32;
    /** 恢复自动。 */
    public static final int SLOT_RESUME = 34;
    /** 重置。 */
    public static final int SLOT_RESET = 38;
    /** 重设随机种子。 */
    public static final int SLOT_SEED = 40;
    /** 指令速查。 */
    public static final int SLOT_HELP = 42;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_REFRESH = 46;
    public static final int SLOT_NEXT_PAGE = 53;
    // 49 = GuiItems.SLOT_BACK_TO_MENU

    /** 边界策略的循环顺序（与 {@link SimulationBoundary} 常量一一对应）。 */
    private static final SimulationBoundary[] BOUNDARY_CYCLE = {
            SimulationBoundary.HARD_CLAMP,
            SimulationBoundary.BOUNCE,
            SimulationBoundary.SOFT_REGRESSION
    };

    /** 波动预设的循环顺序（从低到高）。 */
    private static final SimulationVolatilityPreset[] PRESET_CYCLE = {
            SimulationVolatilityPreset.LOW,
            SimulationVolatilityPreset.NORMAL,
            SimulationVolatilityPreset.HIGH
    };

    /**
     * 指令速查条目：GUI 未提供按钮的复杂参数指令（只在指令层提供），
     * 全部以等价指令文本展示，供管理员复制到手输指令中执行。
     */
    private static final List<String> HELP_COMMANDS = List.of(
            "/mstock simulation list [页码]",
            "/mstock simulation admin add <代码> <名称> <板块> <初始价>",
            "/mstock simulation admin remove <代码>",
            "/mstock simulation admin setprice <代码> <价格>",
            "/mstock simulation admin target <代码> <涨幅%>",
            "/mstock simulation admin jump <代码> <涨跌%>",
            "/mstock simulation admin slope <代码> <每毫秒价格> <持续毫秒>",
            "/mstock simulation admin amplitude <代码> <倍数>",
            "/mstock simulation admin bounds <代码> <下限> <上限>",
            "/mstock simulation admin limit <代码> <涨跌停%>",
            "/mstock simulation admin link <代码A> <代码B> <相关系数>",
            "/mstock simulation admin schedule <代码> <HH:mm> <涨幅%> [daily]",
            "/mstock simulation admin schedules",
            "/mstock simulation admin unschedule <序号>");

    private final MineStock plugin;

    public SimulationAdminGui(MineStock plugin) {
        this.plugin = plugin;
    }

    // ── 打开 / 刷新 ───────────────────────────────────────────────────────────

    /** 打开管理台（每页 9 只股票）。 */
    public void open(Player player, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.SIMULATION_ADMIN).page(page);
        Inventory inventory = GuiItems.create(holder, TITLE, NamedTextColor.LIGHT_PURPLE);
        render(holder);
        player.openInventory(inventory);
    }

    /** 原地重新渲染当前页（翻页 / 刷新共用）。 */
    public void reload(Player player, GuiHolder holder) {
        if (holder != null) render(holder);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────────

    private void render(GuiHolder holder) {
        Inventory inventory = holder.getInventory();
        // 全量清空后再画：选中股消失或状态翻转时不会留下上一次的陈旧按钮。
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, null);

        SimulationService service = SimulationGui.simulationService(plugin);
        List<SimulationStock> stocks = service == null ? List.of() : service.stocks();

        int pages = SimulationGui.pageCount(stocks.size(), SELECT_SLOTS);
        int safePage = Math.max(0, Math.min(pages - 1, holder.getPage()));
        int start = safePage * SELECT_SLOTS;

        List<SimulationStock> visible = new ArrayList<>(SELECT_SLOTS);
        for (int i = 0; i < SELECT_SLOTS && start + i < stocks.size(); i++) {
            SimulationStock stock = stocks.get(start + i);
            visible.add(stock);
            inventory.setItem(SELECT_START + i, buildSelectItem(stock));
        }

        // 选中股：优先取 holder 里记住的代码；没有（或已下线）时回落当前页第一只。
        SimulationStock selected = findSelected(holder, visible);

        inventory.setItem(SLOT_INFO, buildInfo(selected, stocks.size(), safePage, pages, service));
        renderActions(inventory, selected);
        renderHelp(inventory);
        renderPaging(inventory, safePage, pages);

        inventory.setItem(SLOT_REFRESH, GuiItems.button(Material.ARROW,
                Component.text("刷新").color(NamedTextColor.GREEN),
                List.of(
                        GuiItems.label("重新读取股票池与选中股状态"),
                        GuiItems.label("执行指令后按此查看最新状态"))));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());

        holder.page(safePage);
        GuiItems.fillEmpty(inventory, SELECT_START + SELECT_SLOTS, inventory.getSize());
    }

    /** 选股区条目：显示名就是代码（点击分发据此取代码，排版改动不会影响路由）。 */
    private static ItemStack buildSelectItem(SimulationStock stock) {
        boolean suspended = stock.isSuspended();
        double change = stock.changePercent();
        boolean up = change >= 0;
        NamedTextColor color = suspended ? NamedTextColor.GRAY
                : (up ? NamedTextColor.GREEN : NamedTextColor.RED);

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("名称", stock.getName(), NamedTextColor.WHITE));
        lore.add(GuiItems.line("板块", stock.getSector(), NamedTextColor.GRAY));
        lore.add(GuiItems.line("现价", SimulationGui.format2(stock.getCurrentPrice()),
                NamedTextColor.WHITE));
        lore.add(GuiItems.line("涨跌幅", (up ? "+" : "") + SimulationGui.format2(change) + "%",
                color));
        lore.add(GuiItems.line("预设 / 边界",
                stock.getPreset().id() + " / " + stock.getBoundary().id(), NamedTextColor.AQUA));
        lore.add(GuiItems.line("状态", stateText(stock),
                suspended ? NamedTextColor.GRAY : NamedTextColor.WHITE));
        lore.add(Component.empty());
        lore.add(GuiItems.label("点击选中该股"));
        return GuiItems.button(Material.PAPER, Component.text(stock.getCode()).color(color), lore);
    }

    /** 选中股信息面板：当前价格 / 涨跌幅 / 预设 / 边界 / 三道开关与涨跌停价。 */
    private static ItemStack buildInfo(SimulationStock selected, int total, int page, int pages,
                                       SimulationService service) {
        if (selected == null) {
            List<Component> lore = new ArrayList<>();
            lore.add(GuiItems.line("在册品种", total + " 只", NamedTextColor.WHITE));
            lore.add(GuiItems.line("页码", (page + 1) + " / " + pages, NamedTextColor.WHITE));
            if (service == null) {
                lore.add(GuiItems.line("状态", "模拟盘未就绪", NamedTextColor.RED));
            } else if (!service.isEnabled()) {
                lore.add(GuiItems.line("状态", "已关闭（simulation.enabled: false）",
                        NamedTextColor.RED));
            } else if (total == 0) {
                lore.add(GuiItems.line("提示", "暂无模拟股", NamedTextColor.YELLOW));
            }
            lore.add(GuiItems.label("点击上方选股区选择要操作的股票"));
            return GuiItems.info(Material.COMMAND_BLOCK,
                    Component.text(TITLE).color(NamedTextColor.LIGHT_PURPLE), lore);
        }

        double change = selected.changePercent();
        boolean up = change >= 0;
        boolean suspended = selected.isSuspended();
        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("名称", selected.getName(), NamedTextColor.WHITE));
        lore.add(GuiItems.line("板块", selected.getSector(), NamedTextColor.GRAY));
        lore.add(GuiItems.line("现价", SimulationGui.format2(selected.getCurrentPrice()),
                NamedTextColor.WHITE));
        lore.add(GuiItems.line("涨跌幅", (up ? "+" : "") + SimulationGui.format2(change) + "%",
                suspended ? NamedTextColor.GRAY : (up ? NamedTextColor.GREEN : NamedTextColor.RED)));
        lore.add(GuiItems.line("昨收", SimulationGui.format2(selected.getPrevClose()),
                NamedTextColor.GRAY));
        lore.add(GuiItems.line("跌停 / 涨停",
                SimulationGui.format2(selected.lowerLimit()) + " / "
                        + SimulationGui.format2(selected.upperLimit()), NamedTextColor.GRAY));
        lore.add(GuiItems.line("预设 / 边界",
                selected.getPreset().id() + " / " + selected.getBoundary().id(), NamedTextColor.AQUA));
        lore.add(GuiItems.line("种子", String.valueOf(selected.getSeed()), NamedTextColor.DARK_GRAY));
        lore.add(GuiItems.label("状态: " + stateText(selected)));
        lore.add(GuiItems.line("页码", (page + 1) + " / " + pages, NamedTextColor.GRAY));
        return GuiItems.info(Material.COMMAND_BLOCK,
                Component.text(selected.getCode()).color(NamedTextColor.LIGHT_PURPLE), lore);
    }

    /**
     * 全部动作按钮。
     *
     * <p>每个按钮的第三条 lore 都是它的<b>等价指令</b>，点击后由监听器原样
     * {@code performCommand} 出去；GUI 侧不解析参数、不调用任何 Service 写方法。
     */
    private static void renderActions(Inventory inventory, SimulationStock stock) {
        if (stock == null) {
            // 没有选中股时，动作按钮留空（被玻璃板填充），避免误触发出无代码的指令。
            return;
        }
        String code = stock.getCode();

        inventory.setItem(SLOT_FREEZE_DOWN, GuiItems.button(Material.RED_CONCRETE,
                Component.text("定值 -10%").color(NamedTextColor.RED),
                List.of(
                        GuiItems.label("把目标涨幅钉在昨收 -10%"),
                        GuiItems.line("当前价", SimulationGui.format2(stock.getCurrentPrice()),
                                NamedTextColor.WHITE),
                        GuiItems.label(command("target " + code + " -10")))));

        inventory.setItem(SLOT_FREEZE_UP, GuiItems.button(Material.LIME_CONCRETE,
                Component.text("定值 +10%").color(NamedTextColor.GREEN),
                List.of(
                        GuiItems.label("把目标涨幅钉在昨收 +10%"),
                        GuiItems.line("当前价", SimulationGui.format2(stock.getCurrentPrice()),
                                NamedTextColor.WHITE),
                        GuiItems.label(command("target " + code + " 10")))));

        inventory.setItem(SLOT_PAUSE, GuiItems.button(
                stock.isPaused() ? Material.YELLOW_DYE : Material.GRAY_DYE,
                Component.text("暂停推进 " + onOff(stock.isPaused()))
                        .color(stock.isPaused() ? NamedTextColor.YELLOW : NamedTextColor.GRAY),
                List.of(
                        GuiItems.label("暂停后价格冻结，但仍可交易"),
                        GuiItems.line("当前", onOff(stock.isPaused()), NamedTextColor.WHITE),
                        GuiItems.label(command("pause " + code + " "
                                + (!stock.isPaused() ? "on" : "off"))))));

        inventory.setItem(SLOT_SUSPEND, GuiItems.button(
                stock.isSuspended() ? Material.RED_DYE : Material.GRAY_DYE,
                Component.text("停牌 " + onOff(stock.isSuspended()))
                        .color(stock.isSuspended() ? NamedTextColor.RED : NamedTextColor.GRAY),
                List.of(
                        GuiItems.label("停牌后价格冻结且禁止买卖"),
                        GuiItems.line("当前", onOff(stock.isSuspended()), NamedTextColor.WHITE),
                        GuiItems.label(command("suspend " + code + " "
                                + (!stock.isSuspended() ? "on" : "off"))))));

        inventory.setItem(SLOT_TRADABLE, GuiItems.button(
                stock.isTradable() ? Material.LIME_DYE : Material.RED_DYE,
                Component.text("可交易 " + onOff(stock.isTradable()))
                        .color(stock.isTradable() ? NamedTextColor.GREEN : NamedTextColor.RED),
                List.of(
                        GuiItems.label("关掉后禁止买卖，但价格仍会推进"),
                        GuiItems.line("当前", onOff(stock.isTradable()), NamedTextColor.WHITE),
                        GuiItems.label(command("tradable " + code + " "
                                + (!stock.isTradable() ? "on" : "off"))))));

        inventory.setItem(SLOT_BOUNDARY, GuiItems.button(Material.IRON_BARS,
                Component.text("边界策略循环").color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.label("触及涨跌停带时的处理方式"),
                        GuiItems.line("当前", stock.getBoundary().id(), NamedTextColor.WHITE),
                        GuiItems.label(command("boundary " + code + " "
                                + nextBoundary(stock.getBoundary()).id())))));

        inventory.setItem(SLOT_PRESET, GuiItems.button(Material.BLAZE_POWDER,
                Component.text("波动预设循环").color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.label("低 → 正常 → 高 三档循环"),
                        GuiItems.line("当前", stock.getPreset().id(), NamedTextColor.WHITE),
                        GuiItems.label(command("preset " + code + " "
                                + nextPreset(stock.getPreset()).id())))));

        inventory.setItem(SLOT_RESUME, GuiItems.button(Material.FEATHER,
                Component.text("恢复自动").color(NamedTextColor.WHITE),
                List.of(
                        GuiItems.label("清除定值 / 目标涨幅 / 斜率与暂停"),
                        GuiItems.label("不改变停牌与可交易开关"),
                        GuiItems.label(command("resume " + code)))));

        inventory.setItem(SLOT_RESET, GuiItems.button(Material.TNT,
                Component.text("重置").color(NamedTextColor.RED),
                List.of(
                        GuiItems.line("回到初始价", SimulationGui.format2(stock.getInitPrice()),
                                NamedTextColor.WHITE),
                        GuiItems.label("清空指令与停牌，同种子重放价格路径"),
                        GuiItems.label(command("reset " + code)))));

        inventory.setItem(SLOT_SEED, GuiItems.button(Material.ENDER_PEARL,
                Component.text("重设随机种子").color(NamedTextColor.LIGHT_PURPLE),
                List.of(
                        GuiItems.line("当前种子", String.valueOf(stock.getSeed()),
                                NamedTextColor.WHITE),
                        GuiItems.label("同种子 ⇒ 同一条价格路径"),
                        GuiItems.label(command("seed " + code)))));

        renderBounds(inventory, stock);
    }

    /**
     * 绝对上下限的自调数值设置。
     *
     * <p><b>零业务逻辑</b>：四个按钮点下去都是等价的
     * {@code /mstock simulation admin bounds <代码> <下限> <上限>}，
     * "上限必须严格大于下限"这条规则只在 {@link SimulationService#setBounds} 里判定一次，
     * GUI 只负责把数值算出来 —— 拒绝时的提示与手输指令完全一致。
     *
     * <p>步长取当前值的 1%（最小 0.01）：模拟盘价格跨度从 0.01 到 1000000，
     * 任何固定步长都会在一端不可用。需要精确数值时仍可用指令层直接写两个数。
     */
    private static void renderBounds(Inventory inventory, SimulationStock stock) {
        String code = stock.getCode();
        double curMin = stock.getMinPrice();
        double curMax = stock.getMaxPrice();
        double stepMin = boundsStep(curMin);
        double stepMax = boundsStep(curMax);

        inventory.setItem(SLOT_MIN_DOWN, boundsStepButton("下限 −" + SimulationGui.format2(stepMin),
                code, curMin, curMax, true, -stepMin, NamedTextColor.RED, Material.RED_STAINED_GLASS_PANE));
        inventory.setItem(SLOT_MIN_UP, boundsStepButton("下限 +" + SimulationGui.format2(stepMin),
                code, curMin, curMax, true, stepMin, NamedTextColor.GREEN, Material.LIME_STAINED_GLASS_PANE));
        inventory.setItem(SLOT_MAX_DOWN, boundsStepButton("上限 −" + SimulationGui.format2(stepMax),
                code, curMin, curMax, false, -stepMax, NamedTextColor.RED, Material.RED_STAINED_GLASS_PANE));
        inventory.setItem(SLOT_MAX_UP, boundsStepButton("上限 +" + SimulationGui.format2(stepMax),
                code, curMin, curMax, false, stepMax, NamedTextColor.GREEN, Material.LIME_STAINED_GLASS_PANE));
    }

    /** 单个上下限步进按钮；最后一行 lore 必须是等价指令（监听器据此路由）。 */
    private static ItemStack boundsStepButton(String label, String code, double curMin,
                                              double curMax, boolean onLower, double delta,
                                              NamedTextColor color, Material material) {
        double next = round2((onLower ? curMin : curMax) + delta);
        double nextMin = onLower ? next : curMin;
        double nextMax = onLower ? curMax : next;

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("当前区间", SimulationGui.format2(curMin) + " ~ "
                + SimulationGui.format2(curMax), NamedTextColor.WHITE));
        lore.add(GuiItems.label("调整 " + (onLower ? "下限" : "上限") + " "
                + (delta >= 0 ? "+" : "−") + SimulationGui.format2(Math.abs(delta))));
        lore.add(GuiItems.line("调整后", SimulationGui.format2(nextMin) + " ~ "
                + SimulationGui.format2(nextMax), NamedTextColor.AQUA));
        lore.add(GuiItems.label("上限必须大于下限，否则会被拒绝"));
        lore.add(GuiItems.label(command("bounds " + code + " "
                + SimulationGui.format2(nextMin) + " " + SimulationGui.format2(nextMax))));
        return GuiItems.button(material, Component.text(label).color(color), lore);
    }

    /** 一档步长 = 当前值的 1%，下限 0.01。 */
    private static double boundsStep(double value) {
        return Math.max(0.01, round2(Math.abs(value) * 0.01));
    }

    /** 两位小数四舍五入；非有限值一律退化为 0.01，绝不把 NaN 拼进指令。 */
    private static double round2(double value) {
        if (!Double.isFinite(value)) return 0.01;
        return Math.max(0.01, Math.round(value * 100.0) / 100.0);
    }

    /** 指令速查面板：列出 GUI 未提供按钮的复杂参数指令，供复制。 */
    private static void renderHelp(Inventory inventory) {
        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.label("以下指令只在指令层提供，可复制执行"));
        for (String command : HELP_COMMANDS) {
            lore.add(GuiItems.label(command));
        }
        inventory.setItem(SLOT_HELP, GuiItems.info(Material.KNOWLEDGE_BOOK,
                Component.text("指令速查").color(NamedTextColor.YELLOW), lore));
    }

    private static void renderPaging(Inventory inventory, int page, int pages) {
        if (page > 0) {
            inventory.setItem(SLOT_PREV_PAGE, GuiItems.button(Material.ARROW,
                    Component.text("上一页").color(NamedTextColor.YELLOW)));
        }
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT_PAGE, GuiItems.button(Material.ARROW,
                    Component.text("下一页").color(NamedTextColor.YELLOW)));
        }
    }

    // ── 纯读取辅助（无任何 Service 写操作）────────────────────────────────────

    /** 选中股：优先 {@code holder.symbol}，其次当前页第一只。 */
    private SimulationStock findSelected(GuiHolder holder, List<SimulationStock> visible) {
        String code = holder.getSymbol();
        if (code != null) {
            SimulationService service = SimulationGui.simulationService(plugin);
            SimulationStock found = service == null ? null : service.findStock(code).orElse(null);
            if (found != null) return found;
        }
        return visible.isEmpty() ? null : visible.get(0);
    }

    private static SimulationBoundary nextBoundary(SimulationBoundary current) {
        for (int i = 0; i < BOUNDARY_CYCLE.length; i++) {
            if (BOUNDARY_CYCLE[i] == current) return BOUNDARY_CYCLE[(i + 1) % BOUNDARY_CYCLE.length];
        }
        return BOUNDARY_CYCLE[0];
    }

    private static SimulationVolatilityPreset nextPreset(SimulationVolatilityPreset current) {
        for (int i = 0; i < PRESET_CYCLE.length; i++) {
            if (PRESET_CYCLE[i] == current) return PRESET_CYCLE[(i + 1) % PRESET_CYCLE.length];
        }
        return PRESET_CYCLE[0];
    }

    /** 三道开关的合成显示文本。 */
    private static String stateText(SimulationStock stock) {
        if (stock.isSuspended()) return "停牌";
        if (!stock.isTradable()) return "禁售";
        if (stock.isPaused()) return "暂停推进";
        return "正常";
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String command(String actionAndCode) {
        return "/mstock simulation admin " + actionAndCode;
    }
}
