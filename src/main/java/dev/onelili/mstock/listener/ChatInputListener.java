package dev.onelili.mstock.listener;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.ui.PendingAction;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 聊天框数量输入。
 *
 * <p>股票买卖（BUY/SELL）沿用原有的"输入数量 → 确认页"流程；
 * GUI 的数量输入（FOREX_* / FUTURES_OPEN）复用同一会话通道，
 * 由 {@code MStockCommand#consumeQuantityInput} 消费并回调刷新界面。
 *
 * <p>股票数量是整数，外汇 / 期货数量允许小数。
 */
public class ChatInputListener implements Listener {
    private final MineStock plugin;
    private final ChatInputSession session;

    public ChatInputListener(MineStock plugin, ChatInputSession session) {
        this.plugin = plugin;
        this.session = session;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingAction action = session.getSession(player.getUniqueId());
        if (action == null) return;

        event.setCancelled(true);
        // AsyncChatEvent carries a Component; extract plain text for parsing
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        if ("取消".equalsIgnoreCase(input) || "cancel".equalsIgnoreCase(input)) {
            session.clearSession(player.getUniqueId());
            plugin.getCommandExecutor().cancelQuantityInput(player);
            plugin.getLang().send(player, "input-cancelled");
            return;
        }

        // 品种搜索：纯文本，不做数字校验。
        if (action.isTextInput()) {
            action.setTextInput(input);
            CompatScheduler.get().runOnEntity(plugin, player,
                    () -> plugin.getCommandExecutor().consumeSymbolSearch(player, input));
            return;
        }

        // GUI 数量输入：允许小数（外汇手数 / 加密币数量 / 期货手数）。
        if (!action.isStockTrade()) {
            double decimal;
            try {
                decimal = Double.parseDouble(input);
                if (!Double.isFinite(decimal) || decimal <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                plugin.getLang().send(player, "input-invalid");
                return;
            }
            action.setDecimalAmount(decimal);
            CompatScheduler.get().runOnEntity(plugin, player,
                    () -> plugin.getCommandExecutor().consumeQuantityInput(player, action, decimal));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(input);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getLang().send(player, "input-invalid");
            return;
        }

        // Store the amount and move the session to the confirm-waiting stage.
        action.setAmount(amount);

        // Switch to player's region thread (Folia) or main thread (Paper/Spigot) to show confirm.
        CompatScheduler.get().runOnEntity(plugin, player, () ->
                plugin.getCommandExecutor().showTradeConfirm(player, action));
    }
}
