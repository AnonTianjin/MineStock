package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.leverage.MarginSnapshot;
import dev.onelili.mstock.model.FuturesPosition;
import dev.onelili.mstock.model.Holding;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 持仓总览界面（54 格）—— 与主菜单分离的独立界面。
 *
 * <p>主菜单只负责"入口"，持仓明细集中在这里，避免把主菜单塞成信息墙。
 *
 * <p>布局：
 * <ul>
 *   <li>0..26 — 股票持仓</li>
 *   <li>27..44 — 保证金 / 期货持仓</li>
 *   <li>4 — 汇总条（股票 / 保证金分段列示）</li>
 *   <li>45 上一页，48 返回主菜单，49 刷新，53 下一页</li>
 * </ul>
 *
 * <p>所有数据在异步阶段取齐后才渲染，{@link #render} 不做任何网络或数据库调用。
 */
public class PortfolioHubGui {

    public static final String TITLE = "我的持仓总览";

    /** 股票区 0..26（第 1..3 行）。 */
    public static final int STOCK_START = 0;
    public static final int STOCK_END = 27;
    /** 保证金 / 期货区 27..44（第 4..5 行）。 */
    public static final int RISK_START = 27;
    public static final int RISK_END = 45;
    /**
     * 汇总条：放在区外的第 6 行首格，不与任何持仓条目抢槽位。
     *
     * <p>第 6 行槽位分配（45..53）：45 汇总、47 上一页、49 刷新、
     * 51 下一页、53 返回主菜单（{@link GuiItems#SLOT_BACK_TO_MENU}）。
     */
    public static final int SLOT_SUMMARY = 45;
    public static final int SLOT_PREV = 47;
    public static final int SLOT_NEXT = 51;
    /** 刷新放在 49 之外的位置，避免与"返回主菜单"（49）撞槽。 */
    public static final int SLOT_REFRESH = 48;

    private static final int STOCK_CAPACITY = STOCK_END - STOCK_START;
    private static final int RISK_CAPACITY = RISK_END - RISK_START;

    private final MineStock plugin;

    public PortfolioHubGui(MineStock plugin) {
        this.plugin = plugin;
    }

    /** 打开界面：先占位，数据到齐后刷新。 */
    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.PORTFOLIO_HUB).page(page);
        Inventory inventory = GuiItems.create(holder, TITLE, NamedTextColor.GOLD);
        renderButtons(inventory);
        renderLoading(inventory);
        player.openInventory(inventory);
        load(player, holder);
    }

    private void renderButtons(Inventory inventory) {
        inventory.setItem(SLOT_REFRESH, GuiItems.button(Material.ARROW,
                Component.text("刷新").color(NamedTextColor.GREEN),
                List.of(GuiItems.label("重新获取持仓与行情"))));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
    }

    private void renderLoading(Inventory inventory) {
        inventory.setItem(SLOT_SUMMARY, GuiItems.info(Material.COMPASS,
                Component.text("持仓汇总").color(NamedTextColor.GOLD),
                List.of(GuiItems.label("加载中…"))));
        inventory.setItem(STOCK_START, GuiItems.info(Material.CLOCK,
                Component.text("加载中…").color(NamedTextColor.YELLOW),
                List.of(GuiItems.label("正在获取持仓与行情"))));
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    // ── 数据加载 ──────────────────────────────────────────────────────────────

    /** 并行取齐四类数据后才渲染；行情在进入玩家线程之前完成。 */
    private void load(Player player, GuiHolder holder) {
        CompletableFuture<List<Holding>> stocks = loadStockHoldings(player);
        CompletableFuture<Map<String, Double>> prices = stocks.thenCompose(this::fetchStockPrices);
        CompletableFuture<MarginSnapshot> margins = loadMarginSnapshot(player);
        CompletableFuture<List<FuturesPosition>> futures = loadFuturesPositions(player);
        CompletableFuture<List<SimulationService.SimulationPositionView>> simulations =
                loadSimulationPositions(player);

        CompletableFuture
                .allOf(stocks, prices, margins, futures, simulations)
                .thenRun(() -> runOnPlayer(player, () -> {
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    if (current == null || !current.isOnline()) return;
                    if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;
                    render(holder,
                            stocks.getNow(List.of()),
                            prices.getNow(Map.of()),
                            margins.getNow(MarginSnapshot.of(List.of())),
                            futures.getNow(List.of()),
                            simulations.getNow(List.of()));
                }))
                .exceptionally(error -> {
                    plugin.getLogger().warning("[MineStock] 加载持仓总览失败: "
                            + rootMessage(error));
                    return null;
                });
    }

    private CompletableFuture<List<Holding>> loadStockHoldings(Player player) {
        var repo = plugin.getHoldingRepo();
        var tasks = plugin.getTaskManager();
        if (repo == null || tasks == null) return CompletableFuture.completedFuture(List.of());
        return tasks.supplyAsync(() -> {
            try {
                return repo.findAllByPlayer(player.getUniqueId());
            } catch (SQLException e) {
                plugin.getLogger().warning("[MineStock] 读取股票持仓失败: " + e.getMessage());
                return List.<Holding>of();
            }
        });
    }

    /** 股票现价：批量异步获取，禁止 join（调用方可能位于玩家线程）。 */
    private CompletableFuture<Map<String, Double>> fetchStockPrices(List<Holding> stocks) {
        StockApiService api = plugin.getApi();
        if (api == null || stocks == null || stocks.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<String> codes = new ArrayList<>();
        for (Holding holding : stocks) {
            if (!codes.contains(holding.getStockCode())) codes.add(holding.getStockCode());
        }
        List<CompletableFuture<Map.Entry<String, Double>>> requests = new ArrayList<>();
        for (String code : codes) {
            requests.add(api.fetch(code)
                    .thenApply(info -> Map.entry(code, info.getPrice() * priceRatio()))
                    .exceptionally(error -> null));
        }
        return CompletableFuture
                .allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, Double> prices = new java.util.LinkedHashMap<>();
                    for (CompletableFuture<Map.Entry<String, Double>> request : requests) {
                        Map.Entry<String, Double> entry = request.getNow(null);
                        if (entry != null && entry.getValue() != null) {
                            prices.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return prices;
                });
    }

    private CompletableFuture<MarginSnapshot> loadMarginSnapshot(Player player) {
        var service = plugin.getLeverageService();
        if (service == null) {
            return CompletableFuture.completedFuture(MarginSnapshot.of(List.of()));
        }
        // 复合价格源：股票仓位走股票行情，外汇走 biquote
        return service.snapshot(player.getUniqueId(), plugin.priceSource());
    }

    private CompletableFuture<List<FuturesPosition>> loadFuturesPositions(Player player) {
        var service = plugin.getFuturesService();
        if (service == null) return CompletableFuture.completedFuture(List.of());
        return service.positions(player.getUniqueId());
    }

    /**
     * 模拟盘持仓。
     *
     * <p>{@code portfolio} 已经在服务端把估值价、盈亏一次算好（含 price-ratio），
     * 这里不再碰行情，避免为了一个展示界面多打一轮 HTTP。
     * 模拟盘未开启或没有数据库时返回空列表，界面照常显示其余分区。
     */
    private CompletableFuture<List<SimulationService.SimulationPositionView>> loadSimulationPositions(
            Player player) {
        var service = plugin.getSimulationService();
        if (service == null || !service.isEnabled()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return service.portfolio(player.getUniqueId())
                .exceptionally(error -> {
                    plugin.getLogger().warning("[MineStock] 读取模拟盘持仓失败: "
                            + rootMessage(error));
                    return List.of();
                });
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────────

    /**
     * 纯渲染：数据已就绪，本方法不做网络 / 数据库调用，可安全运行在玩家线程。
     *
     * <p>股票与保证金/期货<b>各自独立分页</b>：股票多不代表期货看不到，
     * 反之亦然。页码对所有区块生效，超出容量的部分按页显示。
     */
    private void render(GuiHolder holder, List<Holding> stocks, Map<String, Double> stockPrices,
                        MarginSnapshot margins, List<FuturesPosition> futures,
                        List<SimulationService.SimulationPositionView> simulations) {
        Inventory inventory = holder.getInventory();
        for (int slot = STOCK_START; slot < RISK_END; slot++) {
            inventory.setItem(slot, null);
        }
        inventory.setItem(SLOT_PREV, null);
        inventory.setItem(SLOT_NEXT, null);
        holder.resetTargets();

        // 股票杠杆仓位（market=EQUITY）归入"股票区"，与现金持仓并列展示；
        // 其余保证金仓位（外汇 / 贵金属 / 加密 / 指数）留在"保证金 / 期货区"。
        List<dev.onelili.mstock.model.MarginPosition> equityLeveraged = new ArrayList<>();
        List<dev.onelili.mstock.model.MarginPosition> otherMargin = new ArrayList<>();
        for (dev.onelili.mstock.model.MarginPosition position : margins.positions()) {
            if (position.getMarket() == dev.onelili.mstock.model.MarketType.EQUITY) {
                equityLeveraged.add(position);
            } else {
                otherMargin.add(position);
            }
        }

        int page = holder.getPage();
        // 模拟盘持仓与真实股票同属"股票类"条目，共享股票区的分页容量：
        // 页码对所有条目统一生效，翻页时不会出现某一类永远看不到的情况。
        int simulationEntries = simulations.size();
        int stockEntries = stocks.size() + equityLeveraged.size() + simulationEntries;
        int stockPages = Math.max(1, ceilDiv(stockEntries, STOCK_CAPACITY));
        int riskCount = otherMargin.size() + futures.size();
        int riskPages = Math.max(1, ceilDiv(riskCount, RISK_CAPACITY));
        int pageCount = Math.max(stockPages, riskPages);
        int safePage = Math.max(0, Math.min(pageCount - 1, page));
        holder.page(safePage);

        // ① 股票（现金持仓在前，杠杆仓位在后）
        double stockValue = 0.0;
        double stockCost = 0.0;
        int shownStocks = 0;
        int stockFrom = safePage * STOCK_CAPACITY;
        for (int i = stockFrom; i < stockEntries && shownStocks < STOCK_CAPACITY; i++) {
            int slot = STOCK_START + shownStocks;
            if (i < stocks.size()) {
                Holding holding = stocks.get(i);
                double price = stockPrices.getOrDefault(holding.getStockCode(), 0.0);
                double avgCost = holding.getAvgCost() * priceRatio();
                stockValue += price * holding.getAmount();
                stockCost += avgCost * holding.getAmount();

                inventory.setItem(slot, buildStockItem(holding, price, avgCost));
                holder.putStockTarget(slot, holding.getId(), holding.getStockCode());
            } else {
                int offset = i - stocks.size();
                if (offset < equityLeveraged.size()) {
                    var position = equityLeveraged.get(offset);
                    inventory.setItem(slot, buildEquityLeverageItem(position));
                    holder.putMarginTarget(slot, position.getId());
                } else {
                    var view = simulations.get(offset - equityLeveraged.size());
                    inventory.setItem(slot, buildSimulationItem(view));
                    holder.putSimulationTarget(slot, view.code());
                }
            }
            shownStocks++;
        }
        if (stockEntries == 0) {
            inventory.setItem(STOCK_START, GuiItems.info(Material.GRAY_STAINED_GLASS_PANE,
                    Component.text("暂无股票持仓").color(NamedTextColor.GRAY),
                    List.of(GuiItems.label("用 /mstock buy <代码> <数量> 买入"))));
        }

        // ② 其他保证金 + 期货（保证金在前）
        int shownRisk = 0;
        int riskFrom = safePage * RISK_CAPACITY;
        for (int i = riskFrom; i < riskCount && shownRisk < RISK_CAPACITY; i++) {
            int slot = RISK_START + shownRisk;
            if (i < otherMargin.size()) {
                var position = otherMargin.get(i);
                inventory.setItem(slot, buildMarginItem(position));
                // 带上 symbol：左键进子菜单后要能直接看这个品种的 K 线
                holder.putMarginTarget(slot, position.getId(), position.getSymbol());
            } else {
                FuturesPosition position = futures.get(i - otherMargin.size());
                inventory.setItem(slot, buildFuturesItem(position));
                holder.putFuturesTarget(slot, position.getId());
            }
            shownRisk++;
        }
        if (riskCount == 0) {
            inventory.setItem(RISK_START, GuiItems.info(Material.GRAY_STAINED_GLASS_PANE,
                    Component.text("暂无保证金 / 期货持仓").color(NamedTextColor.GRAY),
                    List.of(
                            GuiItems.label("用 /mstock forex buy 开外汇仓位"),
                            GuiItems.label("用 /mstock futures open 开期货仓位"))));
        }

        // 分页按钮
        if (safePage > 0) {
            inventory.setItem(SLOT_PREV, GuiItems.button(Material.ARROW,
                    Component.text("上一页").color(NamedTextColor.YELLOW)));
        }
        if (safePage < pageCount - 1) {
            inventory.setItem(SLOT_NEXT, GuiItems.button(Material.ARROW,
                    Component.text("下一页").color(NamedTextColor.YELLOW)));
        }

        int total = stockEntries + riskCount;
        inventory.setItem(SLOT_SUMMARY, buildSummary(total, stocks.size(), stockValue,
                stockCost, margins, futures.size(), safePage, pageCount));

        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    /**
     * 股票杠杆仓位条目。
     *
     * <p>与现金持仓并排展示，但用图标与"杠杆"行明确区分：
     * 杠杆仓位有强平风险，现金持仓没有。
     */
    private ItemStack buildEquityLeverageItem(dev.onelili.mstock.model.MarginPosition position) {
        boolean isLong = position.getDirection() == dev.onelili.mstock.model.TradeDirection.LONG;
        double pnl = position.getUnrealizedPnl();
        boolean up = pnl >= 0;

        return GuiItems.button(up ? Material.GOLDEN_PICKAXE : Material.IRON_PICKAXE,
                Component.text(position.getSymbol() + "  "
                                + formatQty(position.getQuantity()) + " 股")
                        .color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.line("类型", "股票 · 杠杆 " + position.getLeverage() + "x",
                                NamedTextColor.YELLOW),
                        GuiItems.line("方向", isLong ? "多（融资）" : "空（融券）",
                                isLong ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.line("开仓价", format6(position.getEntryPrice()),
                                NamedTextColor.WHITE),
                        GuiItems.line("冻结保证金", format2(position.getMargin()),
                                NamedTextColor.WHITE),
                        GuiItems.line("强平价", format6(position.getLiquidationPrice()),
                                NamedTextColor.RED),
                        GuiItems.line("未实现盈亏",
                                (up ? "+" : "") + format2(pnl),
                                up ? NamedTextColor.GREEN : NamedTextColor.RED),
                        Component.empty(),
                        GuiItems.label("点击查看保证金持仓")));
    }

    private ItemStack buildSummary(int total, int stockCount, double stockValue, double stockCost,
                                   MarginSnapshot margins, int futuresCount,
                                   int page, int pageCount) {
        var leverageConfig = plugin.getLeverageConfig();
        double ratio = margins.marginRatio();
        double stockPnl = stockValue - stockCost;

        NamedTextColor ratioColor = leverageConfig != null
                ? (ratio <= leverageConfig.getLiquidationThreshold() ? NamedTextColor.RED
                    : ratio <= leverageConfig.getMarginCallThreshold() ? NamedTextColor.YELLOW
                        : NamedTextColor.GREEN)
                : NamedTextColor.GREEN;

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.line("持仓总数", total + " 笔", NamedTextColor.WHITE));
        lore.add(GuiItems.line("页码", (page + 1) + " / " + pageCount, NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(GuiItems.label("— 股票 (" + stockCount + " 笔) —"));
        lore.add(GuiItems.line("持仓市值", format2(stockValue), NamedTextColor.WHITE));
        lore.add(GuiItems.line("浮动盈亏",
                (stockPnl >= 0 ? "+" : "") + format2(stockPnl),
                stockPnl >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(Component.empty());
        lore.add(GuiItems.label("— 保证金 / 外汇 (" + margins.positionCount() + " 笔) —"));
        lore.add(GuiItems.line("占用保证金", format2(margins.totalMargin()), NamedTextColor.WHITE));
        lore.add(GuiItems.line("未实现盈亏",
                (margins.totalUnrealizedPnl() >= 0 ? "+" : "")
                        + format2(margins.totalUnrealizedPnl()),
                margins.totalUnrealizedPnl() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(GuiItems.line("保证金率", format2(ratio * 100.0) + "%", ratioColor));
        if (leverageConfig != null) {
            lore.add(GuiItems.line("追保线 / 强平线",
                    format2(leverageConfig.getMarginCallThreshold() * 100.0) + "% / "
                            + format2(leverageConfig.getLiquidationThreshold() * 100.0) + "%",
                    NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        lore.add(GuiItems.label("— 期货 (" + futuresCount + " 笔) —"));

        return GuiItems.info(Material.COMPASS,
                Component.text(total == 0 ? "持仓汇总 · 暂无持仓" : "持仓汇总 · " + total + " 笔")
                        .color(total == 0 ? NamedTextColor.GRAY : NamedTextColor.GOLD),
                lore);
    }

    private ItemStack buildStockItem(Holding holding, double price, double avgCost) {
        double value = price * holding.getAmount();
        double cost = avgCost * holding.getAmount();
        double profit = value - cost;
        boolean up = profit >= 0;
        double changePct = cost > 0 ? profit / cost * 100.0 : 0.0;

        return GuiItems.button(up ? Material.LIME_DYE : Material.RED_DYE,
                Component.text(holding.getStockCode() + "  " + holding.getAmount() + " 股")
                        .color(NamedTextColor.WHITE),
                List.of(
                        GuiItems.line("类型", "股票", NamedTextColor.GRAY),
                        GuiItems.line("买入均价", format2(avgCost), NamedTextColor.WHITE),
                        GuiItems.line("当前价格", price > 0 ? format2(price) : "获取中…",
                                NamedTextColor.WHITE),
                        GuiItems.line("持仓市值", format2(value), NamedTextColor.WHITE),
                        GuiItems.line("浮动盈亏",
                                (up ? "+" : "") + format2(profit)
                                        + "  (" + (up ? "+" : "") + format2(changePct) + "%)",
                                up ? NamedTextColor.GREEN : NamedTextColor.RED),
                        Component.empty(),
                        GuiItems.label("左键打开操作菜单"),
                        GuiItems.label("右键直接看详情")));
    }

    /**
     * 模拟盘持仓条目。
     *
     * <p>与真实持仓并排但用青色标题 + "模拟盘"行明确区分：模拟盘的市值与盈亏
     * 不参与真实资产统计，玩家不能在总览里把它当成真钱。
     */
    private ItemStack buildSimulationItem(SimulationService.SimulationPositionView view) {
        boolean up = view.pnl() >= 0;
        return GuiItems.button(up ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                Component.text(view.code() + "  " + formatQty(view.amount()) + " 股")
                        .color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.line("类型", "模拟盘 · 不参与真实结算", NamedTextColor.AQUA),
                        GuiItems.line("名称", view.name(), NamedTextColor.WHITE),
                        GuiItems.line("持仓", formatQty(view.amount()) + " 股", NamedTextColor.WHITE),
                        GuiItems.line("成本", format2(view.avgCost()), NamedTextColor.WHITE),
                        GuiItems.line("现价", format2(view.price()), NamedTextColor.WHITE),
                        GuiItems.line("市值", format2(view.marketValue()), NamedTextColor.WHITE),
                        GuiItems.line("浮动盈亏",
                                (up ? "+" : "") + format2(view.pnl())
                                        + " (" + (up ? "+" : "") + format2(view.pnlPercent()) + "%)",
                                up ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.label("左键打开操作菜单"),
                        GuiItems.label("右键直接看详情")));
    }

    private ItemStack buildMarginItem(dev.onelili.mstock.model.MarginPosition position) {
        boolean isLong = position.getDirection() == dev.onelili.mstock.model.TradeDirection.LONG;
        double pnl = position.getUnrealizedPnl();
        boolean up = pnl >= 0;

        return GuiItems.button(isLong ? Material.IRON_SWORD : Material.SHIELD,
                Component.text("#" + position.getId() + " " + position.getSymbol())
                        .color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.line("类型", "保证金 · " + position.getMarket().label(),
                                NamedTextColor.GRAY),
                        GuiItems.line("方向", isLong ? "多" : "空",
                                isLong ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.line("数量", formatQty(position.getQuantity()), NamedTextColor.WHITE),
                        GuiItems.line("开仓价", format6(position.getEntryPrice()), NamedTextColor.WHITE),
                        GuiItems.line("杠杆", position.getLeverage() + "x", NamedTextColor.AQUA),
                        GuiItems.line("保证金", format2(position.getMargin()), NamedTextColor.WHITE),
                        GuiItems.line("强平价", format6(position.getLiquidationPrice()),
                                NamedTextColor.YELLOW),
                        GuiItems.line("未实现盈亏",
                                (up ? "+" : "") + format2(pnl),
                                up ? NamedTextColor.GREEN : NamedTextColor.RED),
                        Component.empty(),
                        GuiItems.label("左键打开操作菜单（平仓 / 查看K线 / 详情）"),
                        GuiItems.label("右键直接看保证金总览")));
    }

    private ItemStack buildFuturesItem(FuturesPosition position) {
        boolean isLong = position.getDirection() == dev.onelili.mstock.model.TradeDirection.LONG;
        double pnl = position.getRealizedPnl();
        boolean up = pnl >= 0;

        return GuiItems.button(isLong ? Material.GOLDEN_SWORD : Material.SHIELD,
                Component.text("#" + position.getId() + " " + position.getSymbol())
                        .color(NamedTextColor.LIGHT_PURPLE),
                List.of(
                        GuiItems.line("类型", "期货", NamedTextColor.GRAY),
                        GuiItems.line("方向", isLong ? "多" : "空",
                                isLong ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.line("数量", formatQty(position.getQuantity()), NamedTextColor.WHITE),
                        GuiItems.line("开仓价", format6(position.getEntryPrice()), NamedTextColor.WHITE),
                        GuiItems.line("保证金", format2(position.getMargin()), NamedTextColor.WHITE),
                        GuiItems.line("已盯市盈亏",
                                (up ? "+" : "") + format2(pnl),
                                up ? NamedTextColor.GREEN : NamedTextColor.RED),
                        GuiItems.line("到期日", position.getExpiryDate(), NamedTextColor.GRAY),
                        Component.empty(),
                        GuiItems.label("点击查看期货持仓")));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    static int ceilDiv(int value, int divisor) {
        if (value <= 0) return 0;
        return (value + divisor - 1) / divisor;
    }

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

    static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String format6(double value) {
        if (!Double.isFinite(value)) return "0.000000";
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    static String formatQty(double value) {
        if (!Double.isFinite(value)) return "0";
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
