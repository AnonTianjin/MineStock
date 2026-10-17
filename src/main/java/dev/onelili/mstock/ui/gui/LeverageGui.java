package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.leverage.LeverageService;
import dev.onelili.mstock.leverage.MarginSnapshot;
import dev.onelili.mstock.leverage.PlayerLeverageManager;
import dev.onelili.mstock.model.MarginPosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 杠杆设置界面（54 格）。
 *
 * <p>界面内用 45 / 47 两个页签切换「外汇」与「股票」两套彼此独立的杠杆制度
 * （{@link LeverageConfig.Policy}）。切换后顶行信息、调节按钮与重置按钮
 * <b>全部</b>作用在当前制度上，因此
 * {@code /mstock leverage}、{@code /mstock leverage stock} 都有等价的 GUI 入口
 * —— 兑现"指令是权威入口，GUI 是等价映射"的承诺。
 *
 * <p>中间一排 {@code -10 -5 -1 +1 +5 +10} 按钮点击后直接调用
 * {@link PlayerLeverageManager#setLeverage} / {@link PlayerLeverageManager#setStockLeverage}
 * （不需要草稿模式，因此没有"确认"按钮）。
 */
public class LeverageGui {

    public static final String TITLE = "杠杆设置";

    /** 页签：切到外汇杠杆。 */
    public static final int SLOT_VIEW_FOREX = 45;
    /** 页签：切到股票杠杆。 */
    public static final int SLOT_VIEW_STOCK = 47;

    /** 中间一排六个调节按钮的槽位。 */
    public static final int[] ADJUST_SLOTS = {19, 21, 23, 25, 28, 30};
    /** 与槽位一一对应的调整步长。 */
    public static final int[] ADJUST_STEPS = {-10, -5, -1, 1, 5, 10};
    /** 顶行只读信息（当前杠杆 / 范围 / 保证金率）。 */
    public static final int SLOT_INFO = 4;
    /** 重置为默认杠杆。 */
    public static final int SLOT_RESET = 13;

    private final MineStock plugin;

    public LeverageGui(MineStock plugin) {
        this.plugin = plugin;
    }

    /** 打开界面（默认外汇制度），先用"加载中…"占位。 */
    public void open(Player player) {
        open(player, false);
    }

    /**
     * 打开界面。
     *
     * @param stockView {@code true} 进入股票杠杆页签，{@code false} 进入外汇页签
     */
    public void open(Player player, boolean stockView) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.LEVERAGE)
                .stockLeverageView(stockView);
        Inventory inventory = GuiItems.create(holder, TITLE, NamedTextColor.AQUA);
        renderStatic(player, holder, inventory);
        player.openInventory(inventory);
        loadSnapshot(player, holder, inventory);
    }

    /** 原地刷新（点击加减按钮后调用，不重开界面避免闪烁）。 */
    public void refresh(Player player, GuiHolder holder, Inventory inventory) {
        renderStatic(player, holder, inventory);
        loadSnapshot(player, holder, inventory);
    }

    /** 把持有者读到的制度转成配置层的制度枚举。 */
    private static LeverageConfig.Policy policyOf(GuiHolder holder) {
        return holder.isStockLeverageView()
                ? LeverageConfig.Policy.STOCK
                : LeverageConfig.Policy.FOREX;
    }

    /** 当前制度下玩家生效的杠杆。 */
    private int currentLeverage(Player player, GuiHolder holder) {
        PlayerLeverageManager manager = plugin.getLeverageManager();
        LeverageConfig config = plugin.getLeverageConfig();
        LeverageConfig.Policy policy = policyOf(holder);
        if (manager == null) return config.defaultLeverage(policy);
        return policy == LeverageConfig.Policy.STOCK
                ? manager.getStockLeverage(player.getUniqueId())
                : manager.getLeverage(player.getUniqueId());
    }

    /**
     * 股票页签的说明文字。
     *
     * <p>{@code leverage.stock.enabled: false} 时显示的是<b>生效值</b>（1x）
     * 而不是库里存着的历史高杠杆 —— 否则玩家会看到一个"显示 30x 却不生效"的界面。
     */
    private List<Component> stockTabLore(Player player, LeverageConfig config, boolean active) {
        boolean enabled = config.isStockLeverageEnabled();
        PlayerLeverageManager manager = plugin.getLeverageManager();
        String current = !enabled ? "1x（已关闭）"
                : manager == null ? "-" : manager.getStockLeverage(player.getUniqueId()) + "x";

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("当前", current, NamedTextColor.WHITE));
        lore.add(GuiItems.line("范围", "1x ~ " + config.getStockMaxLeverage() + "x",
                NamedTextColor.GRAY));
        lore.add(GuiItems.label("1x = 全额现金买入"));
        if (!enabled) {
            lore.add(GuiItems.label("服务器已关闭股票杠杆"));
        }
        lore.add(GuiItems.label(active ? "当前页签" : "点击切换"));
        return lore;
    }

    /** 渲染不依赖行情的部分：页签、调节按钮、重置、返回。 */
    private void renderStatic(Player player, GuiHolder holder, Inventory inventory) {
        LeverageConfig config = plugin.getLeverageConfig();
        LeverageConfig.Policy policy = policyOf(holder);
        boolean stock = policy == LeverageConfig.Policy.STOCK;
        int current = currentLeverage(player, holder);
        int max = config.maxLeverage(policy);

        inventory.setItem(SLOT_INFO, GuiItems.info(Material.COMPASS,
                Component.text((stock ? "股票" : "外汇") + "杠杆 " + current + "x")
                        .color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.line("制度", stock ? "股票" : "外汇 / 虚拟货币",
                                NamedTextColor.AQUA),
                        GuiItems.line("当前杠杆", current + "x", NamedTextColor.AQUA),
                        GuiItems.line("可用范围", "1x ~ " + max + "x", NamedTextColor.WHITE),
                        GuiItems.line("默认杠杆", config.defaultLeverage(policy) + "x",
                                NamedTextColor.GRAY),
                        GuiItems.label("保证金率加载中…"))));

        // 两个页签：与 /mstock leverage[ stock] 一一对应。
        inventory.setItem(SLOT_VIEW_FOREX, GuiItems.button(
                stock ? Material.GRAY_DYE : Material.LIME_DYE,
                Component.text("外汇杠杆").color(stock ? NamedTextColor.GRAY : NamedTextColor.GREEN),
                List.of(
                        GuiItems.line("当前", plugin.getLeverageManager() == null ? "-"
                                : plugin.getLeverageManager().getLeverage(player.getUniqueId()) + "x",
                                NamedTextColor.WHITE),
                        GuiItems.line("范围", "1x ~ " + config.getMaxLeverage() + "x",
                                NamedTextColor.GRAY),
                        GuiItems.label("等价于 /mstock leverage"),
                        GuiItems.label(stock ? "点击切换" : "当前页签"))));

        inventory.setItem(SLOT_VIEW_STOCK, GuiItems.button(
                stock ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("股票杠杆").color(stock ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                stockTabLore(player, config, stock)));

        // 中间一排调节按钮。
        for (int i = 0; i < ADJUST_SLOTS.length; i++) {
            int step = ADJUST_STEPS[i];
            int target = clamp(current + step, max);
            Material material = step > 0 ? Material.LIME_DYE : Material.RED_DYE;
            String sign = step > 0 ? "+" : "";
            inventory.setItem(ADJUST_SLOTS[i], GuiItems.button(material,
                    Component.text(sign + step)
                            .color(step > 0 ? NamedTextColor.GREEN : NamedTextColor.RED),
                    List.of(
                            GuiItems.line("调整后", target + "x", NamedTextColor.WHITE),
                            GuiItems.line("范围", "1x ~ " + max + "x", NamedTextColor.GRAY),
                            GuiItems.label("等价于 /mstock leverage "
                                    + (stock ? "stock " : "") + target))));
        }

        inventory.setItem(SLOT_RESET, GuiItems.button(Material.HOPPER,
                Component.text("重置为默认").color(NamedTextColor.YELLOW),
                List.of(GuiItems.line("默认杠杆", config.defaultLeverage(policy) + "x",
                        NamedTextColor.WHITE))));

        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    /** 异步取持仓快照，回到玩家线程后写入顶行保证金信息。 */
    private void loadSnapshot(Player player, GuiHolder holder, Inventory inventory) {
        LeverageService service = plugin.getLeverageService();
        if (service == null) return;
        // 复合价格源：股票仓位走股票行情，外汇走 biquote
        var priceSource = plugin.priceSource();

        service.snapshot(player.getUniqueId(), priceSource).thenAccept(snapshot ->
                runOnPlayer(player, () -> {
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    if (current == null || !current.isOnline()) return;
                    // 玩家可能已经跳到别的界面：只刷新仍然打开的那一个。
                    if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;
                    applySnapshot(holder, inventory, snapshot);
                }));
    }

    private void applySnapshot(GuiHolder holder, Inventory inventory, MarginSnapshot snapshot) {
        LeverageConfig config = plugin.getLeverageConfig();
        LeverageConfig.Policy policy = policyOf(holder);
        boolean stock = policy == LeverageConfig.Policy.STOCK;
        double ratio = snapshot.marginRatio();
        NamedTextColor ratioColor = ratio <= config.getLiquidationThreshold()
                ? NamedTextColor.RED
                : ratio <= config.getMarginCallThreshold()
                        ? NamedTextColor.YELLOW : NamedTextColor.GREEN;

        int current = inventory.getItem(SLOT_INFO) != null
                ? parseLeverage(inventory.getItem(SLOT_INFO))
                : config.defaultLeverage(policy);

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("制度", stock ? "股票" : "外汇 / 虚拟货币", NamedTextColor.AQUA));
        lore.add(GuiItems.line("当前杠杆", current + "x", NamedTextColor.AQUA));
        lore.add(GuiItems.line("可用范围",
                "1x ~ " + config.maxLeverage(policy) + "x", NamedTextColor.WHITE));
        lore.add(GuiItems.line("总保证金率", format2(ratio * 100.0) + "%", ratioColor));
        lore.add(GuiItems.line("总保证金", format2(snapshot.totalMargin()), NamedTextColor.WHITE));
        lore.add(GuiItems.line("未实现盈亏",
                (snapshot.totalUnrealizedPnl() >= 0 ? "+" : "")
                        + format2(snapshot.totalUnrealizedPnl()),
                snapshot.totalUnrealizedPnl() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(GuiItems.line("持仓笔数", String.valueOf(snapshot.positionCount()),
                NamedTextColor.WHITE));
        lore.add(GuiItems.line("追保线 / 强平线",
                format2(config.getMarginCallThreshold() * 100.0) + "% / "
                        + format2(config.getLiquidationThreshold() * 100.0) + "%",
                NamedTextColor.GRAY));
        for (MarginPosition position : snapshot.positions()) {
            lore.add(GuiItems.line(" #" + position.getId() + " " + position.getSymbol(),
                    position.getDirection() + " " + format2(position.getUnrealizedPnl()),
                    position.getUnrealizedPnl() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        }

        inventory.setItem(SLOT_INFO, GuiItems.info(Material.COMPASS,
                Component.text((stock ? "股票" : "外汇") + "杠杆 " + current + "x")
                        .color(NamedTextColor.AQUA),
                lore));
    }

    /**
     * 点击调节按钮后的统一处理；返回是否识别该槽位。
     *
     * <p>页签切换也在这里处理：换页签只改显示，不碰任何杠杆值。
     */
    public boolean handleAdjust(Player player, GuiHolder holder, Inventory inventory, int slot) {
        if (slot == SLOT_VIEW_FOREX || slot == SLOT_VIEW_STOCK) {
            boolean stockView = slot == SLOT_VIEW_STOCK;
            if (stockView && !plugin.getLeverageConfig().isStockLeverageEnabled()) {
                player.sendMessage(plugin.getLang().get("leverage-stock-disabled"));
                return true;
            }
            holder.stockLeverageView(stockView);
            refresh(player, holder, inventory);
            return true;
        }
        for (int i = 0; i < ADJUST_SLOTS.length; i++) {
            if (ADJUST_SLOTS[i] != slot) continue;
            applyStep(player, holder, inventory, ADJUST_STEPS[i]);
            return true;
        }
        if (slot == SLOT_RESET) {
            LeverageConfig.Policy policy = policyOf(holder);
            int defaultValue = plugin.getLeverageConfig().defaultLeverage(policy);
            applyStep(player, holder, inventory, defaultValue - currentLeverage(player, holder));
            return true;
        }
        return false;
    }

    private void applyStep(Player player, GuiHolder holder, Inventory inventory, int step) {
        if (step == 0) return;
        if (!player.hasPermission("minestock.leverage.set")) {
            player.sendMessage(plugin.getLang().get("no-permission"));
            return;
        }
        PlayerLeverageManager manager = plugin.getLeverageManager();
        if (manager == null) return;

        LeverageConfig.Policy policy = policyOf(holder);
        boolean stock = policy == LeverageConfig.Policy.STOCK;
        int max = plugin.getLeverageConfig().maxLeverage(policy);
        int current = currentLeverage(player, holder);
        int target = clamp(current + step, max);
        if (target == current) {
            player.sendMessage(plugin.getLang().get("leverage-unchanged",
                    "leverage", String.valueOf(current)));
            return;
        }

        boolean applied = stock
                ? manager.setStockLeverage(player.getUniqueId(), target)
                : manager.setLeverage(player.getUniqueId(), target);
        if (applied) {
            player.sendMessage(plugin.getLang().get(stock ? "leverage-stock-set" : "leverage-set",
                    "leverage", String.valueOf(target),
                    "max", String.valueOf(max)));
        } else {
            player.sendMessage(plugin.getLang().get(
                    stock ? "leverage-stock-out-of-range" : "leverage-out-of-range",
                    "max", String.valueOf(max)));
        }
        refresh(player, holder, inventory);
    }

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(max, value));
    }

    private static int parseLeverage(ItemStack item) {
        if (item == null || item.getItemMeta() == null
                || item.getItemMeta().displayName() == null) {
            return 0;
        }
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(item.getItemMeta().displayName());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)x").matcher(plain);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void runOnPlayer(Player player, Runnable task) {
        dev.onelili.mstock.scheduler.CompatScheduler.get().runOnEntity(plugin, player, () -> {
            if (plugin.isOperational() && player.isOnline()) task.run();
        });
    }
}
