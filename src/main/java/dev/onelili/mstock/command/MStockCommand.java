package dev.onelili.mstock.command;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.api.event.StockBuyEvent;
import dev.onelili.mstock.api.event.StockPortfolioQueryEvent;
import dev.onelili.mstock.api.event.StockPreBuyEvent;
import dev.onelili.mstock.api.event.StockPreSellEvent;
import dev.onelili.mstock.api.event.StockQueryEvent;
import dev.onelili.mstock.api.event.StockSellEvent;
import dev.onelili.mstock.chart.ChartService;
import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.HoldingRepository;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.forex.ForexService;
import dev.onelili.mstock.futures.FuturesService;
import dev.onelili.mstock.leverage.LeverageService;
import dev.onelili.mstock.leverage.PlayerLeverageManager;
import dev.onelili.mstock.leverage.TradeOutcome;
import dev.onelili.mstock.lifecycle.LifecycleTaskManager;
import dev.onelili.mstock.market.ForexProvider;
import dev.onelili.mstock.model.ForexCategory;
import dev.onelili.mstock.model.FuturesContract;
import dev.onelili.mstock.model.FuturesPosition;
import dev.onelili.mstock.model.Holding;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketSymbol;
import dev.onelili.mstock.model.StockInfo;
import dev.onelili.mstock.model.TradeDirection;
import dev.onelili.mstock.recommend.RecommendationService;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.simulation.SimulationBoundary;
import dev.onelili.mstock.simulation.SimulationSchedule;
import dev.onelili.mstock.simulation.SimulationService;
import dev.onelili.mstock.simulation.SimulationVolatilityPreset;
import dev.onelili.mstock.stockio.StockApiService;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.ui.PendingAction;
import dev.onelili.mstock.ui.gui.ForexGui;
import dev.onelili.mstock.ui.gui.FuturesGui;
import dev.onelili.mstock.ui.gui.LeverageGui;
import dev.onelili.mstock.ui.gui.MainMenuGui;
import dev.onelili.mstock.ui.gui.PortfolioHubGui;
import dev.onelili.mstock.ui.gui.SimulationAdminGui;
import dev.onelili.mstock.ui.gui.SimulationGui;
import dev.onelili.mstock.util.KLineRenderer;
import dev.onelili.mstock.util.LangUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MStockCommand implements CommandExecutor, TabCompleter {
    private final MineStock plugin;
    private volatile StockApiService api;
    private volatile RecommendationService recommendations;
    private final HoldingRepository holdingRepo;
    private final EconomyService economy;
    private volatile MainConfig config;
    private volatile LangUtil lang;
    private final ChatInputSession session;
    private final dev.onelili.mstock.ui.PortfolioGUI portfolioGUI;
    private final MainMenuGui mainMenuGui;
    private final PortfolioHubGui portfolioHubGui;
    private final LeverageGui leverageGui;
    private final ForexGui forexGui;
    private final FuturesGui futuresGui;
    private final SimulationGui simulationGui;
    private final SimulationAdminGui simulationAdminGui;

    /** 模拟盘管理动作（Tab 补全用；顺序与 {@code SimulationAdminGui.HELP_COMMANDS} 一致）。 */
    private static final List<String> SIMULATION_ADMIN_ACTIONS = List.of(
            "add", "remove",
            "setprice", "target", "jump", "pause", "slope", "amplitude", "preset", "bounds", "limit",
            "boundary", "suspend", "tradable", "link", "unlink", "schedule", "schedules",
            "unschedule", "seed", "resume", "reset");

    /** 参数非法时展示的用法串（{@code simulation-admin-invalid-args} 的 {usage}）。 */
    private static final String SIMULATION_ADMIN_USAGE =
            "/mstock simulation admin <动作> <代码> [参数]（动作: "
                    + String.join("|", SIMULATION_ADMIN_ACTIONS) + "）";

    /**
     * {@code add} / {@code remove} 单独一套用法串。
     *
     * <p>它们的参数布局与其余动作不同 —— 没有"先代码后参数"的前缀，
     * 统一串里那句 {@code <动作> <代码> [参数]} 反而会误导。
     */
    private static final String SIMULATION_ADMIN_ADD_USAGE =
            "/mstock simulation admin add <代码> <名称> <板块> <初始价>"
                    + "（代码须为 3 位大写字母 + 3 位数字，如 SIM011）";
    private static final String SIMULATION_ADMIN_REMOVE_USAGE =
            "/mstock simulation admin remove <代码>";

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** GUI 数量输入完成后的一次性回调。 */
    private final Map<UUID, java.util.function.DoubleConsumer> quantityCallbacks =
            new ConcurrentHashMap<>();
    /** GUI 文本搜索输入完成后的一次性回调。 */
    private final Map<UUID, java.util.function.Consumer<String>> symbolSearchCallbacks =
            new ConcurrentHashMap<>();

    /**
     * {@code /mstock back} 的返回目标：玩家 UUID → 要执行的指令（不含前导斜杠）。
     *
     * <p>只由"从界面点开 K 线"写入，普通 {@code /mstock chart} <b>不</b>写入 ——
     * 否则玩家在聊天栏手敲图表命令后，{@code /mstock back} 会把他莫名传送回
     * 上一次碰过的界面，而那次界面跟眼前这张图毫无关系。
     */
    private final Map<UUID, String> chartReturnCommands = new ConcurrentHashMap<>();

    /** 与 {@link #chartReturnCommands} 配对的可读标签，仅用于提示文案。 */
    private final Map<UUID, String> chartReturnLabels = new ConcurrentHashMap<>();

    public MStockCommand(MineStock plugin) {
        this.plugin = plugin;
        this.api = plugin.getApi();
        this.recommendations = plugin.getRecommendationService();
        this.holdingRepo = plugin.getHoldingRepo();
        this.economy = plugin.getEconomy();
        this.config = plugin.getMainConfig();
        this.lang = plugin.getLang();
        this.session = plugin.getChatSession();
        this.portfolioGUI = new dev.onelili.mstock.ui.PortfolioGUI(plugin);
        this.mainMenuGui = new MainMenuGui(plugin);
        this.portfolioHubGui = new PortfolioHubGui(plugin);
        this.leverageGui = new LeverageGui(plugin);
        this.forexGui = new ForexGui(plugin);
        this.futuresGui = new FuturesGui(plugin);
        this.simulationGui = new SimulationGui(plugin);
        this.simulationAdminGui = new SimulationAdminGui(plugin);
    }

    public void refresh(StockApiService api, RecommendationService recommendations,
                        MainConfig config, LangUtil lang) {
        this.api = api;
        this.recommendations = recommendations;
        this.config = config;
        this.lang = lang;
    }

    // ── 新子指令：杠杆 ────────────────────────────────────────────────────────

    /** {@code /mstock leverage} 查看当前杠杆；{@code /mstock leverage <倍数>} 设置。 */
    private void handleLeverage(Player player, String[] args) {
        PlayerLeverageManager manager = plugin.getLeverageManager();
        if (manager == null) {
            lang.send(player, "db-error");
            return;
        }

        // /mstock leverage stock [倍数] —— 股票杠杆独立于外汇
        if (args.length >= 2 && "stock".equalsIgnoreCase(args[1])) {
            handleStockLeverage(player, manager, args);
            return;
        }

        int current = manager.getLeverage(player.getUniqueId());
        int max = manager.getMaxLeverage();

        if (args.length < 2) {
            lang.sendNoPrefix(player, "leverage-info",
                    "leverage", String.valueOf(current),
                    "default", String.valueOf(manager.getDefaultLeverage()),
                    "max", String.valueOf(max));
            lang.sendNoPrefix(player, "leverage-stock-info",
                    "leverage", String.valueOf(manager.getStockLeverage(player.getUniqueId())),
                    "default", String.valueOf(manager.getStockDefaultLeverage()),
                    "max", String.valueOf(manager.getStockMaxLeverage()));
            return;
        }

        if (!player.hasPermission("minestock.leverage.set")) {
            lang.send(player, "no-permission");
            return;
        }
        int target;
        try {
            target = Integer.parseInt(args[1].strip());
        } catch (NumberFormatException e) {
            lang.send(player, "input-invalid");
            return;
        }
        if (!plugin.getLeverageConfig().isValidLeverage(target)) {
            lang.send(player, "leverage-out-of-range", "max", String.valueOf(max));
            return;
        }
        if (manager.setLeverage(player.getUniqueId(), target)) {
            lang.send(player, "leverage-set",
                    "leverage", String.valueOf(target),
                    "max", String.valueOf(max));
            lang.sendNoPrefix(player, "leverage-new-positions-only");
        } else {
            lang.send(player, "leverage-out-of-range", "max", String.valueOf(max));
        }
    }

    /** {@code /mstock leverage stock [倍数]} —— 查看或设置股票杠杆。 */
    private void handleStockLeverage(Player player, PlayerLeverageManager manager, String[] args) {
        int current = manager.getStockLeverage(player.getUniqueId());
        int max = manager.getStockMaxLeverage();
        boolean enabled = plugin.getLeverageConfig().isStockLeverageEnabled();

        if (!enabled) {
            // 制度整体关闭：连设置入口一起锁死，免得玩家设了个永远不生效的值。
            lang.sendNoPrefix(player, "leverage-stock-info",
                    "leverage", String.valueOf(current),
                    "default", String.valueOf(manager.getStockDefaultLeverage()),
                    "max", String.valueOf(max));
            lang.send(player, "leverage-stock-disabled");
            return;
        }

        if (args.length < 3) {
            lang.sendNoPrefix(player, "leverage-stock-info",
                    "leverage", String.valueOf(current),
                    "default", String.valueOf(manager.getStockDefaultLeverage()),
                    "max", String.valueOf(max));
            return;
        }

        if (!player.hasPermission("minestock.leverage.set")) {
            lang.send(player, "no-permission");
            return;
        }
        int target;
        try {
            target = Integer.parseInt(args[2].strip());
        } catch (NumberFormatException e) {
            lang.send(player, "input-invalid");
            return;
        }
        if (!plugin.getLeverageConfig().isValidStockLeverage(target)) {
            lang.send(player, "leverage-stock-out-of-range", "max", String.valueOf(max));
            return;
        }
        if (manager.setStockLeverage(player.getUniqueId(), target)) {
            lang.send(player, "leverage-stock-set",
                    "leverage", String.valueOf(target),
                    "max", String.valueOf(max));
            lang.sendNoPrefix(player, "leverage-new-positions-only");
        } else {
            lang.send(player, "leverage-stock-out-of-range", "max", String.valueOf(max));
        }
    }

    /** {@code /mstock margin} 查看保证金、未实现盈亏、强平价。 */
    private void handleMargin(Player player, String[] args) {
        if (args.length >= 2 && "close".equalsIgnoreCase(args[1])) {
            closeMarginPosition(player, args);
            return;
        }
        if (args.length >= 2 && "closeall".equalsIgnoreCase(args[1])) {
            closeAllMargin(player);
            return;
        }
        // /mstock margin topup <仓位ID> <金额> —— 收到追保通知后的自救手段
        if (args.length >= 2 && ("topup".equalsIgnoreCase(args[1])
                || "add".equalsIgnoreCase(args[1]))) {
            topUpMarginPosition(player, args);
            return;
        }

        LeverageService service = plugin.getLeverageService();
        var forex = plugin.getForexService();
        if (service == null) {
            lang.send(player, "db-error");
            return;
        }
        var priceSource = plugin.priceSource();
        lang.sendNoPrefix(player, "margin-header");

        service.snapshot(player.getUniqueId(), priceSource).thenAccept(snapshot ->
                runOnPlayer(player, () -> {
                    if (snapshot.positionCount() == 0) {
                        lang.sendNoPrefix(player, "margin-empty");
                        return;
                    }
                    LeverageConfig leverageConfig = plugin.getLeverageConfig();
                    player.sendMessage(lang.getNoPrefix("margin-summary",
                            "count", String.valueOf(snapshot.positionCount()),
                            "margin", format2(snapshot.totalMargin()),
                            "pnl", (snapshot.totalUnrealizedPnl() >= 0 ? "+" : "")
                                    + format2(snapshot.totalUnrealizedPnl()),
                            "ratio", format2(snapshot.marginRatio() * 100.0),
                            "call", format2(leverageConfig.getMarginCallThreshold() * 100.0),
                            "liq", format2(leverageConfig.getLiquidationThreshold() * 100.0)));
                    for (MarginPosition position : snapshot.positions()) {
                        String key = position.getUnrealizedPnl() >= 0
                                ? "margin-item-up" : "margin-item-down";
                        player.sendMessage(lang.getNoPrefix(key,
                                "id", String.valueOf(position.getId()),
                                "symbol", position.getSymbol(),
                                "direction", position.getDirection().name(),
                                "quantity", formatQty(position.getQuantity()),
                                "entry", format2(position.getEntryPrice()),
                                "leverage", String.valueOf(position.getLeverage()),
                                "margin", format2(position.getMargin()),
                                "liq_price", format2(position.getLiquidationPrice()),
                                "pnl", format2(Math.abs(position.getUnrealizedPnl()))));
                    }
                })
        ).exceptionally(error -> {
            plugin.getLogger().warning("查询保证金失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "db-error"));
            return null;
        });
    }

    /**
     * {@code /mstock margin topup <仓位ID> <金额>} —— 补交保证金。
     *
     * <p>收到追保通知后的自救手段：把余额转进仓位保证金，
     * 保证金率回升、强平价推远。金额建议按提示的"建议补交额"或更多。
     */
    private void topUpMarginPosition(Player player, String[] args) {
        if (args.length < 4) {
            lang.send(player, "input-invalid");
            return;
        }
        long id;
        double amount;
        try {
            id = Long.parseLong(args[2]);
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            lang.send(player, "input-invalid");
            return;
        }
        if (!(amount > 0)) {
            lang.send(player, "input-invalid");
            return;
        }
        LeverageService service = plugin.getLeverageService();
        if (service == null) {
            lang.send(player, "db-error");
            return;
        }

        // 价格按市场路由：股票仓位走股票行情源，外汇走 biquote
        service.snapshot(player.getUniqueId(), plugin.priceSource()).thenCompose(snapshot -> {
            MarginPosition target = snapshot.positions().stream()
                    .filter(p -> p.getId() == id).findFirst().orElse(null);
            if (target == null) {
                return CompletableFuture.completedFuture(
                        TradeOutcome.fail("margin-position-not-found"));
            }
            return plugin.symbolPrice(target.getMarket(), target.getSymbol())
                    .thenCompose(price ->
                            service.topUp(player.getUniqueId(), id, amount, price));
        }).thenAccept(outcome -> runOnPlayer(player, () ->
                player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))
        )).exceptionally(error -> {
            plugin.getLogger().warning("补交保证金失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private void closeMarginPosition(Player player, String[] args) {        if (args.length < 3) {
            lang.send(player, "input-invalid");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            lang.send(player, "input-invalid");
            return;
        }
        LeverageService service = plugin.getLeverageService();
        var forex = plugin.getForexService();
        if (service == null || forex == null) {
            lang.send(player, "db-error");
            return;
        }
        // 价格按市场路由（股票仓位走股票行情源），结算在服务层完成。
        service.snapshot(player.getUniqueId(), plugin.priceSource()).thenCompose(snapshot -> {
            MarginPosition target = snapshot.positions().stream()
                    .filter(p -> p.getId() == id).findFirst().orElse(null);
            if (target == null) {
                return CompletableFuture.completedFuture(
                        TradeOutcome.fail("margin-position-not-found"));
            }
            return plugin.symbolPrice(target.getMarket(), target.getSymbol())
                    .thenCompose(price -> service.close(player.getUniqueId(), id, price));
        }).thenAccept(outcome -> runOnPlayer(player, () ->
                player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))
        )).exceptionally(error -> {
            plugin.getLogger().warning("平仓失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private void closeAllMargin(Player player) {
        LeverageService service = plugin.getLeverageService();
        if (service == null) {
            lang.send(player, "db-error");
            return;
        }
        service.closeAll(player.getUniqueId(), plugin.priceSource())
                .thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))
                )).exceptionally(error -> {
            plugin.getLogger().warning("批量平仓失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    // ── 新子指令：外汇 ────────────────────────────────────────────────────────

    private void handleForex(Player player, String[] args) {
        if (args.length < 2) {
            forexGui.openCategory(player);
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "buy", "sell" -> {
                // /mstock forex buy <symbol> <amount>
                if (args.length < 4) {
                    lang.sendNoPrefix(player, "usage");
                    return;
                }
                if (!player.hasPermission("minestock.forex.trade")) {
                    lang.send(player, "no-permission");
                    return;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[3]);
                } catch (NumberFormatException e) {
                    lang.send(player, "input-invalid");
                    return;
                }
                tradeForex(player, args[2], amount, "buy".equals(action));
            }
            case "price" -> {
                if (args.length < 3) {
                    lang.sendNoPrefix(player, "usage");
                    return;
                }
                showForexPrice(player, args[2]);
            }
            case "chart" -> {
                // /mstock forex chart <symbol> [interval] [limit]
                if (args.length < 3) {
                    lang.sendNoPrefix(player, "usage");
                    return;
                }
                String interval = args.length >= 4 ? args[3] : null;
                int limit = config.getChartDefaultLimit();
                if (args.length >= 5) {
                    try {
                        limit = Integer.parseInt(args[4]);
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                        return;
                    }
                }
                requestChart(player, args[2], interval, limit);
            }
            // /mstock forex fiat | crypto      切换独立板块
            // /mstock forex list [板块] [页码]
            // /mstock forex search <关键词> [板块]
            case "fiat", "crypto" -> {
                ForexCategory category = ForexCategory.parse(action);
                if (category == null) {
                    lang.sendNoPrefix(player, "usage");
                    return;
                }
                forexGui.openMarket(player, category, "", 0);
            }
            case "list", "ls" -> {
                ForexCategory category = args.length >= 3
                        ? ForexCategory.parse(args[2]) : null;
                if (args.length >= 3 && category == null) {
                    lang.send(player, "forex-category-invalid");
                    return;
                }
                int page = 0;
                if (args.length >= 4) {
                    try {
                        page = Math.max(0, Integer.parseInt(args[3]) - 1);
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                        return;
                    }
                }
                forexGui.openMarket(player, category, "", page);
            }
            case "search", "find", "s" -> {
                if (args.length < 3) {
                    // 无关键词 → 打开搜索会话
                    startSymbolSearch(player, null);
                    return;
                }
                ForexCategory category = args.length >= 4
                        ? ForexCategory.parse(args[3]) : null;
                if (args.length >= 4 && category == null) {
                    lang.send(player, "forex-category-invalid");
                    return;
                }
                // 无板块参数时把剩余全部参数当关键词（支持带空格的搜索词）；
                // 指定板块时关键词只取 args[2]。
                int keywordEnd = args.length >= 4 ? 3 : args.length;
                showSymbolSearch(player, joinKeyword(args, 2, keywordEnd), category);
            }
            case "menu" -> forexGui.openCategory(player);
            default -> lang.sendNoPrefix(player, "usage");
        }
    }

    /** 打开聊天框搜索输入（GUI 的"搜索"按钮与 {@code /mstock forex search} 共用）。 */
    private void startSymbolSearch(Player player, ForexCategory category) {
        startSymbolSearchInput(player, keyword -> showSymbolSearch(player, keyword, category));
    }

    /** 用聊天消息列出搜索结果；同时给出进入 GUI 的入口。 */
    private void showSymbolSearch(Player player, String keyword, ForexCategory category) {
        ForexService service = plugin.getForexService();
        if (service == null) {
            lang.send(player, "vault-not-found");
            return;
        }
        lang.send(player, "api-fetching",
                "code", keyword == null || keyword.isBlank() ? "*" : keyword);

        service.search(category, keyword, 30).thenAccept(symbols ->
                runOnPlayer(player, () -> {
                    String categoryLabel = category == null ? "全部" : category.label();
                    if (symbols.isEmpty()) {
                        lang.send(player, "forex-search-empty",
                                "keyword", keyword == null ? "" : keyword,
                                "category", categoryLabel);
                        return;
                    }
                    lang.sendNoPrefix(player, "forex-search-header",
                            "keyword", keyword == null || keyword.isBlank() ? "*" : keyword,
                            "category", categoryLabel,
                            "count", String.valueOf(symbols.size()));
                    for (MarketSymbol symbol : symbols) {
                        player.sendMessage(lang.getNoPrefix("forex-search-item",
                                "symbol", symbol.getName(),
                                "desc", symbol.getDescription(),
                                "category", ForexCategory.labelOf(symbol)));
                    }
                    player.sendMessage(lang.getNoPrefix("forex-search-hint",
                            "keyword", keyword == null ? "" : keyword,
                            "category", category == null ? "all" : category.id()));
                })
        ).exceptionally(error -> {
            plugin.getLogger().warning("外汇搜索失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    /** 把 {@code args[from, endExclusive)} 拼成一个关键词（支持带空格的搜索词）。 */
    private static String joinKeyword(String[] args, int from, int endExclusive) {
        int end = Math.min(endExclusive, args.length);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < end; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private void tradeForex(Player player, String symbol, double amount, boolean isBuy) {
        ForexService service = plugin.getForexService();
        if (service == null) {
            lang.send(player, "vault-not-found");
            return;
        }
        if (checkCooldown(player)) return;
        lang.send(player, "api-fetching", "code", symbol.toUpperCase());

        CompletableFuture<TradeOutcome> pending = isBuy
                ? service.buy(player.getUniqueId(), symbol, amount)
                : service.sell(player.getUniqueId(), symbol, amount);
        pending.thenAccept(outcome -> runOnPlayer(player, () ->
                player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))
        )).exceptionally(error -> {
            plugin.getLogger().warning("外汇交易失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private void showForexPrice(Player player, String symbol) {
        ForexService service = plugin.getForexService();
        if (service == null) {
            lang.send(player, "vault-not-found");
            return;
        }
        if (checkCooldown(player)) return;
        lang.send(player, "api-fetching", "code", symbol.toUpperCase());

        service.quote(symbol).thenAccept(quote -> runOnPlayer(player, () -> {
            boolean up = quote.getDayDiffPercent() >= 0;
            player.sendMessage(lang.getNoPrefix("forex-price-header",
                    "symbol", quote.getSymbol(),
                    "desc", quote.getDescription(),
                    "price", format6(quote.getMid())));
            if (quote.isMarketClosed()) {
                player.sendMessage(lang.getNoPrefix("forex-market-closed"));
            } else {
                player.sendMessage(lang.getNoPrefix(up ? "forex-price-up" : "forex-price-down",
                        "change", format2(Math.abs(quote.getDayDiffPercent()))));
            }
            player.sendMessage(lang.getNoPrefix("forex-price-detail",
                    "bid", format6(quote.getBid()),
                    "ask", format6(quote.getAsk()),
                    "high", format6(quote.getHigh()),
                    "low", format6(quote.getLow())));
        })).exceptionally(error -> {
            plugin.getLogger().warning("获取外汇报价失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    // ── 新子指令：期货 ────────────────────────────────────────────────────────

    private void handleFutures(Player player, String[] args) {
        if (args.length < 2) {
            futuresGui.open(player, null, 1.0);
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "open" -> {
                // /mstock futures open <symbol> <direction> <quantity>
                if (args.length < 4) {
                    lang.sendNoPrefix(player, "usage");
                    return;
                }
                if (!player.hasPermission("minestock.futures.trade")) {
                    lang.send(player, "no-permission");
                    return;
                }
                TradeDirection direction = TradeDirection.parse(args[3]);
                if (direction == null) {
                    lang.send(player, "trade-invalid-direction");
                    return;
                }
                double quantity = 1.0;
                if (args.length >= 5) {
                    try {
                        quantity = Double.parseDouble(args[4]);
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                        return;
                    }
                }
                openFutures(player, args[2], direction, quantity);
            }
            case "close" -> {
                if (args.length < 3) {
                    lang.sendNoPrefix(player, "usage");
                    return;
                }
                if (!player.hasPermission("minestock.futures.trade")) {
                    lang.send(player, "no-permission");
                    return;
                }
                long id;
                try {
                    id = Long.parseLong(args[2]);
                } catch (NumberFormatException e) {
                    lang.send(player, "input-invalid");
                    return;
                }
                closeFutures(player, id);
            }
            case "positions", "list" -> showFuturesPositions(player);
            case "contracts" -> showFuturesContracts(player);
            case "menu" -> futuresGui.open(player, null, 1.0);
            default -> lang.sendNoPrefix(player, "usage");
        }
    }

    private void openFutures(Player player, String symbol, TradeDirection direction,
                             double quantity) {
        FuturesService service = plugin.getFuturesService();
        if (service == null) {
            lang.send(player, "vault-not-found");
            return;
        }
        if (config.getApiCooldownMs() > 0 && checkCooldown(player)) return;

        service.open(player.getUniqueId(), symbol, direction, quantity)
                .thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))
                )).exceptionally(error -> {
            plugin.getLogger().warning("期货开仓失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private void closeFutures(Player player, long positionId) {
        FuturesService service = plugin.getFuturesService();
        if (service == null) {
            lang.send(player, "vault-not-found");
            return;
        }
        service.close(player.getUniqueId(), positionId)
                .thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))
                )).exceptionally(error -> {
            plugin.getLogger().warning("期货平仓失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private void showFuturesPositions(Player player) {
        FuturesService service = plugin.getFuturesService();
        if (service == null) {
            lang.send(player, "vault-not-found");
            return;
        }
        lang.sendNoPrefix(player, "futures-positions-header");
        service.positions(player.getUniqueId()).thenAccept(positions ->
                runOnPlayer(player, () -> {
                    if (positions.isEmpty()) {
                        lang.sendNoPrefix(player, "futures-positions-empty");
                        return;
                    }
                    for (FuturesPosition position : positions) {
                        boolean up = position.getRealizedPnl() >= 0;
                        player.sendMessage(lang.getNoPrefix(
                                up ? "futures-position-up" : "futures-position-down",
                                "id", String.valueOf(position.getId()),
                                "symbol", position.getSymbol(),
                                "direction", position.getDirection().name(),
                                "quantity", formatQty(position.getQuantity()),
                                "entry", format6(position.getEntryPrice()),
                                "margin", format2(position.getMargin()),
                                "pnl", format2(Math.abs(position.getRealizedPnl())),
                                "expiry", position.getExpiryDate()));
                    }
                })
        ).exceptionally(error -> {
            plugin.getLogger().warning("查询期货持仓失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "db-error"));
            return null;
        });
    }

    private void showFuturesContracts(Player player) {
        FuturesService service = plugin.getFuturesService();
        if (service == null) {
            lang.send(player, "vault-not-found");
            return;
        }
        lang.sendNoPrefix(player, "futures-contracts-header");
        service.activeContracts().thenAccept(contracts ->
                runOnPlayer(player, () -> {
                    if (contracts.isEmpty()) {
                        lang.sendNoPrefix(player, "futures-contracts-empty");
                        return;
                    }
                    for (FuturesContract contract : contracts) {
                        player.sendMessage(lang.getNoPrefix("futures-contract-item",
                                "symbol", contract.getSymbol(),
                                "expiry", contract.getExpiryDate(),
                                "size", format2(contract.getContractSize()),
                                "tick", format4(contract.getTickSize()),
                                "leverage", String.valueOf(contract.getLeverage())));
                    }
                })
        ).exceptionally(error -> {
            plugin.getLogger().warning("查询期货合约失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    // ── 新子指令：图表 ────────────────────────────────────────────────────────

    /** {@code /mstock chart <symbol> [interval] [limit]}，默认 1h / 20，最大 30，3 秒冷却。 */
    /**
     * 记住"看完 K 线后回哪儿去"。
     *
     * <p>由界面在打开图表<b>之前</b>调用：图表是聊天消息，一打开原界面就没了，
     * 事后再问"你从哪来"已经无从得知。同一次进入会覆盖上一次的记录 ——
     * 只有最近一次来源有意义。
     *
     * @param command 返回时要执行的指令，<b>不含</b>前导斜杠（{@code performCommand} 的约定）
     * @param label   提示文案里的可读目标名（如"持仓总览"）
     */
    public void rememberChartReturn(Player player, String command, String label) {
        if (player == null || command == null || command.isBlank()) {
            return;
        }
        chartReturnCommands.put(player.getUniqueId(), command.strip());
        chartReturnLabels.put(player.getUniqueId(), label == null ? "" : label);
    }

    /**
     * {@code /mstock back}：回到上次看图前的位置。
     *
     * <p>记录是一次性的（读完即删）：否则玩家在别处反复敲 {@code back} 会被反复弹回
     * 同一个界面，且该记录会永久占住内存。
     *
     * @return {@code true} 表示已跳转；{@code false} 表示没有记录，由调用方发提示
     */
    public boolean returnFromChart(Player player) {
        if (player == null) {
            return false;
        }
        String command = chartReturnCommands.remove(player.getUniqueId());
        chartReturnLabels.remove(player.getUniqueId());
        if (command == null || command.isBlank()) {
            return false;
        }
        player.performCommand(command);
        return true;
    }

    private void handleChart(Player player, String[] args) {
        if (args.length < 2) {
            lang.sendNoPrefix(player, "usage");
            return;
        }
        // /mstock chart return 与 /mstock back 等价：玩家看完图后最自然的动作是在
        // 同一个子命令下敲 return，而不是去猜还有没有别的顶层指令。
        if ("return".equalsIgnoreCase(args[1]) || "back".equalsIgnoreCase(args[1])) {
            if (!returnFromChart(player)) {
                lang.send(player, "chart-return-none");
            }
            return;
        }
        String interval = args.length >= 3 ? args[2] : null;
        int limit = config.getChartDefaultLimit();
        if (args.length >= 4) {
            try {
                limit = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                lang.send(player, "input-invalid");
                return;
            }
        }
        requestChart(player, args[1], interval, limit);
    }

    /**
     * 图表请求的唯一入口：{@code /mstock chart}、{@code /mstock forex chart}
     * 以及 GUI 的"查看K线"按钮最终都走到这里。
     */
    public void requestChart(Player player, String symbol, String interval, int limit) {
        if (!player.hasPermission("minestock.chart")) {
            lang.send(player, "no-permission");
            return;
        }
        ChartService chart = plugin.getChartService();
        if (chart == null) {
            lang.send(player, "api-error");
            return;
        }
        String tf = interval;
        if (tf != null && !ForexProvider.isSupportedInterval(tf)) {
            lang.send(player, "chart-interval-invalid");
            return;
        }
        int capped = Math.max(2, Math.min(chart.maxLimit(), limit));

        long remaining = chart.checkCooldown(player.getUniqueId());
        if (remaining > 0) {
            lang.send(player, "api-cooldown",
                    "seconds", (remaining / 1000) + "." + (remaining % 1000 / 100) + " 秒");
            return;
        }
        lang.send(player, "chart-loading", "symbol", symbol.toUpperCase());
        // 返回提示随图表一起发出（而不是留在聊天记录更上方），玩家看完最后一根柱子
        // 往下扫一眼就能点到返回；只有"从界面点进来"的记录存在时才发。
        String returnCommand = chartReturnCommands.get(player.getUniqueId());
        chart.send(player, chart.chart(symbol, tf, capped),
                () -> lang.send(player, "chart-error"),
                returnCommand == null || returnCommand.isBlank() ? null : "chart-return-line",
                "command", "/" + returnCommand,
                "label", chartReturnLabels.getOrDefault(player.getUniqueId(), "上一页"));
    }

    // ── 模拟盘 ────────────────────────────────────────────────────────────────

    /**
     * {@code /mstock simulation ...} 子指令树。
     *
     * <p>权限在<b>本层第一行</b>判定，Service 层再判一次前置条件（设计的"双重校验"）：
     * 指令层保证"谁能用"，Service 层保证"能不能用"（存在性 / 启用态 / 停牌）。
     *
     * <p>路由表：
     * <pre>
     * /mstock simulation                          → 行情列表 GUI
     * /mstock simulation list [页码]              → 行情列表 GUI（指定页）
     * /mstock simulation &lt;SIMXXX&gt;                → 复用真实盘详情渲染（模拟源已在 index 0）
     * /mstock simulation buy  &lt;代码&gt; &lt;数量&gt; [杠杆] → SimulationService.buy
     * /mstock simulation sell &lt;代码&gt; &lt;数量&gt;       → SimulationService.sell
     * /mstock simulation portfolio                → 模拟现金持仓
     * /mstock simulation admin [动作 ...]         → 管理控制台 / 19 个动作
     * </pre>
     */
    private void handleSimulation(Player player, String[] args) {
        if (!player.hasPermission("minestock.simulation.use")) {
            lang.send(player, "no-permission");
            return;
        }
        if (args.length < 2) {
            simulationGui.open(player, 0);
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "list" -> {
                if (args.length >= 3) {
                    try {
                        simulationGui.open(player, Math.max(0, Integer.parseInt(args[2]) - 1));
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                    }
                } else {
                    simulationGui.open(player, 0);
                }
            }
            case "buy" -> simulateBuy(player, args);
            case "sell" -> simulateSell(player, args);
            case "portfolio" -> showSimulationPortfolio(player);
            case "admin" -> handleSimulationAdmin(player, args);
            default -> {
                // 其余一律按代码处理：复用真实盘详情渲染（价格 / K线 / 图表零改动生效）
                if (args.length == 2) {
                    showStockDetail(player, args[1].toUpperCase());
                } else {
                    lang.sendNoPrefix(player, "usage");
                }
            }
        }
    }

    private void simulateBuy(Player player, String[] args) {
        SimulationService service = plugin.getSimulationService();
        if (service == null) {
            lang.send(player, "simulation-disabled");
            return;
        }
        // 三段式 /mstock simulation buy <代码> 走聊天栏补输。
        // 复用 initBuy 而不是另写一套：补输完成后 executeBuy 的第一件事就是
        // routeToSimulation，模拟盘代码会被自动分流到模拟盘买入。
        if (args.length == 3) {
            initBuy(player, args[2].toUpperCase());
            return;
        }
        if (args.length != 4 && args.length != 5) {
            lang.sendNoPrefix(player, "usage");
            return;
        }
        int quantity;
        int leverage;
        try {
            quantity = Integer.parseInt(args[3]);
            leverage = args.length == 5 ? Integer.parseInt(args[4]) : 1;
        } catch (NumberFormatException e) {
            lang.send(player, "input-invalid");
            return;
        }
        service.buy(player.getUniqueId(), args[2].toUpperCase(), quantity, leverage)
                .thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                .exceptionally(error -> {
                    plugin.getLogger().warning("模拟盘买入失败: " + error.getMessage());
                    runOnPlayer(player, () -> lang.send(player, "api-error"));
                    return null;
                });
    }

    private void simulateSell(Player player, String[] args) {
        SimulationService service = plugin.getSimulationService();
        if (service == null) {
            lang.send(player, "simulation-disabled");
            return;
        }
        // 三段式 /mstock simulation sell <代码> = 清空该标的的现金持仓，
        // 供持仓操作菜单的"卖出"按钮使用（GUI 里没有数量输入框）。
        if (args.length == 3) {
            String code = args[2].toUpperCase();
            service.sellAll(player.getUniqueId(), code)
                    .thenAccept(outcome -> runOnPlayer(player, () ->
                            player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                    .exceptionally(error -> {
                        plugin.getLogger().warning("模拟盘卖出失败: " + error.getMessage());
                        runOnPlayer(player, () -> lang.send(player, "api-error"));
                        return null;
                    });
            return;
        }
        if (args.length != 4) {
            lang.sendNoPrefix(player, "usage");
            return;
        }
        int quantity;
        try {
            quantity = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            lang.send(player, "input-invalid");
            return;
        }
        service.sell(player.getUniqueId(), args[2].toUpperCase(), quantity)
                .thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                .exceptionally(error -> {
                    plugin.getLogger().warning("模拟盘卖出失败: " + error.getMessage());
                    runOnPlayer(player, () -> lang.send(player, "api-error"));
                    return null;
                });
    }

    /**
     * {@code /mstock simulation portfolio}：模拟盘<b>现金</b>持仓。
     *
     * <p>与 {@code showPortfolio} 分开是刻意的：真实盘持仓界面不该混入模拟持仓。
     * {@code market=SIMULATION} 的杠杆仓位由 {@code /mstock margin} 呈现。
     *
     * <p>用 {@code portfolio()} 而不是 {@code holdings()}：持仓行里存的均价已经是含
     * price-ratio 的成交价（{@code SimulationService.tradePrice}），而现价要按
     * {@code marketPriceOf} 现算 —— 只有 {@code portfolio()} 保证了这两个口径一致，
     * 在这里再手工取价会重复乘一次 ratio，凭空造出盈亏。
     */
    private void showSimulationPortfolio(Player player) {
        SimulationService service = plugin.getSimulationService();
        if (service == null) {
            lang.send(player, "simulation-disabled");
            return;
        }
        lang.sendNoPrefix(player, "simulation-portfolio-header");
        service.portfolio(player.getUniqueId())
                .thenAccept(views -> runOnPlayer(player, () -> {
                    if (views.isEmpty()) {
                        lang.sendNoPrefix(player, "simulation-portfolio-empty");
                        return;
                    }
                    for (var view : views) {
                        // 三向分支：涨 / 跌 / 持平。simulation-portfolio-item
                        // 是中性条目，py 恰好为 0 时用它（避免把"没赚没赔"染成红色）。
                        String key = view.pnl() > 0 ? "simulation-portfolio-item-up"
                                : view.pnl() < 0 ? "simulation-portfolio-item-down"
                                : "simulation-portfolio-item";
                        player.sendMessage(lang.getNoPrefix(key,
                                "code", view.code(),
                                "name", view.name(),
                                "amount", String.valueOf(view.amount()),
                                "avg_cost", format2(view.avgCost()),
                                "price", format2(view.price()),
                                "pnl", format2(view.pnl()),
                                "change", (view.pnlPercent() >= 0 ? "+" : "")
                                        + format2(view.pnlPercent()) + "%"));
                    }
                }))
                .exceptionally(error -> {
                    plugin.getLogger().warning("模拟盘持仓查询失败: " + error.getMessage());
                    runOnPlayer(player, () -> lang.send(player, "db-error"));
                    return null;
                });
    }

    /**
     * {@code /mstock simulation admin ...}：管理控制台与 19 个动作。
     *
     * <p>每个动作的 Service 方法都自带参数合法性与存在性校验（"双重校验"的第二层），
     * 这里只负责把字符串解析成强类型参数、并把 {@code TradeOutcome} 发回玩家。
     */
    private void handleSimulationAdmin(Player player, String[] args) {
        if (!player.hasPermission("minestock.simulation.admin")) {
            lang.send(player, "simulation-admin-no-permission");
            return;
        }
        if (args.length < 3) {
            simulationAdminGui.open(player, 0);
            return;
        }
        SimulationService service = plugin.getSimulationService();
        if (service == null) {
            lang.send(player, "simulation-disabled");
            return;
        }
        String action = args[2].toLowerCase();

        // add / remove 的参数布局与其余动作不同：没有"先代码后参数"的前缀，
        // 必须在 code = args[3] 之前单独分流，否则 args[3] 会被当成代码。
        if ("add".equals(action)) {
            if (args.length != 7) {
                lang.send(player, "simulation-admin-invalid-args",
                        "usage", SIMULATION_ADMIN_ADD_USAGE);
                return;
            }
            double initialPrice;
            try {
                initialPrice = Double.parseDouble(args[6]);
            } catch (NumberFormatException e) {
                lang.send(player, "input-invalid");
                return;
            }
            sendSimulationAdminOutcome(player,
                    service.registerStock(args[3], args[4], args[5], initialPrice));
            return;
        }
        if ("remove".equals(action)) {
            if (args.length != 4) {
                lang.send(player, "simulation-admin-invalid-args",
                        "usage", SIMULATION_ADMIN_REMOVE_USAGE);
                return;
            }
            sendSimulationAdminOutcome(player, service.unregisterStock(args[3]));
            return;
        }

        // 只读动作先处理：它返回的是列表而不是 TradeOutcome
        if ("schedules".equals(action)) {
            service.schedules()
                    .thenAccept(schedules -> runOnPlayer(player, () ->
                            sendScheduleList(player, schedules)))
                    .exceptionally(error -> {
                        plugin.getLogger().warning("模拟盘定时查询失败: " + error.getMessage());
                        runOnPlayer(player, () -> lang.send(player, "db-error"));
                        return null;
                    });
            return;
        }

        String code = args.length >= 4 ? args[3].toUpperCase() : null;
        CompletableFuture<TradeOutcome> pending;
        try {
            pending = dispatchSimulationAdmin(service, action, code, args);
        } catch (NumberFormatException e) {
            lang.send(player, "input-invalid");
            return;
        }
        if (pending == null) {
            lang.send(player, "simulation-admin-invalid-args", "usage", SIMULATION_ADMIN_USAGE);
            return;
        }
        pending.thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                .exceptionally(error -> {
                    plugin.getLogger().warning("模拟盘管理操作失败: " + error.getMessage());
                    runOnPlayer(player, () -> lang.send(player, "api-error"));
                    return null;
                });
    }

    /** 管理员动作的统一回调：结果落 lang，异常落 api-error。 */
    private void sendSimulationAdminOutcome(Player player,
                                            CompletableFuture<TradeOutcome> pending) {
        pending.thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                .exceptionally(error -> {
                    plugin.getLogger().warning("模拟盘管理操作失败: " + error.getMessage());
                    runOnPlayer(player, () -> lang.send(player, "api-error"));
                    return null;
                });
    }

    /** 动作名 → Service 方法。参数不足 / 越界一律抛 {@link NumberFormatException} 或返回 {@code null}。 */
    private CompletableFuture<TradeOutcome> dispatchSimulationAdmin(
            SimulationService service, String action, String code, String[] args) {
        return switch (action) {
            case "setprice" -> args.length == 5
                    ? service.setPrice(code, Double.parseDouble(args[4])) : null;
            case "target" -> args.length == 5
                    ? service.setTargetPercent(code, Double.parseDouble(args[4])) : null;
            case "jump" -> args.length == 5
                    ? service.jump(code, Double.parseDouble(args[4])) : null;
            case "pause" -> args.length == 5
                    ? service.setPaused(code, parseOnOff(args[4])) : null;
            case "suspend" -> args.length == 5
                    ? service.setSuspended(code, parseOnOff(args[4])) : null;
            case "tradable" -> args.length == 5
                    ? service.setTradable(code, parseOnOff(args[4])) : null;
            case "slope" -> args.length == 6
                    ? service.setSlope(code, Double.parseDouble(args[4]),
                            Long.parseLong(args[5])) : null;
            case "amplitude" -> args.length == 5
                    ? service.setAmplitude(code, Double.parseDouble(args[4])) : null;
            case "preset" -> args.length == 5
                    ? service.setPreset(code, SimulationVolatilityPreset.parse(args[4])) : null;
            case "bounds" -> args.length == 6
                    ? service.setBounds(code, Double.parseDouble(args[4]),
                            Double.parseDouble(args[5])) : null;
            case "limit" -> args.length == 5
                    ? service.setLimitPercent(code, Double.parseDouble(args[4])) : null;
            case "boundary" -> args.length == 5
                    ? service.setBoundary(code, SimulationBoundary.parse(args[4])) : null;
            case "link" -> args.length == 6
                    ? service.link(code, args[4].toUpperCase(), Double.parseDouble(args[5])) : null;
            case "unlink" -> args.length == 5
                    ? service.unlink(code, args[4].toUpperCase()) : null;
            case "schedule" -> args.length >= 6
                    ? service.schedule(code, args[4], Double.parseDouble(args[5]),
                            args.length >= 7 && parseOnOff(args[6])) : null;
            case "unschedule" -> args.length == 4 ? service.unschedule(Long.parseLong(args[3])) : null;
            case "seed" -> {
                if (args.length == 4) yield service.reseed(code, null);
                if (args.length == 5) yield service.reseed(code, Long.parseLong(args[4]));
                yield null;
            }
            case "resume" -> args.length == 4 ? service.resume(code) : null;
            case "reset" -> args.length == 4 ? service.reset(code) : null;
            default -> null;
        };
    }

    private void sendScheduleList(Player player, List<SimulationSchedule> schedules) {
        if (schedules.isEmpty()) {
            lang.sendNoPrefix(player, "simulation-admin-schedules-empty");
            return;
        }
        lang.sendNoPrefix(player, "simulation-admin-schedules");
        for (SimulationSchedule schedule : schedules) {
            player.sendMessage(lang.getNoPrefix("simulation-admin-schedules-item",
                    "id", String.valueOf(schedule.id()),
                    "code", schedule.code(),
                    "time", schedule.timeOfDay(),
                    "percent", format2(schedule.changePercent()),
                    "mode", schedule.repeatDaily() ? "daily" : "once"));
        }
    }

    /** {@code on/off}（大小写不敏感）。其它输入按 {@code false}，由 Service 层再校验语义。 */
    private static boolean parseOnOff(String raw) {
        return "on".equalsIgnoreCase(raw.strip()) || "true".equalsIgnoreCase(raw.strip());
    }

    /**
     * 主菜单入口的统一权限判定（{@code minestock.menu}，{@code plugin.yml} 里已声明）。
     *
     * <p>该节点此前只声明、从不校验，于是把 {@code minestock.menu} 设为 {@code false}
     * 在服务端毫无效果 —— 声明了却不生效的权限会让管理员以为自己关掉了入口。
     * 现在三条入口（无参、{@code /mstock menu}、{@code _gui_menu}）共用本方法，
     * 保证不会只堵住其中一条而被其余两条绕过。
     *
     * @return {@code true} 表示有权打开；{@code false} 表示已发送拒绝消息，调用方必须立即 return
     */
    private boolean canOpenMenu(Player player) {
        if (player.hasPermission("minestock.menu")) return true;
        lang.send(player, "no-permission");
        return false;
    }

    /**
     * 模拟盘代码拦截：命中则整单交给 {@link SimulationService}，
     * <b>绝不触碰 {@code holdings} / {@code margin_positions} 的现金语义</b>。
     *
     * <p><b>为什么必须有这一层</b>：模拟行情源排在 {@code StockApiService} 的
     * {@code sources[0]}，因此 {@code api.isSupported("SIM001")} 返回 {@code true}。
     * 不拦截的话 {@code /mstock buy SIM001 10 5} 会以 {@code market=EQUITY} 落一条
     * <b>真实</b>股票保证金仓位，{@code /mstock buy SIM001 10} 更会把模拟股写进
     * <b>真实</b> {@code holdings} 表并扣 Vault 余额 —— 两条都会污染真实盘。
     *
     * @return {@code true} 表示本单已被模拟盘接管（调用方必须立即 return）
     */
    private boolean routeToSimulation(Player player, String code, int amount, int leverage) {
        SimulationService service = plugin.getSimulationService();
        // 先按"代码归属"判断，再判启用状态 —— 顺序是资金级的：
        // 若把 !isEnabled() 并进这一行，关闭态就会 return false 放行，而 api.isSupported("SIM001")
        // 在 reload 之后可能仍为 true（注册表被 reconfigure 逐只搬迁且不清空），
        // 本单便会以真实股票身份写入 holdings 并扣 Vault。改成本方法接管并报 simulation-disabled。
        if (service == null || !service.isSimulationCode(code)) return false;
        if (!player.hasPermission("minestock.simulation.use")) {
            lang.send(player, "no-permission");
            return true;
        }
        if (!service.isEnabled()) {
            lang.send(player, "simulation-disabled");
            return true;
        }
        service.buy(player.getUniqueId(), code, amount, leverage)
                .thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                .exceptionally(error -> {
                    plugin.getLogger().warning("模拟盘买入失败: " + error.getMessage());
                    runOnPlayer(player, () -> lang.send(player, "api-error"));
                    return null;
                });
        return true;
    }

    /** {@link #routeToSimulation} 的卖出版本。 */
    private boolean routeToSimulationSell(Player player, String code, int amount) {
        SimulationService service = plugin.getSimulationService();
        // 顺序同 routeToSimulation：先判归属，再判启用（关闭态要接管报错，不能放行）。
        if (service == null || !service.isSimulationCode(code)) return false;
        if (!player.hasPermission("minestock.simulation.use")) {
            lang.send(player, "no-permission");
            return true;
        }
        if (!service.isEnabled()) {
            lang.send(player, "simulation-disabled");
            return true;
        }
        service.sell(player.getUniqueId(), code, amount)
                .thenAccept(outcome -> runOnPlayer(player, () ->
                        player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                .exceptionally(error -> {
                    plugin.getLogger().warning("模拟盘卖出失败: " + error.getMessage());
                    runOnPlayer(player, () -> lang.send(player, "api-error"));
                    return null;
                });
        return true;
    }

    // Shorthand: run on the player's region thread (Folia) or main thread (Paper/Spigot)
    private boolean runOnPlayer(Player player, Runnable task) {
        if (!plugin.isOperational()) return false;
        try {
            CompatScheduler.get().runOnEntity(plugin, player, () -> {
                if (plugin.isOperational()) task.run();
            });
            return true;
        } catch (IllegalStateException ignored) {
            // The plugin was disabled between the lifecycle check and scheduling.
            return false;
        }
    }

    // Shorthand: run on an async thread
    private void runAsync(Runnable task) {
        LifecycleTaskManager tasks = plugin.getTaskManager();
        if (tasks != null) tasks.runAsync(task);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!plugin.isOperational()) return true;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此命令只能由玩家执行");
            return true;
        }

        if (!economy.isReady()) {
            lang.send(player, "vault-not-found");
            return true;
        }

        if (args.length == 0) {
            // 无参 → 打开统一主菜单 GUI；指令列表移到 /mstock help
            if (!canOpenMenu(player)) return true;
            mainMenuGui.open(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help", "?" -> handleHelp(player, args);
            case "back", "return" -> {
                if (!returnFromChart(player)) {
                    lang.send(player, "chart-return-none");
                }
            }
            case "menu" -> {
                if (!canOpenMenu(player)) return true;
                mainMenuGui.open(player);
            }
            case "positions", "pos" -> portfolioHubGui.open(player);
            case "_gui_menu" -> {
                if (!canOpenMenu(player)) return true;
                mainMenuGui.open(player);
            }
            case "_gui_positions" -> portfolioHubGui.open(player);
            case "_gui_leverage" -> leverageGui.open(player);
            case "_gui_forex" -> forexGui.openCategory(player);
            case "_gui_futures" -> futuresGui.open(player, null, 1.0);
            case "leverage" -> handleLeverage(player, args);
            case "margin" -> handleMargin(player, args);
            case "forex" -> handleForex(player, args);
            case "futures" -> handleFutures(player, args);
            case "chart" -> handleChart(player, args);
            case "simulation", "sim" -> handleSimulation(player, args);
            case "buy" -> {
                // /mstock buy <代码> <数量> [杠杆]，省略数量时走聊天框补输
                if (args.length == 2) {
                    initBuy(player, args[1].toUpperCase());
                } else if (args.length == 3 || args.length == 4) {
                    try {
                        int amount = Integer.parseInt(args[2]);
                        int leverage = args.length == 4 ? Integer.parseInt(args[3]) : 0;
                        executeBuy(player, args[1].toUpperCase(), amount, leverage);
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                    }
                } else {
                    lang.sendNoPrefix(player, "usage");
                }
            }
            case "sell" -> {
                // /mstock sell <代码> <数量>，省略数量时走聊天框补输
                // （持仓操作菜单的"卖出"按钮就是发这条两段式指令）
                if (args.length == 2) {
                    initSell(player, args[1].toUpperCase());
                } else if (args.length == 3) {
                    try {
                        executeSell(player, args[1].toUpperCase(), Integer.parseInt(args[2]));
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                    }
                } else {
                    lang.sendNoPrefix(player, "usage");
                }
            }
            case "recommended", "rec", "r" -> showRecommendations(player);
            case "portfolio", "p" -> showPortfolio(player);
            case "pg" -> portfolioGUI.open(player);
            case "reload" -> {
                if (!player.hasPermission("minestock.admin")) {
                    lang.send(player, "reload-no-permission");
                    return true;
                }
                plugin.reload();
                lang.send(player, "reload-success");
            }
            case "_buy_init" -> {
                if (args.length == 2) {
                    initBuy(player, args[1].toUpperCase());
                }
            }
            case "_sell_init" -> {
                if (args.length == 2) {
                    initSell(player, args[1].toUpperCase());
                }
            }
            case "_cancel_confirm" -> {
                PendingAction pending = session.getSession(player.getUniqueId());
                if (pending != null && pending.isAwaitingConfirm()) {
                    session.clearSession(player.getUniqueId());
                    lang.send(player, "input-cancelled");
                }
            }
            case "_buy_confirm" -> {
                // /mstock _buy_confirm <code> <amount>
                if (args.length == 3) {
                    try {
                        int amount = Integer.parseInt(args[2]);
                        PendingAction action = session.getSession(player.getUniqueId());
                        if (action != null && action.getType() == PendingAction.Type.BUY
                                && action.getStockCode().equals(args[1].toUpperCase())
                                && action.isAwaitingConfirm()) {
                            session.clearSession(player.getUniqueId());
                            executeBuy(player, args[1].toUpperCase(), amount);
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
            case "_sell_confirm" -> {
                // /mstock _sell_confirm <code> <amount>
                if (args.length == 3) {
                    try {
                        int amount = Integer.parseInt(args[2]);
                        PendingAction action = session.getSession(player.getUniqueId());
                        if (action != null && action.getType() == PendingAction.Type.SELL
                                && action.getStockCode().equals(args[1].toUpperCase())
                                && action.isAwaitingConfirm()) {
                            session.clearSession(player.getUniqueId());
                            executeSell(player, args[1].toUpperCase(), amount);
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
            case "_kline" -> {
                // _kline <code> <days>
                if (args.length == 3) {
                    try {
                        int days = Integer.parseInt(args[2]);
                        showKLine(player, args[1].toUpperCase(), days);
                    } catch (NumberFormatException ignored) { }
                }
            }

            default -> {
                String code = args[0].toUpperCase();
                if (api.isSupported(code)) {
                    showStockDetail(player, code);
                } else {
                    lang.send(player, "unsupported-code", "code", code);
                }
            }
        }
        return true;
    }

    /** {@code /mstock help} 的总页数，与 lang.yml 的 {@code help-page-1..N} 一一对应。 */
    public static final int HELP_PAGES = 3;

    /** 分页帮助的正文键，下标 0 对应第 1 页；长度必须等于 {@link #HELP_PAGES}。 */
    private static final List<String> HELP_PAGE_KEYS = List.of("help-page-1", "help-page-2", "help-page-3");

    /**
     * {@code /mstock help [页码]}：把过去"一次性贴一段 usage"的帮助改成可翻页的完整清单。
     *
     * <p>过去 {@code case "help"} 直接发 lang.yml 的 {@code usage}，而那份文本只覆盖
     * 主流程的十来条指令；{@code positions} / {@code pg} / {@code menu} /
     * {@code leverage stock} / {@code margin close|closeall|topup} /
     * {@code forex fiat|crypto|list|search|chart} / {@code futures close|contracts} /
     * {@code simulation list|admin} 等一批已注册的指令在帮助里完全不可见。
     *
     * <p>这里刻意<b>不动</b> {@code usage} 本身：该键同时是十几处"参数非法"分支的短提示
     * （{@code handleForex}、{@code handleFutures}、{@code handleChart}、
     * {@code handleSimulation} 等），改写它会连带改变那些错误路径的输出。分页正文另起
     * {@code help-page-N} 键，与短提示解耦。
     *
     * <p>页码非法时只发 {@code help-page-invalid} 并且直接返回，不回落显示第一页——
     * 否则玩家把 {@code /mstock help abc} 打成第一页会误以为参数被接受了。
     */
    private void handleHelp(Player player, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException ex) {
                sendHelpPageInvalid(player, args[1]);
                return;
            }
            if (page < 1 || page > HELP_PAGES) {
                sendHelpPageInvalid(player, args[1]);
                return;
            }
        }

        String total = String.valueOf(HELP_PAGES);
        lang.sendNoPrefix(player, "help-header", "page", String.valueOf(page), "total", total);
        lang.sendNoPrefix(player, HELP_PAGE_KEYS.get(page - 1));
        lang.sendNoPrefix(player, "help-footer",
                "page", String.valueOf(page), "total", total,
                "prev", String.valueOf(Math.max(1, page - 1)),
                "next", String.valueOf(Math.min(HELP_PAGES, page + 1)));
    }

    private void sendHelpPageInvalid(Player player, String input) {
        lang.send(player, "help-page-invalid", "input", input, "total", String.valueOf(HELP_PAGES));
    }

    /**
     * 让玩家在聊天框输入数量（GUI 的"设置数量"按钮复用同一条通道）。
     *
     * <p>会话由原仓库的 {@link ChatInputSession} 承载，输入解析与取消逻辑
     * 复用 {@code ChatInputListener}，因此 GUI 与指令的输入校验完全一致。
     *
     * @param callback 输入合法后的一次性回调，在玩家线程执行
     */
    public void startQuantityInput(Player player, String symbol, PendingAction.Type type,
                                   java.util.function.DoubleConsumer callback) {
        player.closeInventory();
        quantityCallbacks.put(player.getUniqueId(), callback);
        session.startSession(player.getUniqueId(), new PendingAction(type, symbol));
        lang.send(player, "gui-quantity-prompt", "code", symbol == null ? "-" : symbol);
    }

    /**
     * 由 {@code ChatInputListener} 在玩家输入数量后调用。
     *
     * @return {@code true} 表示该输入已被 GUI 会话消费，不需要进入股票确认流程
     */
    public boolean consumeQuantityInput(Player player, PendingAction action, double amount) {
        if (action.getType() == PendingAction.Type.BUY || action.getType() == PendingAction.Type.SELL) {
            return false;
        }
        java.util.function.DoubleConsumer callback = quantityCallbacks.remove(player.getUniqueId());
        session.clearSession(player.getUniqueId());
        if (amount <= 0) {
            lang.send(player, "input-invalid");
            return true;
        }
        if (callback != null) callback.accept(amount);
        return true;
    }

    /** 取消 GUI 数量输入。 */
    public void cancelQuantityInput(Player player) {
        quantityCallbacks.remove(player.getUniqueId());
        symbolSearchCallbacks.remove(player.getUniqueId());
        session.clearSession(player.getUniqueId());
    }

    // ── GUI 文本搜索输入 ─────────────────────────────────────────────────────

    /**
     * 让玩家在聊天框输入搜索关键词（GUI 的"搜索"按钮复用同一条通道）。
     *
     * <p>与数量输入共用 {@link ChatInputSession}，但走文本分支：
     * {@link ChatInputListener} 识别出类型后回传给 {@link #consumeSymbolSearch(Player, String)}。
     *
     * @param callback 关键词合法后的一次性回调，在玩家线程执行
     */
    public void startSymbolSearchInput(Player player, java.util.function.Consumer<String> callback) {
        player.closeInventory();
        symbolSearchCallbacks.put(player.getUniqueId(), callback);
        session.startSession(player.getUniqueId(),
                new PendingAction(PendingAction.Type.SYMBOL_SEARCH, ""));
        lang.send(player, "forex-search-prompt");
    }

    /**
     * 由 {@code ChatInputListener} 在玩家输入关键词后调用。
     *
     * @return {@code true} 表示该输入已被搜索会话消费
     */
    public boolean consumeSymbolSearch(Player player, String keyword) {
        java.util.function.Consumer<String> callback =
                symbolSearchCallbacks.remove(player.getUniqueId());
        session.clearSession(player.getUniqueId());
        if (callback == null) return true;
        // 允许空关键词：等价于"清空搜索"，与指令语义一致
        callback.accept(keyword == null ? "" : keyword.strip());
        return true;
    }

    /** 玩家退出时清理。 */
    public void clearPlayerState(UUID playerUuid) {
        quantityCallbacks.remove(playerUuid);
        symbolSearchCallbacks.remove(playerUuid);
        cooldowns.remove(playerUuid);
    }

    private void showRecommendations(Player player) {
        if (checkCooldown(player)) return;

        lang.sendNoPrefix(player, "recommend-header");
        player.sendMessage(LangUtil.parse("  <gray>正在获取行情…</gray>"));
        recommendations.getRecommendations().thenAccept(stocks ->
                runOnPlayer(player, () -> {
                    if (stocks.isEmpty()) {
                        lang.send(player, "api-error");
                        return;
                    }
                    for (StockInfo info : stocks) player.sendMessage(buildRecommendLine(info));
                    lang.sendNoPrefix(player, "recommend-hint");
                })
        ).exceptionally(error -> {
            plugin.getLogger().warning("获取今日推荐失败: " + error.getMessage());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private Component buildRecommendLine(StockInfo info) {
        String key = info.getChangePercent() >= 0 ? "recommend-item-up" : "recommend-item-down";
        return lang.getNoPrefix(key,
                "code", info.getCode(),
                "name", info.getName(),
                "price", format2(info.getPrice() * config.getPriceRatio()),
                "change", format2(Math.abs(info.getChangePercent()))
        );
    }

    private void showStockDetail(Player player, String code) {
        if (checkCooldown(player)) return;

        StockQueryEvent queryEvent = new StockQueryEvent(player, code);
        plugin.getServer().getPluginManager().callEvent(queryEvent);
        if (queryEvent.isCancelled()) return;

        lang.send(player, "api-fetching", "code", code);

        CompletableFuture<StockInfo> priceFuture = api.fetch(code);
        CompletableFuture<List<KLinePoint>> klineFuture = api.fetchKLine(code, 30);

        priceFuture.thenCombine(klineFuture, (info, points) -> {
            runOnPlayer(player, () -> renderFullDetail(player, code, info, points, 30));
            return null;
        }).exceptionally(ex -> {
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private void showPortfolio(Player player) {
        StockPortfolioQueryEvent portfolioEvent = new StockPortfolioQueryEvent(player);
        plugin.getServer().getPluginManager().callEvent(portfolioEvent);
        if (portfolioEvent.isCancelled()) return;

        runAsync(() -> {
            try {
                List<Holding> holdings = holdingRepo.findAllByPlayer(player.getUniqueId());
                runOnPlayer(player, () -> {
                    lang.sendNoPrefix(player, "portfolio-header");
                    if (holdings.isEmpty()) {
                        lang.sendNoPrefix(player, "portfolio-empty");
                        return;
                    }
                    for (Holding h : holdings) {
                        api.fetch(h.getStockCode()).thenAccept(info ->
                                runOnPlayer(player, () -> {
                                    double avgCost = h.getAvgCost() * config.getPriceRatio();
                                    double currentPrice = info.getPrice() * config.getPriceRatio();
                                    double changePct = avgCost > 0
                                            ? (currentPrice - avgCost) / avgCost * 100.0
                                            : 0.0;
                                    String key = changePct >= 0 ? "portfolio-item-up" : "portfolio-item-down";
                                    player.sendMessage(lang.getNoPrefix(key,
                                            "code", h.getStockCode(),
                                            "name", info.getName(),
                                            "amount", String.valueOf(h.getAmount()),
                                            "avg_cost", format2(avgCost),
                                            "price", format2(currentPrice),
                                            "change", format2(Math.abs(changePct))
                                    ));
                                })
                        ).exceptionally(ex -> null);
                    }
                });
            } catch (SQLException e) {
                runOnPlayer(player, () -> lang.send(player, "db-error"));
            }
        });
    }

    /** Called by _kline command. Checks cooldown then fetches+renders the full detail page. */
    private void showKLine(Player player, String code, int days) {
        if (days < 1 || days > 365) return;
        if (checkCooldown(player)) return;
        if (!api.isSupported(code)) {
            lang.send(player, "unsupported-code", "code", code);
            return;
        }
        lang.send(player, "api-fetching", "code", code);

        CompletableFuture<StockInfo> priceFuture = api.fetch(code);
        CompletableFuture<List<KLinePoint>> klineFuture = api.fetchKLine(code, days);

        priceFuture.thenCombine(klineFuture, (info, points) -> {
            runOnPlayer(player, () -> renderFullDetail(player, code, info, points, days));
            return null;
        }).exceptionally(ex -> {
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    /**
     * Renders the complete stock detail page in one shot.
     * Must be called on the player's region thread.
     */
    private void renderFullDetail(Player player, String code, StockInfo info,
                                  List<KLinePoint> points, int days) {
        String headerKey = info.getChangePercent() >= 0 ? "stock-header-up" : "stock-header-down";
        lang.sendNoPrefix(player, headerKey,
                "name",   info.getName(),
                "code",   info.getCode(),
                "price",  format2(info.getPrice() * config.getPriceRatio()),
                "change", format2(Math.abs(info.getChangePercent())));

        List<String> chartLines = KLineRenderer.render(points);
        for (String line : chartLines) {
            player.sendMessage(LangUtil.parse(line));
        }

        lang.sendNoPrefix(player, "kline-current-period", "days", String.valueOf(days));
        player.sendMessage(LangUtil.parse(buildPeriodBar(code, days)));

        lang.sendNoPrefix(player, "stock-actions", "code", code);
    }

    private String buildPeriodBar(String code, int selectedDays) {
        int[] presetDays = {30, 90, 365};
        String[] presetLabels = {"1月", "3月", "1年"};

        StringBuilder sb = new StringBuilder("  ");
        for (int i = 0; i < presetDays.length; i++) {
            int d = presetDays[i];
            String label = presetLabels[i];
            if (d == selectedDays) {
                sb.append("<green>[ ").append(label).append(" ]</green>");
            } else {
                sb.append("<gray><click:run_command:'/mstock _kline ").append(code).append(" ").append(d).append("'>")
                  .append("<hover:show_text:'<gray>查看 ").append(label).append(" K线</gray>'>")
                  .append("[ ").append(label).append(" ]")
                  .append("</hover></click></gray>");
            }
            sb.append("  ");
        }
        return sb.toString();
    }

    private void initBuy(Player player, String code) {
        lang.send(player, "api-fetching", "code", code);
        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            runOnPlayer(player, () -> {
                session.startSession(player.getUniqueId(), new PendingAction(PendingAction.Type.BUY, code));
                lang.send(player, "buy-prompt",
                        "code", code,
                        "price", format2(unitPrice));
            });
        }).exceptionally(ex -> {
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    private void initSell(Player player, String code) {
        lang.send(player, "api-fetching", "code", code);
        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            runOnPlayer(player, () -> {
                session.startSession(player.getUniqueId(), new PendingAction(PendingAction.Type.SELL, code));
                lang.send(player, "sell-prompt",
                        "code", code,
                        "price", format2(unitPrice));
            });
        }).exceptionally(ex -> {
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    /**
     * Called from ChatInputListener after the player types an amount.
     * Must be called on the player's region thread.
     */
    public void showTradeConfirm(Player player, PendingAction action) {
        String code = action.getStockCode();
        int amount = action.getAmount();
        boolean isBuy = action.getType() == PendingAction.Type.BUY;

        lang.send(player, "api-fetching", "code", code);

        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            double subtotal  = round2(unitPrice * amount);
            double fee       = calcFee(subtotal);

            runOnPlayer(player, () -> {
                if (isBuy) {
                    double total = round2(subtotal + fee);
                    lang.sendNoPrefix(player, "buy-confirm",
                            "unit_price", format2(unitPrice),
                            "amount",     String.valueOf(amount),
                            "subtotal",   format2(subtotal),
                            "fee",        format2(fee),
                            "total",      format2(total));
                } else {
                    double income = round2(subtotal - fee);
                    lang.sendNoPrefix(player, "sell-confirm",
                            "unit_price", format2(unitPrice),
                            "amount",     String.valueOf(amount),
                            "subtotal",   format2(subtotal),
                            "fee",        format2(fee),
                            "income",     format2(income));
                }

                String confirmCmd = isBuy
                        ? "/mstock _buy_confirm " + code + " " + amount
                        : "/mstock _sell_confirm " + code + " " + amount;
                String confirmLine =
                        "<click:run_command:'/mstock _cancel_confirm'>" +
                        "<hover:show_text:'<gray>取消本次交易</gray>'>" +
                        "<red>[ 取消 ]</red></hover></click>" +
                        "   " +
                        "<click:run_command:'" + confirmCmd + "'>" +
                        "<hover:show_text:'<gray>确认并执行交易</gray>'>" +
                        "<green>[ 确认 ]</green></hover></click>";
                player.sendMessage(LangUtil.parse("  " + confirmLine));
            });
        }).exceptionally(ex -> {
            session.clearSession(player.getUniqueId());
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    public void executeBuy(Player player, String code, int amount) {
        executeBuy(player, code, amount, 0);
    }

    /**
     * 买入股票。
     *
     * <p><b>杠杆路由</b>：杠杆 &gt; 1 时开保证金仓位（冻结名义价值 / 杠杆，
     * 有强平风险）；杠杆 = 1（或不指定）走原仓库的现金买入路径，行为与历史版本一致。
     * 这样新功能完全向后兼容：老玩家不动设置就不会碰到保证金机制。
     *
     * <p><b>越权防护</b>：{@code leverage} 参数一律按<b>股票</b>制度校验。
     * 股票上限（默认 10）与外汇上限（默认 100）是两套数字，用外汇区间去校验
     * 会让 {@code /mstock buy AAPL 1 100} 开出 100 倍股票仓位。
     *
     * <p><b>关闭开关</b>：{@code leverage.stock.enabled: false} 时拒绝任何 &gt;1 的请求，
     * 而<b>不是</b>悄悄改走现金路径 —— 现金路径要花的钱是保证金的 {@code leverage} 倍，
     * 静默替换会让玩家花掉远超预期的余额。
     *
     * @param leverage 指定杠杆；{@code 0} 表示使用玩家当前的股票杠杆设置
     */
    public void executeBuy(Player player, String code, int amount, int leverage) {
        // 模拟盘拦截必须在最前面：initBuy → 确认页 → _buy_confirm 最终都汇入本方法，
        // 因此这里拦住即可同时覆盖"指令直买"与"确认页买"两条入口。
        // 在此之后 api.isSupported("SIM001") 会返回 true（模拟源在 index 0），
        // 不拦就会以 market=EQUITY 落真实保证金仓位 / 写真实 holdings 并扣 Vault。
        if (routeToSimulation(player, code, amount, leverage)) return;

        if (amount <= 0) {
            lang.send(player, "input-invalid");
            return;
        }

        if (!api.isSupported(code)) {
            lang.send(player, "unsupported-code", "code", code);
            return;
        }

        LeverageConfig leverageConfig = plugin.getLeverageConfig();
        if (leverageConfig == null) {
            lang.send(player, "db-error");
            return;
        }
        int maxStock = leverageConfig.getStockMaxLeverage();

        // 显式传入的杠杆先按股票制度校验，越界与"制度已关闭"都当场拒绝。
        // accepts() 已经涵盖 "enabled=false 时只接受 1x"，这里额外区分提示文案。
        if (leverage > 1) {
            if (!leverageConfig.isStockLeverageEnabled()) {
                lang.send(player, "leverage-stock-disabled");
                return;
            }
            if (!leverageConfig.accepts(LeverageConfig.Policy.STOCK, leverage)) {
                lang.send(player, "leverage-stock-out-of-range",
                        "max", String.valueOf(maxStock));
                return;
            }
        }

        // 到这里为止全是本地校验，之后必然要发网络请求。
        // 回执要发在冷却校验<b>之后</b>：先喊"正在处理买入"再回一句"操作过于频繁"，
        // 比什么都不说更让人困惑。

        // 杠杆 > 1：走保证金路径
        int effective = leverage > 0 ? leverage : playerStockLeverage(player);
        if (effective > 1) {
            var service = plugin.getStockLeverageService();
            if (service == null) {
                lang.send(player, "db-error");
                return;
            }
            if (checkCooldown(player)) return;
            lang.send(player, "trade-processing", "action", "买入");
            service.buy(player.getUniqueId(), code, amount, effective)
                    .thenAccept(outcome -> runOnPlayer(player, () ->
                            player.sendMessage(lang.get(outcome.langKey(), outcome.placeholders()))))
                    .exceptionally(error -> {
                        plugin.getLogger().warning("股票杠杆买入失败: " + error.getMessage());
                        runOnPlayer(player, () -> lang.send(player, "api-error"));
                        return null;
                    });
            return;
        }

        // 杠杆 = 1：现金买入（原仓库路径，行为不变）
        // 网络慢的时候最伤体验的不是等待本身，而是"点了确认，界面毫无反应" ——
        // 玩家会重复点击，反而压出更多请求。
        lang.send(player, "trade-processing", "action", "买入");
        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            double subtotal  = round2(unitPrice * amount);
            double fee       = calcFee(subtotal);
            double total     = round2(subtotal + fee);

            runOnPlayer(player, () -> {
                if (!economy.has(player, total)) {
                    lang.send(player, "not-enough-money",
                            "need",    format2(total),
                            "balance", format2(economy.getBalance(player)));
                    return;
                }

                StockPreBuyEvent preEvent = new StockPreBuyEvent(player, code, amount, unitPrice, fee, total);
                plugin.getServer().getPluginManager().callEvent(preEvent);
                if (preEvent.isCancelled()) return;

                LifecycleTaskManager tasks = plugin.getTaskManager();
                LifecycleTaskManager.CriticalOperation settlement =
                        tasks != null ? tasks.beginCritical() : null;
                if (settlement == null) return;

                boolean withdrawn;
                try {
                    withdrawn = economy.withdraw(player, total);
                } catch (RuntimeException e) {
                    settlement.close();
                    plugin.getLogger().severe("买入扣款异常: " + e.getMessage());
                    lang.send(player, "db-error");
                    return;
                }
                if (!withdrawn) {
                    settlement.close();
                    lang.send(player, "db-error");
                    return;
                }

                settlement.runAsync(() -> {
                    try {
                        holdingRepo.upsertBuy(player.getUniqueId(), code, amount, unitPrice);
                        runOnPlayer(player, () -> {
                            plugin.getServer().getPluginManager().callEvent(
                                    new StockBuyEvent(player, code, amount, unitPrice, fee, total));
                            lang.send(player, "buy-success",
                                    "code",   code,
                                    "amount", String.valueOf(amount),
                                    "total",  format2(total),
                                    "fee",    format2(fee));
                        });
                    } catch (Exception e) {
                        plugin.getLogger().warning("买入写库失败: " + e.getMessage());
                        scheduleCompensation(tasks, player, () -> {
                            if (!economy.deposit(player, total)) {
                                plugin.getLogger().severe("买入退款失败！玩家 " + player.getName());
                            }
                        });
                    }
                }).exceptionally(ex -> {
                    plugin.getLogger().severe("买入结算任务未执行: " + ex.getMessage());
                    scheduleCompensation(tasks, player, () -> {
                        if (!economy.deposit(player, total)) {
                            plugin.getLogger().severe("买入退款失败！玩家 " + player.getName());
                        }
                    });
                    return null;
                });
            });
        }).exceptionally(ex -> {
            runOnPlayer(player, () -> lang.send(player, "api-error"));
            return null;
        });
    }

    public void executeSell(Player player, String code, int amount) {
        // 同 executeBuy：模拟盘拦截放在最前面，绝不触碰真实持仓与 Vault。
        if (routeToSimulationSell(player, code, amount)) return;

        if (amount <= 0) {
            lang.send(player, "input-invalid");
            return;
        }

        // 先平杠杆仓位（先开先平），余下的数量走现金持仓。
        // 没有杠杆仓位时这一步是零成本查询，不影响原现金卖出路径。
        var stockService = plugin.getStockLeverageService();
        if (stockService != null) {
            stockService.closeLeveraged(player.getUniqueId(), code, amount)
                    .thenCompose(closed -> {
                        int remaining = amount - (int) Math.floor(closed.closedQuantity() + 1e-9);
                        if (remaining > 0) {
                            cashSell(player, code, remaining);
                        }
                        return CompletableFuture.completedFuture(null);
                    })
                    .exceptionally(error -> {
                        plugin.getLogger().warning("平仓杠杆股票失败: " + error.getMessage());
                        runOnPlayer(player, () -> lang.send(player, "api-error"));
                        return null;
                    });
            return;
        }

        cashSell(player, code, amount);
    }

    /**
     * 玩家当前的股票杠杆；服务未就绪时按 1（现金）处理。
     *
     * <p>{@code leverage.stock.enabled: false} 时恒为 1：关闭股票杠杆后，
     * 即使玩家库里存着历史的高杠杆设置，买入也必须落回全额现金路径。
     */
    private int playerStockLeverage(Player player) {
        var manager = plugin.getLeverageManager();
        if (manager == null) return 1;
        var leverageConfig = plugin.getLeverageConfig();
        if (leverageConfig != null && !leverageConfig.isStockLeverageEnabled()) return 1;
        return manager.getStockLeverage(player.getUniqueId());
    }

    /** 原仓库的现金卖出路径（行为不变），供卖出联动调用。 */
    private void cashSell(Player player, String code, int amount) {
        runAsync(() -> {
            try {
                Holding holding = holdingRepo.findByPlayerAndCode(player.getUniqueId(), code);
                if (holding == null) {
                    // 杠杆仓位已平掉一部分时，现金不足属于正常情况
                    runOnPlayer(player, () -> lang.send(player, "no-holding", "code", code));
                    return;
                }

                if (holding.getAmount() < amount) {
                    runOnPlayer(player, () -> lang.send(player, "not-enough-holding",
                            "hold",   String.valueOf(holding.getAmount()),
                            "amount", String.valueOf(amount)));
                    return;
                }

                api.fetch(code).thenAccept(info -> {
                    double sellPrice = round2(info.getPrice() * config.getPriceRatio());
                    double subtotal  = round2(sellPrice * amount);
                    double fee       = calcFee(subtotal);
                    double income    = round2(subtotal - fee);

                    runOnPlayer(player, () -> {
                        StockPreSellEvent preEvent = new StockPreSellEvent(player, code, amount, sellPrice, fee, income);
                        plugin.getServer().getPluginManager().callEvent(preEvent);
                        if (preEvent.isCancelled()) return;

                        LifecycleTaskManager tasks = plugin.getTaskManager();
                        LifecycleTaskManager.CriticalOperation settlement =
                                tasks != null ? tasks.beginCritical() : null;
                        if (settlement == null) return;

                        boolean deposited;
                        try {
                            deposited = economy.deposit(player, income);
                        } catch (RuntimeException e) {
                            settlement.close();
                            plugin.getLogger().severe("卖出入账异常: " + e.getMessage());
                            lang.send(player, "db-error");
                            return;
                        }
                        if (!deposited) {
                            settlement.close();
                            plugin.getLogger().severe("卖出加钱失败！玩家 " + player.getName());
                            lang.send(player, "db-error");
                            return;
                        }

                        settlement.runAsync(() -> {
                            try {
                                holdingRepo.reduceSell(player.getUniqueId(), code, amount, sellPrice);
                                runOnPlayer(player, () -> {
                                    plugin.getServer().getPluginManager().callEvent(
                                            new StockSellEvent(player, code, amount, sellPrice, fee, income));
                                    lang.send(player, "sell-success",
                                            "code",   code,
                                            "amount", String.valueOf(amount),
                                            "income", format2(income),
                                            "fee",    format2(fee));
                                });
                            } catch (Exception e) {
                                plugin.getLogger().warning("卖出写库失败: " + e.getMessage());
                                scheduleCompensation(tasks, player, () -> {
                                    if (!economy.withdraw(player, income)) {
                                        plugin.getLogger().severe("卖出回滚扣款失败！玩家 " + player.getName());
                                    }
                                });
                            }
                        }).exceptionally(ex -> {
                            plugin.getLogger().severe("卖出结算任务未执行: " + ex.getMessage());
                            scheduleCompensation(tasks, player, () -> {
                                if (!economy.withdraw(player, income)) {
                                    plugin.getLogger().severe("卖出回滚扣款失败！玩家 " + player.getName());
                                }
                            });
                            return null;
                        });
                    });
                }).exceptionally(ex -> {
                    runOnPlayer(player, () -> lang.send(player, "api-error"));
                    return null;
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("查询持仓失败: " + e.getMessage());
                runOnPlayer(player, () -> lang.send(player, "db-error"));
            }
        });
    }

    private void scheduleCompensation(LifecycleTaskManager tasks, Player player, Runnable action) {
        AtomicBoolean completed = new AtomicBoolean();
        Runnable compensation = () -> {
            if (completed.compareAndSet(false, true)) action.run();
        };
        tasks.deferUntilShutdown(compensation);
        runOnPlayer(player, () -> {
            tasks.cancelDeferredAction(compensation);
            compensation.run();
            lang.send(player, "db-error");
        });
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 外汇价格量级差异很大，保留 6 位并裁剪末尾 0。 */
    private String format6(double value) {
        if (!Double.isFinite(value)) return "0.000000";
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private String format4(double value) {
        if (!Double.isFinite(value)) return "0.0000";
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** 数量允许小数：外汇手数、加密币数量、期货手数。 */
    private String formatQty(double value) {
        if (!Double.isFinite(value)) return "0";
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private double calcFee(double subtotal) {
        double rate = config.getTransactionFeeRate();
        if (rate <= 0) return 0.0;
        return BigDecimal.valueOf(subtotal)
                .multiply(BigDecimal.valueOf(rate))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private boolean checkCooldown(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        long cdMs = config.getApiCooldownMs();
        if (last != null && now - last < cdMs) {
            long remainMs = cdMs - (now - last);
            String display = remainMs >= 1000
                    ? (remainMs / 1000) + "." + (remainMs % 1000 / 100) + " 秒"
                    : remainMs + " ms";
            lang.send(player, "api-cooldown", "seconds", display);
            return true;
        }
        cooldowns.put(uuid, now);
        return false;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!plugin.isOperational()) return List.of();
        if (args.length == 1) {
            return List.of("buy", "sell", "portfolio", "pg", "recommended", "reload",
                            "leverage", "margin", "forex", "futures", "chart", "menu", "help",
                            "positions", "simulation").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            List<String> candidates = switch (sub) {
                case "forex" -> List.of("fiat", "crypto", "list", "search", "price", "buy", "sell", "chart");
                case "futures" -> List.of("open", "close", "positions", "contracts");
                case "leverage" -> List.of("1", "5", "10", "20", "50", "100", "stock");
                case "margin" -> List.of("close", "closeall", "topup");
                case "simulation", "sim" -> List.of("list", "buy", "sell", "portfolio", "admin");
                case "help", "?" -> java.util.stream.IntStream.rangeClosed(1, HELP_PAGES)
                        .mapToObj(String::valueOf)
                        .toList();
                default -> List.of();
            };
            return candidates.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            String action = args[1].toLowerCase();
            if ("futures".equals(sub) && "open".equals(action)) {
                return completeSymbols(args[2]);
            }
            if ("forex".equals(sub)) {
                if ("list".equals(action) || "search".equals(action)) {
                    // 板块参数
                    return completeCategories(args[2]);
                }
                if ("price".equals(action) || "chart".equals(action)
                        || "buy".equals(action) || "sell".equals(action)) {
                    return completeSymbols(args[2]);
                }
                return List.of();
            }
            if ("chart".equals(sub)) {
                return completeSymbols(args[2]);
            }
            if ("simulation".equals(sub) || "sim".equals(sub)) {
                if ("admin".equals(action)) {
                    // /mstock simulation admin <动作…>
                    String prefix = args[2].toLowerCase();
                    return SIMULATION_ADMIN_ACTIONS.stream()
                            .filter(s -> s.startsWith(prefix))
                            .collect(Collectors.toList());
                }
                return completeSimulationCodes(args[2]);
            }
            return List.of();
        }
        if (args.length == 4) {
            String sub = args[0].toLowerCase();
            String action = args[1].toLowerCase();
            if ("futures".equals(sub) && "open".equals(action)) {
                return List.of("long", "short").stream()
                        .filter(s -> s.startsWith(args[3].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if ("chart".equals(sub)) {
                return completeIntervals(args[3]);
            }
            if ("forex".equals(sub)) {
                if ("chart".equals(action)) return completeIntervals(args[3]);
                // search <关键词> <板块>
                return completeCategories(args[3]);
            }
            if ("simulation".equals(sub) || "sim".equals(sub)) {
                // admin <动作> <代码>
                if ("admin".equals(action)) return completeSimulationCodes(args[3]);
                return List.of();
            }
            return List.of();
        }
        if (args.length == 5) {
            String sub = args[0].toLowerCase();
            String action = args[1].toLowerCase();
            if (("simulation".equals(sub) || "sim".equals(sub)) && "buy".equals(action)) {
                // buy <代码> <数量> [杠杆]
                return List.of("1", "2", "3", "5", "10").stream()
                        .filter(s -> s.startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return List.of();
        }
        return List.of();
    }

    /** 补全模拟盘代码（走 {@code SimulationService.allCodes()}；服务未就绪时补空）。 */
    private List<String> completeSimulationCodes(String prefix) {
        SimulationService service = plugin.getSimulationService();
        if (service == null) return List.of();
        String lower = prefix.toLowerCase();
        return service.allCodes().stream()
                .filter(code -> code.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }

    /** 补全板块标识（fiat / crypto）。 */
    private List<String> completeCategories(String prefix) {
        String lower = prefix.toLowerCase();
        return ForexCategory.all().stream()
                .map(ForexCategory::id)
                .filter(id -> id.startsWith(lower))
                .collect(Collectors.toList());
    }

    private List<String> completeIntervals(String prefix) {
        String lower = prefix.toLowerCase();
        return ForexProvider.INTERVALS.stream()
                .filter(s -> s.startsWith(lower))
                .collect(Collectors.toList());
    }

    /** 补全品种：期货用合约目录，图表 / 外汇用活跃品种（失败时退回股票代码）。 */
    private List<String> completeSymbols(String prefix) {
        String lower = prefix.toLowerCase();
        FuturesService futures = plugin.getFuturesService();
        if (futures != null) {
            List<String> symbols = futures.contractSymbols();
            if (!symbols.isEmpty()) {
                return symbols.stream()
                        .filter(s -> s.toLowerCase().startsWith(lower))
                        .limit(50)
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }
}
