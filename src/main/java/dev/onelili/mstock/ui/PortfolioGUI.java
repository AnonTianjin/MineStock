package dev.onelili.mstock.ui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.HoldingRepository;
import dev.onelili.mstock.model.Holding;
import dev.onelili.mstock.model.StockInfo;
import dev.onelili.mstock.stockio.StockApiService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PortfolioGUI {

    /** 正在查看持仓 GUI 的玩家 UUID，用于拦截 inventory 点击事件 */
    private static final Set<UUID> VIEWERS = ConcurrentHashMap.newKeySet();

    public static final String GUI_TITLE_PREFIX = "我的持仓";

    private final MineStock plugin;
    private final HoldingRepository holdingRepo;
    private final StockApiService api;
    private final MainConfig config;

    public PortfolioGUI(MineStock plugin) {
        this.plugin = plugin;
        this.holdingRepo = plugin.getHoldingRepo();
        this.api = plugin.getApi();
        this.config = plugin.getMainConfig();
    }

    public static boolean isViewer(UUID uuid) {
        return VIEWERS.contains(uuid);
    }

    public static void removeViewer(UUID uuid) {
        VIEWERS.remove(uuid);
    }

    public void open(Player player) {
        plugin.getTaskManager().runAsync(() -> {
            List<Holding> holdings;
            try {
                holdings = holdingRepo.findAllByPlayer(player.getUniqueId());
            } catch (SQLException e) {
                runSync(() -> player.sendMessage(
                        Component.text("[MineStock] 数据库错误，无法读取持仓").color(NamedTextColor.RED)));
                return;
            }

            if (holdings.isEmpty()) {
                runSync(() -> {
                    Inventory inv = Bukkit.createInventory(null, 9,
                            Component.text(GUI_TITLE_PREFIX).color(NamedTextColor.GOLD)
                                    .decorate(TextDecoration.BOLD));
                    ItemStack empty = buildPane(Material.GRAY_STAINED_GLASS_PANE,
                            Component.text("暂无持仓").color(NamedTextColor.GRAY));
                    inv.setItem(4, empty);
                    VIEWERS.add(player.getUniqueId());
                    player.openInventory(inv);
                });
                return;
            }
            List<StockInfo> infos = new ArrayList<>();
            for (Holding h : holdings) {
                try {
                    infos.add(api.fetch(h.getStockCode()).get());
                } catch (Exception e) {
                    infos.add(null);
                }
            }

            runSync(() -> {
                // 行数：持仓行 + 1 行底部 (关闭按钮 + 装饰)
                int stockRows = (int) Math.ceil(holdings.size() / 9.0);
                int rows = Math.min(6, stockRows + 1);
                int size = rows * 9;

                Inventory inv = Bukkit.createInventory(null, size,
                        Component.text(GUI_TITLE_PREFIX).color(NamedTextColor.GOLD)
                                .decorate(TextDecoration.BOLD));

                for (int i = 0; i < holdings.size(); i++) {
                    inv.setItem(i, buildHoldingItem(holdings.get(i), infos.get(i)));
                }

                // 底部行填充灰色玻璃板 + 右下角关闭按钮 + 左下角返回主菜单
                for (int s = size - 9; s < size - 1; s++) {
                    inv.setItem(s, buildPane(Material.BLACK_STAINED_GLASS_PANE, Component.empty()));
                }
                ItemStack close = buildPane(Material.BARRIER,
                        Component.text("关闭").color(NamedTextColor.RED));
                inv.setItem(size - 1, close);
                // 返回统一主菜单，保持各 GUI 之间可以互相跳转。
                ItemStack back = buildPane(Material.OAK_DOOR,
                        Component.text("返回主菜单").color(NamedTextColor.YELLOW));
                inv.setItem(size - 9, back);

                VIEWERS.add(player.getUniqueId());
                player.openInventory(inv);
            });
        });
    }

    private ItemStack buildHoldingItem(Holding h, StockInfo info) {
        double avgCost = h.getAvgCost() * config.getPriceRatio();
        double currentPrice = info != null ? info.getPrice() * config.getPriceRatio() : 0.0;
        double changePct = avgCost > 0 && info != null
                ? (currentPrice - avgCost) / avgCost * 100.0
                : 0.0;
        boolean up = changePct >= 0;

        Material mat = up ? Material.LIME_DYE : Material.RED_DYE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        Component title = Component.text(h.getStockCode()).color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true);
        if (info != null) {
            title = title.append(Component.text("  " + info.getName())
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, false));
        }
        meta.displayName(title);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(loreLine("持仓数量", h.getAmount() + " 股", NamedTextColor.WHITE));
        lore.add(loreLine("持仓成本", round2(avgCost), NamedTextColor.WHITE));

        if (info != null) {
            lore.add(loreLine("当前价格", round2(currentPrice), NamedTextColor.WHITE));
            NamedTextColor changeColor = up ? NamedTextColor.GREEN : NamedTextColor.RED;
            String arrow = up ? "▲" : "▼";
            lore.add(loreLine("持仓盈亏", arrow + " " + round2(Math.abs(changePct)) + "%", changeColor));
            double totalValue = currentPrice * h.getAmount();
            double totalCost = avgCost * h.getAmount();
            double profit = totalValue - totalCost;
            lore.add(loreLine("浮动盈亏", (profit >= 0 ? "+" : "") + round2(profit), changeColor));
        }

        lore.add(Component.empty());
        lore.add(Component.text("点击查看详情").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, true));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component loreLine(String label, String value, NamedTextColor valueColor) {
        return Component.text(label + ": ").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(value).color(valueColor));
    }

    private static ItemStack buildPane(Material mat, Component name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static String round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void runSync(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
