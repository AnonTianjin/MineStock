package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** 交易类 GUI 共用的布局与物品构建工具，避免各界面重复造轮子。 */
public final class GuiItems {

    private GuiItems() {
    }

    /** 创建 54 格箱子界面并绑定 holder。 */
    public static Inventory create(GuiHolder holder, String title, NamedTextColor color) {
        return create(holder, title, color, 6);
    }

    /**
     * 创建 {@code rows} 行（每行 9 格）的箱子界面并绑定 holder。
     *
     * <p>内容很少的界面（如单个持仓的操作菜单）用 27 格比 54 格更合适：
     * 半箱空玻璃看起来像还没加载完，玩家会反复点刷新。
     *
     * @param rows 1..6；越界会被夹到该区间
     */
    /**
     * 行数 → 箱子格数：夹到 1..6 行，永远是 9 的倍数。
     *
     * <p>抽成纯函数是为了让布局测试不必启动 Bukkit 也能断言尺寸约定。
     */
    public static int chestSize(int rows) {
        return Math.max(1, Math.min(6, rows)) * 9;
    }

    public static Inventory create(GuiHolder holder, String title, NamedTextColor color, int rows) {
        Inventory inventory = Bukkit.createInventory(holder, chestSize(rows),
                Component.text(title).color(color).decorate(TextDecoration.BOLD));
        holder.attach(inventory);
        return inventory;
    }

    public static ItemStack pane(Material material) {
        return pane(material, Component.empty());
    }

    public static ItemStack pane(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 带名称与 lore 的按钮。 */
    public static ItemStack button(Material material, Component name,
                                   Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) meta.lore(List.of(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack button(Material material, Component name, List<Component> lore) {
        return button(material, name, lore.toArray(Component[]::new));
    }

    /** 只读展示项（顶行信息用，不可点击）。 */
    public static ItemStack info(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (Component line : lore) {
                    lines.add(line.decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static Component line(String label, String value, NamedTextColor valueColor) {
        return Component.text(label + ": ").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(value).color(valueColor));
    }

    public static Component label(String text) {
        return Component.text(text).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** 深色玻璃板填充，让 54 格界面有明确的视觉分区。 */
    public static void fillEmpty(Inventory inventory, int from, int to) {
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = from; slot < to && slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, filler);
        }
    }

    /** 统一的"返回主菜单"按钮，放进 49 号槽。 */
    public static final int SLOT_BACK_TO_MENU = 49;
    public static final int SLOT_CLOSE = 49;

    public static ItemStack backToMenu() {
        return button(Material.OAK_DOOR,
                Component.text("返回主菜单").color(NamedTextColor.YELLOW),
                Component.text("回到 /mstock 主菜单").color(NamedTextColor.GRAY));
    }

    public static ItemStack close() {
        return button(Material.BARRIER,
                Component.text("关闭").color(NamedTextColor.RED));
    }

    /**
     * 主菜单槽位常量：两行对称排布，持仓总览居中（最常用）。
     *
     * <p>第 3 行 20 / 22 / 24，第 4 行 29 / 31，另加 49 关闭。
     *
     * <p>已移除"股票持仓（原版界面）"入口：持仓统一走 {@link #MENU_PORTFOLIO_HUB}，
     * 不再在主菜单暴露两套持仓界面。{@code /mstock pg} 指令本身仍然可用。
     */
    public static final int MENU_LEVERAGE = 20;
    /** 持仓总览：居中，最常用。 */
    public static final int MENU_PORTFOLIO_HUB = 22;
    public static final int MENU_FOREX = 24;
    public static final int MENU_FUTURES = 29;
    public static final int MENU_HELP = 31;
    /** 模拟盘入口（所有人可见）。 */
    public static final int MENU_SIMULATION = 33;
    /** 模拟盘管理控制台入口（仅 op 可见）。 */
    public static final int MENU_SIMULATION_ADMIN = 35;
}
