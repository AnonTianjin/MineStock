package dev.onelili.mstock.listener;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.leverage.PlayerLeverageManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 登录加载、退出保存玩家个人杠杆倍数。 */
public class PlayerLeverageListener implements Listener {

    private final MineStock plugin;

    public PlayerLeverageListener(MineStock plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        PlayerLeverageManager manager = plugin.getLeverageManager();
        if (manager == null) return;
        // 数据库读取放到异步线程，避免登录时卡主线程。
        plugin.getTaskManager().runAsync(() -> manager.load(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerLeverageManager manager = plugin.getLeverageManager();
        if (manager != null) {
            manager.saveAndEvict(event.getPlayer().getUniqueId());
        }
        // 释放图表冷却记录：不清理的话该 Map 会随玩家数单调增长（慢泄漏）。
        dev.onelili.mstock.chart.ChartService charts = plugin.getChartService();
        if (charts != null) charts.clearCooldown(event.getPlayer().getUniqueId());
        // 释放 GUI 回调与返回目标（数量输入 / 搜索 / 图表返回）：
        // 回调 lambda 强引用 Player 与 Inventory，不清理会让已退出玩家无法 GC。
        dev.onelili.mstock.command.MStockCommand executor = plugin.getCommandExecutor();
        if (executor != null) executor.clearPlayerState(event.getPlayer().getUniqueId());
    }
}
