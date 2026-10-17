package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.model.FuturesContract;
import dev.onelili.mstock.model.FuturesPosition;
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
 * 期货界面（54 格）。
 *
 * <ul>
 *   <li>顶部（0-8）：未到期合约，点击选中</li>
 *   <li>中部（19-25）：开多 / 开空 / 设置数量</li>
 *   <li>底部（36-44）：当前持仓，点击平仓</li>
 * </ul>
 *
 * <p>所有按钮都调用 {@code FuturesService} 的同一方法，
 * 与 {@code /mstock futures open|close} 完全等价。
 */
public class FuturesGui {

    public static final String TITLE = "期货合约";

    /** 合约选择区占满 0..8。 */
    public static final int CONTRACT_SLOTS = 9;
    /** 选中合约的信息放在 13，避免与合约区（0..8）抢槽位。 */
    public static final int SLOT_SELECTED_INFO = 13;
    public static final int SLOT_CLOSE_LONG = 19;
    public static final int SLOT_OPEN_LONG = 20;
    public static final int SLOT_OPEN_SHORT = 24;
    public static final int SLOT_CLOSE_SHORT = 25;
    public static final int SLOT_QUANTITY = 22;
    /** 图表放在 31：持仓列表占用 36..44，两者不能重叠。 */
    public static final int SLOT_CHART = 31;
    /** 持仓列表区。 */
    public static final int POSITION_START = 36;
    public static final int POSITION_SLOTS = 9;

    private final MineStock plugin;

    public FuturesGui(MineStock plugin) {
        this.plugin = plugin;
    }

    /** 打开界面：先渲染"加载中…"，合约与持仓异步补齐。 */
    public void open(Player player, String selectedSymbol, double quantity) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.FUTURES)
                .symbol(selectedSymbol)
                .quantity(quantity <= 0 ? 1.0 : quantity);
        Inventory inventory = GuiItems.create(holder, TITLE, NamedTextColor.LIGHT_PURPLE);
        renderLoading(inventory);
        player.openInventory(inventory);
        reload(player, holder);
    }

    /** 重新加载合约目录与持仓列表。 */
    public void reload(Player player, GuiHolder holder) {
        var service = plugin.getFuturesService();
        if (service == null) return;
        service.activeContracts().thenCompose(contracts ->
                service.positions(player.getUniqueId()).thenApply(positions -> {
                    render(player, holder, contracts, positions, null);
                    return null;
                })
        ).exceptionally(error -> {
            plugin.getLogger().warning("[MineStock] 加载期货界面失败: " + error.getMessage());
            return null;
        });
    }

    private void renderLoading(Inventory inventory) {
        inventory.setItem(SLOT_SELECTED_INFO, GuiItems.info(Material.CLOCK,
                Component.text("加载中…").color(NamedTextColor.YELLOW),
                List.of(GuiItems.label("正在获取合约与持仓"))));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    private void render(Player player, GuiHolder holder, List<FuturesContract> contracts,
                        List<FuturesPosition> positions, Double ignoredPrice) {
        runOnPlayer(player, () -> {
            Player current = Bukkit.getPlayer(player.getUniqueId());
            if (current == null || !current.isOnline()) return;
            if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;

            Inventory inventory = holder.getInventory();
            for (int slot = 0; slot < CONTRACT_SLOTS; slot++) inventory.setItem(slot, null);
            for (int slot = POSITION_START; slot < POSITION_START + POSITION_SLOTS; slot++) {
                inventory.setItem(slot, null);
            }

            String selected = holder.getSymbol();
            if (selected == null && !contracts.isEmpty()) {
                selected = contracts.get(0).getSymbol();
                holder.symbol(selected);
            }

            int shown = Math.min(CONTRACT_SLOTS, contracts.size());
            for (int i = 0; i < shown; i++) {
                FuturesContract contract = contracts.get(i);
                boolean isSelected = contract.getSymbol().equals(selected);
                inventory.setItem(i, buildContractItem(contract, isSelected));
            }

            FuturesContract active = findContract(contracts, selected);
            double quantity = holder.getQuantity();

            List<Component> infoLore = new ArrayList<>();
            if (active == null) {
                infoLore.add(GuiItems.label("请先点击顶部选择一个合约"));
            } else {
                infoLore.add(GuiItems.line("合约", active.getSymbol(), NamedTextColor.WHITE));
                infoLore.add(GuiItems.line("到期日", active.getExpiryDate(), NamedTextColor.GRAY));
                infoLore.add(GuiItems.line("合约乘数", format2(active.getContractSize()),
                        NamedTextColor.WHITE));
                infoLore.add(GuiItems.line("最小变动", format4(active.getTickSize()),
                        NamedTextColor.WHITE));
                infoLore.add(GuiItems.line("初始保证金率",
                        format2(active.getInitialMarginRate() * 100.0) + "%", NamedTextColor.WHITE));
                infoLore.add(GuiItems.line("默认杠杆", active.getLeverage() + "x",
                        NamedTextColor.AQUA));
                infoLore.add(GuiItems.line("数量", formatQty(quantity), NamedTextColor.WHITE));
            }

            inventory.setItem(SLOT_SELECTED_INFO, GuiItems.info(Material.CLOCK,
                    Component.text(active == null ? "未选择合约" : active.getSymbol())
                            .color(NamedTextColor.LIGHT_PURPLE),
                    infoLore));

            boolean tradable = active != null;
            inventory.setItem(SLOT_CLOSE_LONG, GuiItems.button(
                    tradable ? Material.LIGHT_BLUE_DYE : Material.GRAY_DYE,
                    Component.text("平多").color(NamedTextColor.AQUA),
                    List.of(
                            GuiItems.label(tradable
                                    ? "平掉 " + active.getSymbol() + " 的全部多头持仓"
                                    : "请先选择合约"),
                            GuiItems.label("按合约与方向平仓，不动空单"),
                            GuiItems.label("权限 minestock.futures.trade"))));

            inventory.setItem(SLOT_OPEN_LONG, GuiItems.button(
                    tradable ? Material.LIME_DYE : Material.GRAY_DYE,
                    Component.text("开多").color(NamedTextColor.GREEN),
                    List.of(
                            GuiItems.line("数量", formatQty(quantity), NamedTextColor.WHITE),
                            GuiItems.label(tradable
                                    ? "等价于 /mstock futures open "
                                            + active.getSymbol() + " long " + formatQty(quantity)
                                    : "请先选择合约"),
                            GuiItems.label("权限 minestock.futures.trade"))));

            inventory.setItem(SLOT_OPEN_SHORT, GuiItems.button(
                    tradable ? Material.RED_DYE : Material.GRAY_DYE,
                    Component.text("开空").color(NamedTextColor.RED),
                    List.of(
                            GuiItems.line("数量", formatQty(quantity), NamedTextColor.WHITE),
                            GuiItems.label(tradable
                                    ? "等价于 /mstock futures open "
                                            + active.getSymbol() + " short " + formatQty(quantity)
                                    : "请先选择合约"),
                            GuiItems.label("权限 minestock.futures.trade"))));

            inventory.setItem(SLOT_CLOSE_SHORT, GuiItems.button(
                    tradable ? Material.MAGENTA_DYE : Material.GRAY_DYE,
                    Component.text("平空").color(NamedTextColor.LIGHT_PURPLE),
                    List.of(
                            GuiItems.label(tradable
                                    ? "平掉 " + active.getSymbol() + " 的全部空头持仓"
                                    : "请先选择合约"),
                            GuiItems.label("按合约与方向平仓，不动多单"),
                            GuiItems.label("权限 minestock.futures.trade"))));

            inventory.setItem(SLOT_QUANTITY, GuiItems.button(Material.PAPER,
                    Component.text("数量 " + formatQty(quantity)).color(NamedTextColor.YELLOW),
                    List.of(
                            GuiItems.label("左键 +1　右键 -1"),
                            GuiItems.label("Shift+左键 +10　Shift+右键 -10"),
                            GuiItems.label("中键 / 其他：聊天框输入数量"))));

            inventory.setItem(SLOT_CHART, GuiItems.button(Material.FILLED_MAP,
                    Component.text("查看K线").color(NamedTextColor.LIGHT_PURPLE),
                    List.of(GuiItems.label(tradable
                            ? "等价于 /mstock chart " + active.getSymbol()
                            : "请先选择合约"))));

            // 底部持仓列表：点击即平仓。
            int shownPositions = Math.min(POSITION_SLOTS, positions.size());
            for (int i = 0; i < shownPositions; i++) {
                inventory.setItem(POSITION_START + i, buildPositionItem(positions.get(i)));
            }
            if (positions.isEmpty()) {
                inventory.setItem(POSITION_START, GuiItems.info(Material.GRAY_STAINED_GLASS_PANE,
                        Component.text("暂无期货持仓").color(NamedTextColor.GRAY), List.of()));
            }

            inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
            GuiItems.fillEmpty(inventory, 0, inventory.getSize());
        });
    }

    private ItemStack buildContractItem(FuturesContract contract, boolean selected) {
        Material material = selected ? Material.NETHER_STAR : Material.QUARTZ;
        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("到期日", contract.getExpiryDate(), NamedTextColor.GRAY));
        lore.add(GuiItems.line("合约乘数", format2(contract.getContractSize()), NamedTextColor.WHITE));
        lore.add(GuiItems.line("最小变动", format4(contract.getTickSize()), NamedTextColor.WHITE));
        lore.add(GuiItems.line("默认杠杆", contract.getLeverage() + "x", NamedTextColor.AQUA));
        lore.add(GuiItems.label(selected ? "已选中" : "点击选中"));
        return GuiItems.button(material,
                Component.text(contract.getSymbol())
                        .color(selected ? NamedTextColor.GOLD : NamedTextColor.WHITE),
                lore);
    }

    /** 持仓项：点击即调用 {@code FuturesService#close}。 */
    private ItemStack buildPositionItem(FuturesPosition position) {
        boolean isLong = position.getDirection() == dev.onelili.mstock.model.TradeDirection.LONG;
        Material material = isLong ? Material.LIME_DYE : Material.RED_DYE;
        double pnl = position.getRealizedPnl();
        boolean profit = pnl >= 0;

        return GuiItems.button(material,
                Component.text("#" + position.getId() + " " + position.getSymbol())
                        .color(NamedTextColor.WHITE),
                List.of(
                        GuiItems.line("方向", isLong ? "多" : "空",
                                isLong ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.line("数量", formatQty(position.getQuantity()), NamedTextColor.WHITE),
                        GuiItems.line("开仓价", format6(position.getEntryPrice()),
                                NamedTextColor.WHITE),
                        GuiItems.line("保证金", format2(position.getMargin()), NamedTextColor.WHITE),
                        GuiItems.line("已盯市盈亏",
                                (profit ? "+" : "") + format2(pnl),
                                profit ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.line("到期日", position.getExpiryDate(), NamedTextColor.GRAY),
                        Component.empty(),
                        GuiItems.label("点击平仓（等价于 /mstock futures close "
                                + position.getId() + "）")));
    }

    private static FuturesContract findContract(List<FuturesContract> contracts, String symbol) {
        if (symbol == null) return null;
        for (FuturesContract contract : contracts) {
            if (contract.getSymbol().equals(symbol)) return contract;
        }
        return null;
    }

    static String formatQty(double quantity) {
        return BigDecimal.valueOf(quantity).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String format4(double value) {
        if (!Double.isFinite(value)) return "0.0000";
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String format6(double value) {
        if (!Double.isFinite(value)) return "0.000000";
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private void runOnPlayer(Player player, Runnable task) {
        dev.onelili.mstock.scheduler.CompatScheduler.get().runOnEntity(plugin, player, () -> {
            if (plugin.isOperational() && player.isOnline()) task.run();
        });
    }
}
