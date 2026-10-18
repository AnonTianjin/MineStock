package dev.onelili.mstock.listener;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.ui.PortfolioGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class PortfolioGUIListener implements Listener {

    private final MineStock plugin;

    public PortfolioGUIListener(MineStock plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!PortfolioGUI.isViewer(player.getUniqueId())) return;

        Component viewTitle = event.getView().title();
        String titlePlain = PlainTextComponentSerializer.plainText().serialize(viewTitle);
        if (!titlePlain.startsWith(PortfolioGUI.GUI_TITLE_PREFIX)) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        Component displayName = meta.displayName();
        if (displayName == null) return;

        String name = PlainTextComponentSerializer.plainText().serialize(displayName);

        if (name.contains("关闭")) {
            player.closeInventory();
            return;
        }

        // 返回统一主菜单（与 /mstock menu 等价）
        if (name.contains("返回主菜单")) {
            player.closeInventory();
            player.performCommand("mstock menu");
            return;
        }

        // 如果是股票持仓项（根据 lore 判断），提取股票代码
        if (!meta.hasLore()) return;
        String stockCode = extractStockCode(name);
        if (stockCode == null || stockCode.isEmpty()) return;

        player.closeInventory();
        player.performCommand("mstock " + stockCode);
    }

    /**
     * 旧版持仓界面由 {@code Bukkit.createInventory(null, ...)} 创建（无 GuiHolder），
     * {@code TradingGuiListener.onDrag} 覆盖不到它 —— 不拦截的话玩家可把随身物品
     * 拖进界面格子，而这里的点击已被全量取消，物品再也拿不回来（重绘 / 关闭即消失）。
     *
     * <p>只拦拖进<b>界面格子</b>的部分：玩家开着界面时在自己背包内拖动物品仍被允许。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!PortfolioGUI.isViewer(player.getUniqueId())) return;

        Component viewTitle = event.getView().title();
        String titlePlain = PlainTextComponentSerializer.plainText().serialize(viewTitle);
        if (!titlePlain.startsWith(PortfolioGUI.GUI_TITLE_PREFIX)) return;

        int topSize = event.getInventory().getSize();
        boolean touchesGui = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (touchesGui) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Component title = event.getView().title();
        String titlePlain = PlainTextComponentSerializer.plainText().serialize(title);
        if (titlePlain.startsWith(PortfolioGUI.GUI_TITLE_PREFIX)) {
            PortfolioGUI.removeViewer(player.getUniqueId());
        }
    }

    private String extractStockCode(String displayName) {
        String[] parts = displayName.split("\\s+", 2);
        return parts.length > 0 ? parts[0].trim() : null;
    }
}
