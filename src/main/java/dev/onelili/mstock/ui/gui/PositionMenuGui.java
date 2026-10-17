package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.leverage.MarginSnapshot;
import dev.onelili.mstock.model.Holding;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.TradeDirection;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.simulation.SimulationService;
import dev.onelili.mstock.stockio.StockApiService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 单笔持仓的操作子菜单（27 格）。
 *
 * <p>持仓总览里左键一条持仓进入这里，把"卖出 / 平仓、查看K线、详情"摆成明确的按钮，
 * 而不是继续用"点击=执行 <code>/mstock 代码</code>"这种一个点击绑一个隐式动作的老做法
 * —— 后者在持仓条目上尤其别扭：玩家点一枚持仓，期待的是"能对它做点什么"，
 * 不是"跳去详情页"。
 *
 * <p>两种持仓共用同一个界面，差别只在按钮文案与落点：
 * <ul>
 *   <li>股票现金持仓（{@link GuiHolder.Target#KIND_STOCK}）：卖出走
 *       {@code /mstock sell <代码>}，数量仍在聊天栏输入；</li>
 *   <li>保证金仓位（{@link GuiHolder.Target#KIND_MARGIN}，含外汇 / 贵金属 /
 *       加密货币 / 指数）：平仓走 {@code /mstock margin close <主键>} —— 保证金仓位
 *       没有"部分平仓"这回事，所以按下即结算，不需要数量。</li>
 * </ul>
 *
 * <p>布局：
 * <ul>
 *   <li>4 — 持仓信息条</li>
 *   <li>10 卖出 / 平仓，12 查看K线，14 详情</li>
 *   <li>22 返回持仓总览</li>
 * </ul>
 *
 * <p>与其它界面一致：所有数据在异步阶段取齐后才渲染，{@link #render} 不做任何
 * 网络或数据库调用，也不在异步线程里碰 {@link ItemStack}
 * （物品只在玩家线程构造，见 {@link #render}）。
 */
public class PositionMenuGui {

    public static final String TITLE = "持仓操作";

    /** 界面格数：27 格（3 行），内容只有 5 个条目。 */
    public static final int SIZE = 27;
    /** 创建 {@link #SIZE} 格界面所需行数。 */
    public static final int ROWS = SIZE / 9;

    /** 持仓信息条。 */
    public static final int SLOT_HOLDING = 4;
    /** 卖出（股票）/ 平仓（保证金）。 */
    public static final int SLOT_SELL = 10;
    /** 查看K线：与 /mstock chart &lt;品种&gt; 等价，看完可一键回到持仓总览。 */
    public static final int SLOT_CHART = 12;
    /** 详情：股票是 /mstock &lt;代码&gt;，保证金是 /mstock margin。 */
    public static final int SLOT_DETAIL = 14;
    /** 返回持仓总览（回到进入本页时的那一页）。 */
    public static final int SLOT_BACK = 22;

    /** 异步阶段取回的原始数据；物品一律留到玩家线程再构造。 */
    private record Loaded(Holding holding, double price, MarginPosition position,
                          SimulationService.SimulationPositionView simulation) {}

    private final MineStock plugin;

    public PositionMenuGui(MineStock plugin) {
        this.plugin = plugin;
    }

    /**
     * 打开某个股票现金持仓的子菜单。
     *
     * @param returnPage 返回持仓总览时要恢复的页码
     */
    public void open(Player player, String code, int returnPage) {
        open(player, new GuiHolder.Target(GuiHolder.Target.KIND_STOCK, 0L, code), returnPage);
    }

    /**
     * 打开某个保证金仓位（外汇 / 贵金属 / 加密货币 / 指数）的子菜单。
     *
     * @param positionId 保证金仓位主键，用于 {@code /mstock margin close}
     * @param symbol     品种代码，用于看 K 线
     * @param returnPage 返回持仓总览时要恢复的页码
     */
    public void openMargin(Player player, long positionId, String symbol, int returnPage) {
        open(player, new GuiHolder.Target(GuiHolder.Target.KIND_MARGIN, positionId, symbol), returnPage);
    }

    /**
     * 打开某个模拟盘持仓的子菜单。
     *
     * <p>模拟持仓在库里以 (玩家, 代码) 为主键，没有数字主键，所以定位只用 {@code code}。
     *
     * @param returnPage 返回持仓总览时要恢复的页码
     */
    public void openSimulation(Player player, String code, int returnPage) {
        open(player, new GuiHolder.Target(GuiHolder.Target.KIND_SIM, 0L, code), returnPage);
    }

    private void open(Player player, GuiHolder.Target target, int returnPage) {
        String code = target.code();
        if (code == null || code.isBlank()) {
            player.closeInventory();
            return;
        }
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.POSITION_MENU)
                .symbol(code)
                .page(Math.max(0, returnPage))
                .focusTarget(target);
        Inventory inventory = GuiItems.create(holder, TITLE, NamedTextColor.GOLD, ROWS);
        renderLoading(inventory, code);
        player.openInventory(inventory);
        load(player, holder, target);
    }

    /** 是否正在操作一笔保证金仓位（决定按钮文案与落点）。 */
    private static boolean isMargin(GuiHolder.Target target) {
        return target != null && GuiHolder.Target.KIND_MARGIN.equals(target.kind());
    }

    /** 是否正在操作一笔模拟盘持仓。 */
    private static boolean isSimulation(GuiHolder.Target target) {
        return target != null && GuiHolder.Target.KIND_SIM.equals(target.kind());
    }

    private void renderLoading(Inventory inventory, String code) {
        inventory.setItem(SLOT_HOLDING, GuiItems.info(Material.CLOCK,
                Component.text(code).color(NamedTextColor.YELLOW),
                List.of(GuiItems.label("加载中…"))));
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    // ── 数据加载 ──────────────────────────────────────────────────────────────

    /** 取持仓数据（股票：持仓 + 现价；保证金：整张快照里找出这一笔），到齐后回到玩家线程渲染。 */
    private void load(Player player, GuiHolder holder, GuiHolder.Target target) {
        CompletableFuture<Loaded> data = isMargin(target)
                ? loadMargin(player, target.id())
                : isSimulation(target)
                        ? loadSimulation(player, target.code())
                        : loadStock(player, target.code());

        data.thenAccept(loaded -> runOnPlayer(player, () -> {
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    if (current == null || !current.isOnline()) return;
                    if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;
                    render(holder, loaded, target);
                }))
                .exceptionally(error -> {
                    plugin.getLogger().warning("[MineStock] 加载持仓操作页失败: " + rootMessage(error));
                    return null;
                });
    }

    private CompletableFuture<Loaded> loadStock(Player player, String code) {
        CompletableFuture<Holding> holding = loadHolding(player, code);
        CompletableFuture<Double> price = loadPrice(code);
        return CompletableFuture
                .allOf(holding, price)
                .thenApply(ignored -> new Loaded(holding.getNow(null), price.getNow(0.0), null, null));
    }

    /**
     * 模拟盘持仓：{@code portfolio} 已在服务层算好估值价与盈亏（含 price-ratio），
     * 这里按代码挑出那一笔，不重复拉行情。
     */
    private CompletableFuture<Loaded> loadSimulation(Player player, String code) {
        var service = plugin.getSimulationService();
        if (service == null || !service.isEnabled()) {
            return CompletableFuture.completedFuture(new Loaded(null, 0.0, null, null));
        }
        return service.portfolio(player.getUniqueId()).thenApply(views -> {
            for (SimulationService.SimulationPositionView view : views) {
                if (code.equalsIgnoreCase(view.code())) {
                    return new Loaded(null, 0.0, null, view);
                }
            }
            return new Loaded(null, 0.0, null, null);
        });
    }

    /**
     * 保证金仓位：整个快照由服务层一次算好（含强平价与未实现盈亏），
     * 这里只按主键挑出那一笔，不自己算任何价格。
     */
    private CompletableFuture<Loaded> loadMargin(Player player, long positionId) {
        var service = plugin.getLeverageService();
        if (service == null) return CompletableFuture.completedFuture(new Loaded(null, 0.0, null, null));
        return service.snapshot(player.getUniqueId(), plugin.priceSource())
                .thenApply(snapshot -> {
                    for (MarginPosition position : snapshot.positions()) {
                        if (position.getId() == positionId) {
                            return new Loaded(null, 0.0, position, null);
                        }
                    }
                    return new Loaded(null, 0.0, null, null);
                });
    }

    private CompletableFuture<Holding> loadHolding(Player player, String code) {
        var repo = plugin.getHoldingRepo();
        var tasks = plugin.getTaskManager();
        if (repo == null || tasks == null) return CompletableFuture.completedFuture(null);
        return tasks.supplyAsync(() -> {
            try {
                for (Holding holding : repo.findAllByPlayer(player.getUniqueId())) {
                    if (code.equalsIgnoreCase(holding.getStockCode())) return holding;
                }
                return null;
            } catch (SQLException e) {
                plugin.getLogger().warning("[MineStock] 读取股票持仓失败: " + e.getMessage());
                return null;
            }
        });
    }

    /** 现价：异步获取，禁止 join（本方法可能被玩家线程调用）。 */
    private CompletableFuture<Double> loadPrice(String code) {
        StockApiService api = plugin.getApi();
        if (api == null) return CompletableFuture.completedFuture(0.0);
        return api.fetch(code)
                .thenApply(info -> info.getPrice() * priceRatio())
                .exceptionally(error -> 0.0);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────────

    private void render(GuiHolder holder, Loaded loaded, GuiHolder.Target target) {
        Inventory inventory = holder.getInventory();
        boolean margin = isMargin(target);
        boolean simulation = isSimulation(target);
        String code = target.code();

        inventory.setItem(SLOT_HOLDING, margin
                ? buildMarginItem(loaded.position(), code)
                : simulation
                        ? buildSimulationItem(loaded.simulation(), code)
                        : buildStockItem(loaded.holding(), loaded.price(), code));

        String sellHint = margin
                ? "按最新价格结算并平掉这笔保证金仓位"
                : simulation
                        ? "卖出这只模拟股票的部分或全部持仓"
                        : "卖出这只股票的部分或全部持仓";
        String sellCommand = margin
                ? "/mstock margin close " + target.id()
                : simulation
                        ? "/mstock simulation sell " + code
                        : "/mstock sell " + code;

        inventory.setItem(SLOT_SELL, GuiItems.button(Material.GOLD_INGOT,
                Component.text(margin ? "平仓" : "卖出").color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.label(sellHint),
                        GuiItems.label(margin
                                ? "点击后立即执行，无需输入数量"
                                : "点击后在聊天栏输入数量"),
                        Component.empty(),
                        GuiItems.label(sellCommand))));

        String detailHint = margin
                ? "查看保证金总览、保证金率与强平价"
                : simulation
                        ? "查看模拟行情、持仓与波动参数"
                        : "查看完整行情、持仓与杠杆信息";
        String detailCommand = margin
                ? "/mstock margin"
                : simulation
                        ? "/mstock simulation " + code
                        : "/mstock " + code;

        inventory.setItem(SLOT_CHART, GuiItems.button(Material.PAPER,
                Component.text("查看K线").color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.label("在聊天栏绘制 K 线图"),
                        GuiItems.label("看完可直接回到持仓总览"),
                        Component.empty(),
                        GuiItems.label("/mstock chart " + code))));

        inventory.setItem(SLOT_DETAIL, GuiItems.button(Material.SPYGLASS,
                Component.text("详情").color(NamedTextColor.WHITE),
                List.of(
                        GuiItems.label(detailHint),
                        Component.empty(),
                        GuiItems.label(detailCommand))));

        inventory.setItem(SLOT_BACK, GuiItems.button(Material.ARROW,
                Component.text("返回持仓总览").color(NamedTextColor.GREEN),
                List.of(GuiItems.label("回到进入本页时的那一页"))));

        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    /**
     * 模拟盘持仓条目。
     *
     * <p>青色标题 + "模拟盘"行：与真实持仓视觉上必须一眼可分，
     * 否则玩家会把模拟市值当成真钱。
     */
    private ItemStack buildSimulationItem(SimulationService.SimulationPositionView view, String code) {
        if (view == null) {
            return goneItem(code);
        }
        boolean up = view.pnl() >= 0;
        return GuiItems.button(up ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                Component.text(view.code() + "  模拟").color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.line("名称", view.name(), NamedTextColor.WHITE),
                        GuiItems.line("持仓",
                                PortfolioHubGui.formatQty(view.amount()) + " 股", NamedTextColor.WHITE),
                        GuiItems.line("成本", PortfolioHubGui.format2(view.avgCost()), NamedTextColor.WHITE),
                        GuiItems.line("现价", PortfolioHubGui.format2(view.price()), NamedTextColor.WHITE),
                        GuiItems.line("市值", PortfolioHubGui.format2(view.marketValue()), NamedTextColor.WHITE),
                        GuiItems.line("浮动盈亏",
                                (up ? "+" : "") + PortfolioHubGui.format2(view.pnl())
                                        + " (" + (up ? "+" : "") + PortfolioHubGui.format2(view.pnlPercent()) + "%)",
                                up ? NamedTextColor.GREEN : NamedTextColor.RED),
                        Component.empty(),
                        GuiItems.label("模拟盘不参与真实结算")));
    }

    private ItemStack buildStockItem(Holding holding, double price, String code) {
        if (holding == null) {
            return goneItem(code);
        }

        double amount = holding.getAmount();
        double avgCost = holding.getAvgCost() * priceRatio();
        double value = price * amount;
        double cost = avgCost * amount;
        double profit = value - cost;
        boolean up = profit >= 0;
        double changePct = cost > 0 ? profit / cost * 100.0 : 0.0;

        return GuiItems.info(up ? Material.LIME_DYE : Material.RED_DYE,
                Component.text(code + "  " + PortfolioHubGui.formatQty(amount) + " 股")
                        .color(NamedTextColor.WHITE),
                List.of(
                        GuiItems.line("买入均价", PortfolioHubGui.format2(avgCost), NamedTextColor.WHITE),
                        GuiItems.line("当前价格", price > 0
                                        ? PortfolioHubGui.format2(price) : "获取中…",
                                NamedTextColor.WHITE),
                        GuiItems.line("持仓市值", PortfolioHubGui.format2(value), NamedTextColor.WHITE),
                        GuiItems.line("浮动盈亏",
                                (up ? "+" : "") + PortfolioHubGui.format2(profit)
                                        + "  (" + (up ? "+" : "") + PortfolioHubGui.format2(changePct) + "%)",
                                up ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }

    /**
     * 保证金仓位信息条。
     *
     * <p>字段与持仓总览的条目保持一致（方向 / 数量 / 开仓价 / 杠杆 / 保证金 / 强平价 /
     * 未实现盈亏），因为它就是同一个 {@link MarginSnapshot} 里挑出来的同一笔。
     */
    private ItemStack buildMarginItem(MarginPosition position, String symbol) {
        if (position == null) {
            return goneItem(symbol);
        }
        boolean isLong = position.getDirection() == TradeDirection.LONG;
        double pnl = position.getUnrealizedPnl();
        boolean up = pnl >= 0;

        return GuiItems.info(isLong ? Material.IRON_SWORD : Material.SHIELD,
                Component.text("#" + position.getId() + " " + position.getSymbol())
                        .color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.line("类型", "保证金 · " + position.getMarket().label(),
                                NamedTextColor.GRAY),
                        GuiItems.line("方向", isLong ? "多" : "空",
                                isLong ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.line("数量", PortfolioHubGui.formatQty(position.getQuantity()),
                                NamedTextColor.WHITE),
                        GuiItems.line("开仓价", PortfolioHubGui.format6(position.getEntryPrice()),
                                NamedTextColor.WHITE),
                        GuiItems.line("杠杆", position.getLeverage() + "x", NamedTextColor.AQUA),
                        GuiItems.line("保证金", PortfolioHubGui.format2(position.getMargin()),
                                NamedTextColor.WHITE),
                        GuiItems.line("强平价", PortfolioHubGui.format6(position.getLiquidationPrice()),
                                NamedTextColor.YELLOW),
                        GuiItems.line("未实现盈亏", (up ? "+" : "") + PortfolioHubGui.format2(pnl),
                                up ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }

    private ItemStack goneItem(String code) {
        return GuiItems.info(Material.GRAY_STAINED_GLASS_PANE,
                Component.text(code + " 已无持仓").color(NamedTextColor.GRAY),
                List.of(GuiItems.label("持仓可能已在别处平掉")));
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    private double priceRatio() {
        MainConfig config = plugin.getMainConfig();
        return config != null ? config.getPriceRatio() : 1.0;
    }

    private void runOnPlayer(Player player, Runnable task) {
        try {
            CompatScheduler.get().runOnEntity(plugin, player, () -> {
                if (plugin.isOperational() && player.isOnline()) task.run();
            });
        } catch (IllegalStateException ignored) {
            // 插件在调度与执行之间被禁用
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
