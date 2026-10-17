package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * 统一主菜单：{@code /mstock} 无参调用时的入口。
 *
 * <p><b>只做入口，不放持仓明细</b>：持仓集中在 {@link PortfolioHubGui}，
 * 避免主菜单变成信息墙、也避免每次打开主菜单都要拉一遍行情。
 *
 * <p>布局（54 格）：
 * <ul>
 *   <li>20 杠杆设置 → {@link LeverageGui}</li>
 *   <li>22 持仓总览 → {@link PortfolioHubGui}（居中，最常用）</li>
 *   <li>24 外汇交易 → {@link ForexGui}</li>
 *   <li>29 期货合约 → {@link FuturesGui}</li>
 *   <li>31 指令帮助，33 模拟盘，35 模拟盘管理（仅 op），49 关闭</li>
 * </ul>
 *
 * <p>不再提供"股票持仓（原版界面）"入口 —— 持仓统一走持仓总览。
 * {@code /mstock pg} 指令本身仍然可用，只是不占主菜单槽位。
 */
public class MainMenuGui {

    public static final String TITLE = "MineStock 主菜单";

    private final MineStock plugin;

    public MainMenuGui(MineStock plugin) {
        this.plugin = plugin;
    }

    /** 在玩家线程打开主菜单。 */
    public void open(Player player) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.MAIN_MENU);
        Inventory inventory = GuiItems.create(holder, TITLE, NamedTextColor.GOLD);

        inventory.setItem(GuiItems.MENU_PORTFOLIO_HUB, GuiItems.button(Material.CHEST,
                Component.text("持仓总览").color(NamedTextColor.GOLD),
                List.of(
                        GuiItems.label("股票 / 保证金 / 期货 集中查看"),
                        GuiItems.label("含市值、浮动盈亏与保证金率"),
                        GuiItems.label("等价于 /mstock positions"))));

        inventory.setItem(GuiItems.MENU_LEVERAGE, GuiItems.button(Material.ANVIL,
                Component.text("杠杆设置").color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.label("调整个人杠杆倍数"),
                        GuiItems.label("等价于 /mstock leverage <倍数>"),
                        GuiItems.label("权限 minestock.leverage.set"))));

        inventory.setItem(GuiItems.MENU_FOREX, GuiItems.button(Material.GOLD_INGOT,
                Component.text("外汇 / 虚拟货币").color(NamedTextColor.GOLD),
                List.of(
                        GuiItems.label("国家外汇与虚拟货币分为两个板块"),
                        GuiItems.label("支持模糊搜索品种"),
                        GuiItems.label("买卖等价于 /mstock forex buy|sell"),
                        GuiItems.label("权限 minestock.forex.trade"))));

        inventory.setItem(GuiItems.MENU_FUTURES, GuiItems.button(Material.CLOCK,
                Component.text("期货合约").color(NamedTextColor.LIGHT_PURPLE),
                List.of(
                        GuiItems.label("开多 / 开空 / 平仓"),
                        GuiItems.label("每日盯市，到期自动平仓"),
                        GuiItems.label("权限 minestock.futures.trade"))));

        inventory.setItem(GuiItems.MENU_SIMULATION, GuiItems.button(Material.PAPER,
                Component.text("模拟盘").color(NamedTextColor.GREEN),
                List.of(
                        GuiItems.label("用虚拟代码 SIM001~SIM010 练习交易"),
                        GuiItems.label("价格由本地模型实时生成"),
                        GuiItems.label("等价于 /mstock simulation"),
                        GuiItems.label("权限 minestock.simulation.use"))));

        // 只有 op 才看到管理台入口；无权限时该槽保持为 null，随后被玻璃板填充。
        if (player.hasPermission("minestock.simulation.admin")) {
            inventory.setItem(GuiItems.MENU_SIMULATION_ADMIN, GuiItems.button(Material.COMMAND_BLOCK,
                    Component.text("模拟盘管理").color(NamedTextColor.LIGHT_PURPLE),
                    List.of(
                            GuiItems.label("定值 / 目标涨幅 / 停牌 / 联动 / 定时"),
                            GuiItems.label("仅供管理员"),
                            GuiItems.label("权限 minestock.simulation.admin"))));
        }

        inventory.setItem(GuiItems.MENU_HELP, GuiItems.button(Material.BOOK,
                Component.text("指令帮助").color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.label("查看全部指令"),
                        GuiItems.label("等价于 /mstock help"))));

        inventory.setItem(GuiItems.SLOT_CLOSE, GuiItems.close());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());

        player.openInventory(inventory);
    }
}
