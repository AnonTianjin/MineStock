package dev.onelili.mstock.listener;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.command.MStockCommand;
import dev.onelili.mstock.leverage.TradeOutcome;
import dev.onelili.mstock.model.ForexCategory;
import dev.onelili.mstock.model.TradeDirection;
import dev.onelili.mstock.simulation.SimulationService;
import dev.onelili.mstock.ui.gui.ForexGui;
import dev.onelili.mstock.ui.gui.FuturesGui;
import dev.onelili.mstock.ui.gui.GuiHolder;
import dev.onelili.mstock.ui.gui.GuiItems;
import dev.onelili.mstock.ui.gui.LeverageGui;
import dev.onelili.mstock.ui.gui.MainMenuGui;
import dev.onelili.mstock.ui.gui.PortfolioHubGui;
import dev.onelili.mstock.ui.gui.PositionMenuGui;
import dev.onelili.mstock.ui.gui.SimulationAdminGui;
import dev.onelili.mstock.ui.gui.SimulationGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.concurrent.CompletableFuture;

/**
 * 交易类 GUI 的统一点击分发。
 *
 * <p>识别方式基于 {@link InventoryHolder}，与原有的
 * {@code PortfolioGUIListener}（基于标题前缀 + 静态 viewer 集合）互不干扰。
 *
 * <p><b>GUI 与指令等价</b>：这里只做"槽位 → Service 调用"的映射，
 * 不复制任何业务逻辑；提示文案也由 Service 返回的语言键决定，
 * 因此点击 GUI 与手输指令的结果、权限校验与冷却行为完全一致。
 */
public class TradingGuiListener implements Listener {

    private final MineStock plugin;
    private final MStockCommand command;

    public TradingGuiListener(MineStock plugin, MStockCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        GuiHolder holder = resolveHolder(event.getView().getTopInventory());
        if (holder == null) return;

        // 交易类 GUI 一律只读：禁止取出、放入、Shift 转移与数字键交换。
        event.setCancelled(true);
        if (event.getClickedInventory() == null) return;
        if (!holder.isBoundTo(event.getClickedInventory())) return;

        int slot = event.getRawSlot();
        ClickType click = event.getClick();
        switch (holder.getType()) {
            case MAIN_MENU -> handleMainMenu(player, holder, slot);
            case PORTFOLIO_HUB -> handlePortfolioHub(player, holder, slot, click);
            case POSITION_MENU -> handlePositionMenu(player, holder, slot);
            case LEVERAGE -> handleLeverage(player, holder, slot);
            case FOREX_CATEGORY -> handleForexCategory(player, holder, slot);
            case FOREX_MARKET -> handleForexMarket(player, holder, slot);
            case FOREX_TRADE -> handleForexTrade(player, holder, slot, click);
            case FUTURES -> handleFutures(player, holder, slot, click);
            case SIMULATION -> handleSimulation(player, holder, slot);
            case SIMULATION_TRADE -> handleSimulationTrade(player, holder, slot, click);
            case SIMULATION_ADMIN -> handleSimulationAdmin(player, holder, slot);
        }
    }

    /**
     * 「数量」按钮的共享点击处理：先按点击方式做整数步进，步进不了才回落到聊天输入。
     *
     * <p>步进成功后<b>原地重绘</b>当前界面而不重开 inventory，
     * 否则连续点击会不断重建界面，鼠标位置与滚动状态都会被打断。
     *
     * @param rerender      步进成功后的原地重绘动作
     * @param openChatInput 点击方式无法表达步进时的兜底（复用原仓库聊天输入通道）
     */
    private void handleQuantityClick(GuiHolder holder, ClickType click,
                                     Runnable rerender, Runnable openChatInput) {
        if (holder.adjustQuantity(click.isLeftClick(), click.isRightClick(), click.isShiftClick())) {
            rerender.run();
            return;
        }
        openChatInput.run();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (resolveHolder(event.getView().getTopInventory()) == null) return;
        event.setCancelled(true);
    }

    private GuiHolder resolveHolder(Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof GuiHolder guiHolder ? guiHolder : null;
    }

    // ── 主菜单 ────────────────────────────────────────────────────────────────

    private void handleMainMenu(Player player, GuiHolder holder, int slot) {
        if (slot == GuiItems.MENU_PORTFOLIO_HUB) {
            // 持仓总览：独立界面，等价于 /mstock positions
            new PortfolioHubGui(plugin).open(player);
            return;
        }
        if (slot == GuiItems.MENU_LEVERAGE) {
            // 等价于 /mstock leverage
            new LeverageGui(plugin).open(player);
            return;
        }
        if (slot == GuiItems.MENU_FOREX) {
            // 等价于 /mstock forex
            new ForexGui(plugin).openCategory(player);
            return;
        }
        if (slot == GuiItems.MENU_FUTURES) {
            // 等价于 /mstock futures positions
            new FuturesGui(plugin).open(player, null, 1.0);
            return;
        }
        if (slot == GuiItems.MENU_HELP) {
            player.closeInventory();
            player.performCommand("mstock help");
            return;
        }
        if (slot == GuiItems.MENU_SIMULATION) {
            // 等价于 /mstock simulation
            if (!player.hasPermission("minestock.simulation.use")) {
                player.sendMessage(plugin.getLang().get("no-permission"));
                return;
            }
            new SimulationGui(plugin).open(player, 0);
            return;
        }
        if (slot == GuiItems.MENU_SIMULATION_ADMIN) {
            // 等价于 /mstock simulation admin
            if (!player.hasPermission("minestock.simulation.admin")) {
                player.sendMessage(plugin.getLang().get("no-permission"));
                return;
            }
            new SimulationAdminGui(plugin).open(player, 0);
            return;
        }
        if (slot == GuiItems.SLOT_CLOSE) {
            player.closeInventory();
        }
    }

    // ── 持仓总览 ──────────────────────────────────────────────────────────────

    /**
     * 持仓总览界面。
     *
     * <p>条目点击分两档：股票持仓<b>左键</b>进 {@link PositionMenuGui} 操作子菜单
     * （卖出 / 查看K线 / 详情），<b>右键</b>保留原来"直接跳详情"的路径。
     * 保证金 → {@code /mstock margin}，期货 → {@code /mstock futures positions}。
     */
    private void handlePortfolioHub(Player player, GuiHolder holder, int slot, ClickType click) {
        GuiHolder.Target target = holder.targetAt(slot);
        if (target != null) {
            // 左键 = 对这笔持仓做点什么；右键 = 只看不动。
            // 两种意图都保留，比强行收成一种更不容易误触。
            if (click.isLeftClick() && target.code() != null) {
                if (GuiHolder.Target.KIND_STOCK.equals(target.kind())) {
                    new PositionMenuGui(plugin).open(player, target.code(), holder.getPage());
                    return;
                }
                if (GuiHolder.Target.KIND_MARGIN.equals(target.kind())) {
                    new PositionMenuGui(plugin).openMargin(
                            player, target.id(), target.code(), holder.getPage());
                    return;
                }
                if (GuiHolder.Target.KIND_SIM.equals(target.kind())) {
                    new PositionMenuGui(plugin).openSimulation(
                            player, target.code(), holder.getPage());
                    return;
                }
            }
            routeToPosition(player, target);
            return;
        }
        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        if (slot == PortfolioHubGui.SLOT_SUMMARY) {
            player.closeInventory();
            player.performCommand("mstock margin");
            return;
        }
        if (slot == PortfolioHubGui.SLOT_REFRESH) {
            new PortfolioHubGui(plugin).open(player, holder.getPage());
            return;
        }
        if (slot == PortfolioHubGui.SLOT_PREV) {
            new PortfolioHubGui(plugin).open(player, Math.max(0, holder.getPage() - 1));
            return;
        }
        if (slot == PortfolioHubGui.SLOT_NEXT) {
            new PortfolioHubGui(plugin).open(player, holder.getPage() + 1);
        }
    }

    /** 概览条目点击 → 跳到与指令等价的详情展示。 */
    private void routeToPosition(Player player, GuiHolder.Target target) {
        player.closeInventory();
        switch (target.kind()) {
            // 股票：与 /mstock <代码> 等价
            case GuiHolder.Target.KIND_STOCK -> {
                if (target.code() != null) player.performCommand("mstock " + target.code());
            }
            // 期货：与 /mstock futures positions 等价
            case GuiHolder.Target.KIND_FUTURES -> player.performCommand("mstock futures positions");
            // 模拟盘：与 /mstock simulation <代码> 等价
            case GuiHolder.Target.KIND_SIM -> {
                if (target.code() != null) player.performCommand("mstock simulation " + target.code());
            }
            // 保证金 / 外汇：与 /mstock margin 等价
            default -> player.performCommand("mstock margin");
        }
    }

    // ── 单个持仓的操作子菜单 ──────────────────────────────────────────────────

    /**
     * 持仓操作子菜单。
     *
     * <p>三个动作都通过玩家指令触发，而不是直接调服务：界面与聊天指令只留一条
     * 代码路径，权限校验、冷却、模拟盘分流自然一致，不会出现"从界面能绕开检查"
     * 的缺口。
     */
    private void handlePositionMenu(Player player, GuiHolder holder, int slot) {
        if (slot == PositionMenuGui.SLOT_BACK) {
            new PortfolioHubGui(plugin).open(player, holder.getPage());
            return;
        }
        String code = holder.getSymbol();
        GuiHolder.Target target = holder.getFocusTarget();
        boolean margin = target != null && GuiHolder.Target.KIND_MARGIN.equals(target.kind());
        boolean simulation = target != null && GuiHolder.Target.KIND_SIM.equals(target.kind());
        if (code == null || code.isBlank() || target == null) {
            player.closeInventory();
            return;
        }
        switch (slot) {
            case PositionMenuGui.SLOT_SELL -> {
                player.closeInventory();
                if (margin) {
                    // 保证金仓位没有"部分平仓"：按下即整笔结算，所以直接用主键
                    player.performCommand("mstock margin close " + target.id());
                } else if (simulation) {
                    // 模拟盘走自己的指令：绝不落到真实持仓路径上
                    player.performCommand("mstock simulation sell " + code);
                } else {
                    // 数量仍走聊天栏：卖出没有"安全默认量"，替玩家猜一个反而危险
                    player.performCommand("mstock sell " + code);
                }
            }
            case PositionMenuGui.SLOT_CHART -> {
                // 先登记返回目标，再执行图表指令：K线看完可按提示一键回持仓总览
                command.rememberChartReturn(player, "mstock positions", "持仓总览");
                player.closeInventory();
                player.performCommand("mstock chart " + code);
            }
            case PositionMenuGui.SLOT_DETAIL -> {
                player.closeInventory();
                player.performCommand(margin ? "mstock margin"
                        : simulation ? "mstock simulation " + code
                        : "mstock " + code);
            }
            default -> {
                // 持仓信息条与填充格不做任何事
            }
        }
    }

    // ── 杠杆 ──────────────────────────────────────────────────────────────────

    private void handleLeverage(Player player, GuiHolder holder, int slot) {
        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        // 页签切换 + 六个调节按钮 + 重置全部走 LeverageGui#handleAdjust，
        // 由它按当前制度分派到 setLeverage / setStockLeverage，
        // 与 /mstock leverage[ stock] <倍数> 是同一条路径。
        new LeverageGui(plugin).handleAdjust(player, holder, holder.getInventory(), slot);
    }

    // ── 外汇板块页 ────────────────────────────────────────────────────────────

    /**
     * 板块选择页：国家外汇 / 虚拟货币 / 全部 / 搜索。
     *
     * <p>两个板块是并列的独立入口，各自进入只含本板块品种的行情页。
     */
    private void handleForexCategory(Player player, GuiHolder holder, int slot) {
        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        if (slot == ForexGui.SLOT_CAT_FIAT) {
            new ForexGui(plugin).openMarket(player, ForexCategory.FIAT, "", 0);
            return;
        }
        if (slot == ForexGui.SLOT_CAT_CRYPTO) {
            new ForexGui(plugin).openMarket(player, ForexCategory.CRYPTO, "", 0);
            return;
        }
        if (slot == ForexGui.SLOT_CAT_ALL) {
            new ForexGui(plugin).openMarket(player, null, "", 0);
            return;
        }
        if (slot == ForexGui.SLOT_CAT_SEARCH) {
            startSymbolSearch(player, null);
        }
    }

    /** 打开聊天框关键词输入；回调里用当前板块过滤。 */
    private void startSymbolSearch(Player player, ForexCategory category) {
        command.startSymbolSearchInput(player,
                keyword -> new ForexGui(plugin).openMarket(player, category, keyword, 0));
    }

    // ── 外汇行情页 ────────────────────────────────────────────────────────────

    private void handleForexMarket(Player player, GuiHolder holder, int slot) {
        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new ForexGui(plugin).openCategory(player);
            return;
        }
        if (slot == ForexGui.SLOT_REFRESH) {
            new ForexGui(plugin).reload(player, holder);
            return;
        }
        if (slot == ForexGui.SLOT_SEARCH) {
            // 保留当前板块，关键词为空时等于"清空搜索"
            startSymbolSearch(player, holder.getCategory());
            return;
        }
        if (slot == ForexGui.SLOT_CLEAR_SEARCH) {
            new ForexGui(plugin).openMarket(player, holder.getCategory(), "", 0);
            return;
        }
        if (slot == ForexGui.SLOT_PREV_PAGE) {
            holder.page(Math.max(0, holder.getPage() - 1));
            new ForexGui(plugin).reload(player, holder);
            return;
        }
        if (slot == ForexGui.SLOT_NEXT_PAGE) {
            holder.page(holder.getPage() + 1);
            new ForexGui(plugin).reload(player, holder);
            return;
        }
        if (slot < 0 || slot >= ForexGui.SYMBOL_SLOTS) return;

        var clicked = holder.getInventory().getItem(slot);
        if (clicked == null || clicked.getItemMeta() == null
                || clicked.getItemMeta().displayName() == null) return;
        String symbol = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(clicked.getItemMeta().displayName()).strip();
        if (symbol.isEmpty() || symbol.contains("外汇行情")) return;

        // 带着板块 / 关键词 / 页码进交易页，"返回行情"才能回到原处。
        new ForexGui(plugin).openTrade(player, symbol, holder.getCategory(),
                holder.getKeyword(), holder.getPage());
    }

    // ── 外汇交易页 ────────────────────────────────────────────────────────────

    private void handleForexTrade(Player player, GuiHolder holder, int slot, ClickType click) {
        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        if (slot == ForexGui.SLOT_BACK_TO_MARKET) {
            // 回到来源板块 / 关键词 / 页码，而不是重置到默认板块。
            new ForexGui(plugin).openMarket(player, holder.getCategory(),
                    holder.getKeyword(), holder.getPage());
            return;
        }
        if (slot == ForexGui.SLOT_QUANTITY) {
            // 左键 +1 / 右键 -1 / Shift 左键 +10：够用的步长直接原地改；
            // 需要精确小数（或中键等）时回落到原聊天输入通道，两条路共用同一份校验。
            handleQuantityClick(holder, click,
                    () -> {
                        new ForexGui(plugin).renderTrade(player, holder);
                        new ForexGui(plugin).refreshQuote(player, holder);
                    },
                    () -> command.startQuantityInput(player, holder.getSymbol(),
                            dev.onelili.mstock.ui.PendingAction.Type.FOREX_BUY,
                            quantity -> {
                                holder.quantity(quantity);
                                new ForexGui(plugin).renderTrade(player, holder);
                                new ForexGui(plugin).refreshQuote(player, holder);
                            }));
            return;
        }
        if (slot == ForexGui.SLOT_CHART) {
            // 等价于 /mstock forex chart <symbol>
            player.closeInventory();
            player.performCommand("mstock forex chart " + holder.getSymbol());
            return;
        }
        if (slot == ForexGui.SLOT_BUY) {
            tradeForex(player, holder, TradeDirection.LONG);
            return;
        }
        if (slot == ForexGui.SLOT_SELL) {
            tradeForex(player, holder, TradeDirection.SHORT);
        }
    }

    private void tradeForex(Player player, GuiHolder holder, TradeDirection direction) {
        if (!player.hasPermission("minestock.forex.trade")) {
            player.sendMessage(plugin.getLang().get("no-permission"));
            return;
        }
        var service = plugin.getForexService();
        if (service == null) return;
        double quantity = holder.getQuantity() > 0 ? holder.getQuantity() : 1.0;
        String symbol = holder.getSymbol();

        CompletableFuture<TradeOutcome> pending = direction == TradeDirection.LONG
                ? service.buy(player.getUniqueId(), symbol, quantity)
                : service.sell(player.getUniqueId(), symbol, quantity);
        sendOutcome(player, pending);
    }

    // ── 期货 ──────────────────────────────────────────────────────────────────

    private void handleFutures(Player player, GuiHolder holder, int slot, ClickType click) {
        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        if (slot >= 0 && slot < FuturesGui.CONTRACT_SLOTS) {
            var clicked = holder.getInventory().getItem(slot);
            if (clicked == null || clicked.getItemMeta() == null
                    || clicked.getItemMeta().displayName() == null) return;
            String symbol = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(clicked.getItemMeta().displayName()).strip();
            if (symbol.isEmpty()) return;
            holder.symbol(symbol);
            new FuturesGui(plugin).reload(player, holder);
            return;
        }
        if (slot == FuturesGui.SLOT_QUANTITY) {
            handleQuantityClick(holder, click,
                    () -> new FuturesGui(plugin).reload(player, holder),
                    () -> command.startQuantityInput(player, holder.getSymbol(),
                            dev.onelili.mstock.ui.PendingAction.Type.FUTURES_OPEN,
                            quantity -> {
                                holder.quantity(quantity);
                                new FuturesGui(plugin).reload(player, holder);
                            }));
            return;
        }
        if (slot == FuturesGui.SLOT_CHART) {
            String symbol = holder.getSymbol();
            if (symbol == null) return;
            // 登记返回目标：K 线末尾会附一条回到本页的指令
            command.rememberChartReturn(player, "mstock futures", "期货合约");
            player.closeInventory();
            player.performCommand("mstock chart " + symbol);
            return;
        }
        if (slot == FuturesGui.SLOT_OPEN_LONG) {
            openFutures(player, holder, TradeDirection.LONG);
            return;
        }
        if (slot == FuturesGui.SLOT_OPEN_SHORT) {
            openFutures(player, holder, TradeDirection.SHORT);
            return;
        }
        if (slot >= FuturesGui.POSITION_START
                && slot < FuturesGui.POSITION_START + FuturesGui.POSITION_SLOTS) {
            closeFutures(player, holder, slot);
        }
    }

    private void openFutures(Player player, GuiHolder holder, TradeDirection direction) {
        if (!player.hasPermission("minestock.futures.trade")) {
            player.sendMessage(plugin.getLang().get("no-permission"));
            return;
        }
        var service = plugin.getFuturesService();
        if (service == null) return;
        String symbol = holder.getSymbol();
        if (symbol == null) {
            player.sendMessage(plugin.getLang().get("futures-no-contract-selected"));
            return;
        }
        double quantity = holder.getQuantity() > 0 ? holder.getQuantity() : 1.0;
        sendOutcome(player, service.open(player.getUniqueId(), symbol, direction, quantity),
                () -> new FuturesGui(plugin).reload(player, holder));
    }

    private void closeFutures(Player player, GuiHolder holder, int slot) {
        if (!player.hasPermission("minestock.futures.trade")) {
            player.sendMessage(plugin.getLang().get("no-permission"));
            return;
        }
        var service = plugin.getFuturesService();
        if (service == null) return;

        var item = holder.getInventory().getItem(slot);
        Long positionId = extractPositionId(item);
        if (positionId == null) return;

        sendOutcome(player, service.close(player.getUniqueId(), positionId),
                () -> new FuturesGui(plugin).reload(player, holder));
    }

    /** 从物品名 {@code #12 EURUSD} 中解析持仓 ID。 */
    private static Long extractPositionId(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getItemMeta() == null || item.getItemMeta().displayName() == null) {
            return null;
        }
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(item.getItemMeta().displayName());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("#(\\d+)").matcher(plain);
        if (!matcher.find()) return null;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 模拟盘：行情列表页 ────────────────────────────────────────────────────

    /**
     * 模拟盘行情列表页：翻页 / 刷新 / 返回主菜单 / 点品种进交易页。
     *
     * <p>数据全在内存（{@code SimulationService}），无需异步拉取，也就没有
     * {@code ForexGui} 那种"点击 → 异步报价 → 重渲染"的两段式。
     */
    private void handleSimulation(Player player, GuiHolder holder, int slot) {
        if (!requireSimulationUse(player)) return;

        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        if (slot == SimulationGui.SLOT_PREV_PAGE) {
            holder.page(Math.max(0, holder.getPage() - 1));
            new SimulationGui(plugin).reload(player, holder);
            return;
        }
        if (slot == SimulationGui.SLOT_NEXT_PAGE) {
            holder.page(holder.getPage() + 1);
            new SimulationGui(plugin).reload(player, holder);
            return;
        }
        if (slot == SimulationGui.SLOT_REFRESH) {
            new SimulationGui(plugin).reload(player, holder);
            return;
        }
        if (slot < 0 || slot >= SimulationGui.LIST_SLOTS) return;

        String code = displayNameOf(holder.getInventory().getItem(slot));
        if (code == null || code.isEmpty()) return;
        // 带上来原页码，"返回行情"才能回到原处
        new SimulationGui(plugin).openTrade(player, code, holder.getPage());
    }

    // ── 模拟盘：交易页 ────────────────────────────────────────────────────────

    private void handleSimulationTrade(Player player, GuiHolder holder, int slot, ClickType click) {
        if (!requireSimulationUse(player)) return;

        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        if (slot == SimulationGui.SLOT_BACK_TO_LIST) {
            // 回到来源页码，而不是重置到第 1 页
            new SimulationGui(plugin).open(player, holder.getPage());
            return;
        }
        if (slot == SimulationGui.SLOT_QUANTITY) {
            // 模拟盘数量是整数（tradeSimulation 会 floor 取整），步进天然合适。
            // 兜底的聊天输入里填小数也安全：最终仍会被 floor 到整数。
            handleQuantityClick(holder, click,
                    () -> new SimulationGui(plugin).renderTrade(player, holder),
                    () -> command.startQuantityInput(player, holder.getSymbol(),
                            // FOREX_BUY 只是"非股票确认流"的标记，不新增 PendingAction.Type 值。
                            dev.onelili.mstock.ui.PendingAction.Type.FOREX_BUY,
                            quantity -> {
                                holder.quantity(quantity);
                                new SimulationGui(plugin).renderTrade(player, holder);
                            }));
            return;
        }
        if (slot == SimulationGui.SLOT_CHART) {
            String code = holder.getSymbol();
            if (code == null) return;
            // 等价于 /mstock chart <代码>（模拟源已在 index 0，K 线自动生效）
            // 同样登记返回目标：K 线末尾会附一条回到模拟盘行情页的指令
            command.rememberChartReturn(player, "mstock simulation list", "模拟盘行情");
            player.closeInventory();
            player.performCommand("mstock chart " + code);
            return;
        }
        if (slot == SimulationGui.SLOT_BUY) {
            tradeSimulation(player, holder, true);
            return;
        }
        if (slot == SimulationGui.SLOT_SELL) {
            tradeSimulation(player, holder, false);
        }
    }

    /** 交易页买卖：数量取 GUI 里记住的值（默认 1），结果统一走 {@code sendOutcome}。 */
    private void tradeSimulation(Player player, GuiHolder holder, boolean buy) {
        SimulationService service = plugin.getSimulationService();
        String code = holder.getSymbol();
        if (service == null || code == null) return;
        int amount = (int) Math.max(1.0, Math.floor(holder.getQuantity()));
        CompletableFuture<TradeOutcome> pending = buy
                ? service.buy(player.getUniqueId(), code, amount, 1)
                : service.sell(player.getUniqueId(), code, amount);
        if (buy) {
            sendOutcome(player, pending, () -> new SimulationGui(plugin).renderTrade(player, holder));
        } else {
            sendOutcome(player, pending);
        }
    }

    // ── 模拟盘：管理控制台 ────────────────────────────────────────────────────

    /**
     * 管理台：点选股槽位记住选中股，其余按钮一律
     * {@code performCommand("/mstock simulation admin ...")} ——
     * 与手输指令走<b>完全相同</b>的 Service 路径与双重校验，这里不复制任何业务逻辑。
     */
    private void handleSimulationAdmin(Player player, GuiHolder holder, int slot) {
        if (!requireSimulationUse(player)) return;
        if (!player.hasPermission("minestock.simulation.admin")) {
            player.sendMessage(plugin.getLang().get("no-permission"));
            return;
        }

        if (slot == GuiItems.SLOT_BACK_TO_MENU) {
            new MainMenuGui(plugin).open(player);
            return;
        }
        if (slot == SimulationAdminGui.SLOT_PREV_PAGE) {
            holder.page(Math.max(0, holder.getPage() - 1));
            new SimulationAdminGui(plugin).reload(player, holder);
            return;
        }
        if (slot == SimulationAdminGui.SLOT_NEXT_PAGE) {
            holder.page(holder.getPage() + 1);
            new SimulationAdminGui(plugin).reload(player, holder);
            return;
        }
        if (slot == SimulationAdminGui.SLOT_REFRESH) {
            new SimulationAdminGui(plugin).reload(player, holder);
            return;
        }
        // 指令速查 / 信息面板是只读展示项：它们的 lore 里也有指令文本，但点击不该执行
        if (slot == SimulationAdminGui.SLOT_HELP || slot == SimulationAdminGui.SLOT_INFO) return;
        // 选股区：点击即选中（holder 记住代码，渲染时优先取它）
        if (slot >= SimulationAdminGui.SELECT_START
                && slot < SimulationAdminGui.SELECT_START + SimulationAdminGui.SELECT_SLOTS) {
            String code = displayNameOf(holder.getInventory().getItem(slot));
            if (code == null || code.isEmpty()) return;
            holder.symbol(code);
            new SimulationAdminGui(plugin).reload(player, holder);
            return;
        }
        // 其余动作按钮：从 lore 里取等价指令原样执行。
        //
        // 执行后<b>立刻原地重绘</b>：按钮 lore 里缓存的是"下一次"的取值
        // （波动预设写的是 normal → high），不重绘的话连点多次会一直发同一条指令，
        // 聊天框就永远只显示"调整至 high"。Service 层对 SimulationStock 的改动是
        // 同步生效的（持久化才走异步），所以重绘能立刻读到新值。
        String action = actionOf(holder.getInventory().getItem(slot));
        if (action == null) return;
        player.performCommand("mstock simulation admin " + action);
        new SimulationAdminGui(plugin).reload(player, holder);
    }

    /** 三个 handler 的统一权限判定（{@code minestock.simulation.use}）。 */
    private boolean requireSimulationUse(Player player) {
        if (player.hasPermission("minestock.simulation.use")) return true;
        player.sendMessage(plugin.getLang().get("no-permission"));
        return false;
    }

    /**
     * 取物品显示名的纯文本（品种代码 / 选中股代码）。
     *
     * <p>与 {@code ForexGui} / {@code FuturesGui} 的取代码手法一致：
     * 显示名恰好是代码，排版改动不会影响路由。
     */
    private static String displayNameOf(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getItemMeta() == null
                || item.getItemMeta().displayName() == null) {
            return null;
        }
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(item.getItemMeta().displayName()).strip();
    }

    /**
     * 从管理台按钮的 lore 里取出等价指令的"动作 + 参数"部分。
     *
     * <p>{@code SimulationAdminGui} 每个动作按钮的最后一行都是
     * {@code /mstock simulation admin <动作> <代码> [参数]}；这里剥掉前缀后原样转交指令层，
     * 因此 GUI 与指令的校验、提示、权限判定完全一致。
     */
    private static String actionOf(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getItemMeta() == null || item.getItemMeta().lore() == null) {
            return null;
        }
        final String prefix = "/mstock simulation admin ";
        for (net.kyori.adventure.text.Component line : item.getItemMeta().lore()) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(line).strip();
            if (plain.startsWith(prefix) && plain.length() > prefix.length()) {
                return plain.substring(prefix.length()).strip();
            }
        }
        return null;
    }

    // ── 共用：把 Service 结果发回玩家 ─────────────────────────────────────────

    private void sendOutcome(Player player, CompletableFuture<TradeOutcome> pending) {
        sendOutcome(player, pending, null);
    }

    private void sendOutcome(Player player, CompletableFuture<TradeOutcome> pending,
                             Runnable onSuccess) {
        pending.thenAccept(outcome -> runOnPlayer(player, () -> {
            Player current = plugin.getServer().getPlayer(player.getUniqueId());
            if (current == null || !current.isOnline()) return;
            current.sendMessage(plugin.getLang().get(outcome.langKey(), outcome.placeholders()));
            if (outcome.success() && onSuccess != null) onSuccess.run();
        })).exceptionally(error -> {
            plugin.getLogger().warning("[MineStock] GUI 交易失败: " + error.getMessage());
            runOnPlayer(player, () -> {
                Player current = plugin.getServer().getPlayer(player.getUniqueId());
                if (current != null && current.isOnline()) {
                    current.sendMessage(plugin.getLang().get("db-error"));
                }
            });
            return null;
        });
    }

    /** 供其他监听器复用：当前玩家打开的是不是某个交易 GUI。 */
    public static boolean isTradingGui(Player player, GuiHolder.GuiType type) {
        Inventory top = player.getOpenInventory().getTopInventory();
        InventoryHolder holder = top.getHolder();
        return holder instanceof GuiHolder guiHolder && guiHolder.getType() == type;
    }

    /** 切回玩家线程；插件已禁用时静默丢弃。 */
    private void runOnPlayer(Player player, Runnable task) {
        try {
            dev.onelili.mstock.scheduler.CompatScheduler.get().runOnEntity(plugin, player, () -> {
                if (plugin.isOperational() && player.isOnline()) task.run();
            });
        } catch (IllegalStateException ignored) {
            // 插件在调度与执行之间被禁用。
        }
    }
}
