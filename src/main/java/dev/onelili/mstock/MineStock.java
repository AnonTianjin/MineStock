package dev.onelili.mstock;

import dev.onelili.mstock.api.MineStockAPI;
import dev.onelili.mstock.command.DynamicCommandRegistration;
import dev.onelili.mstock.command.MStockCommand;
import dev.onelili.mstock.config.DatabaseConfig;
import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.DatabaseManager;
import dev.onelili.mstock.database.HoldingRepository;
import dev.onelili.mstock.database.TradingRepository;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.futures.FuturesService;
import dev.onelili.mstock.forex.ForexService;
import dev.onelili.mstock.leverage.LeverageEngine;
import dev.onelili.mstock.leverage.LeverageService;
import dev.onelili.mstock.leverage.PlayerLeverageManager;
import dev.onelili.mstock.lifecycle.LifecycleTaskManager;
import dev.onelili.mstock.listener.ChatInputListener;
import dev.onelili.mstock.listener.PlayerLeverageListener;
import dev.onelili.mstock.listener.PortfolioGUIListener;
import dev.onelili.mstock.listener.TradingGuiListener;
import dev.onelili.mstock.market.BiquoteForexProvider;
import dev.onelili.mstock.market.ForexProvider;
import dev.onelili.mstock.recommend.RecommendationService;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.chart.ChartService;
import dev.onelili.mstock.simulation.SimulationService;
import dev.onelili.mstock.sign.StockSignListener;
import dev.onelili.mstock.sign.StockSignService;
import dev.onelili.mstock.stockio.StockApiService;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.util.LangUtil;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MineStock extends JavaPlugin {

    private MainConfig mainConfig;
    private DatabaseConfig databaseConfig;
    private LeverageConfig leverageConfig;
    private LangUtil lang;
    private DatabaseManager dbManager;
    private HoldingRepository holdingRepo;
    private TradingRepository tradingRepo;
    private EconomyService economy;
    private volatile StockApiService api;
    private volatile RecommendationService recommendationService;
    private ChatInputSession chatSession;
    private MStockCommand commandExecutor;
    private ChatInputListener chatListener;
    private PortfolioGUIListener portfolioGUIListener;
    private PlayerLeverageListener leverageListener;
    private TradingGuiListener tradingGuiListener;
    private StockSignListener stockSignListener;
    private StockSignService stockSignService;
    private DynamicCommandRegistration stCommandRegistration;
    private LifecycleTaskManager taskManager;
    /** 模拟盘服务：{@code simulation.enabled: false} 时依然非 null（内部自行降级）。 */
    private volatile SimulationService simulationService;
    private CompatScheduler.ScheduledTaskHandle simulationTask;

    // ── 杠杆 / 外汇 / 期货 / 图表 ────────────────────────────────────────────
    private volatile ForexProvider forexProvider;
    private volatile PlayerLeverageManager leverageManager;
    private volatile LeverageService leverageService;
    private volatile ForexService forexService;
    private volatile FuturesService futuresService;
    private volatile ChartService chartService;
    private volatile dev.onelili.mstock.stock.StockLeverageService stockLeverageService;
    private CompatScheduler.ScheduledTaskHandle liquidationTask;
    private CompatScheduler.ScheduledTaskHandle futuresSettleTask;

    private final AtomicBoolean operational = new AtomicBoolean();

    @Override
    public void onEnable() {
        try {
            operational.set(false);
            getLogger().info("运行环境: " + (CompatScheduler.isFolia() ? "Folia" : "Paper/Spigot"));

            taskManager = new LifecycleTaskManager("MineStock");
            mainConfig = new MainConfig(this);
            databaseConfig = new DatabaseConfig(this);
            leverageConfig = new LeverageConfig(this);
            lang = new LangUtil(this);

            dbManager = new DatabaseManager(getLogger());
            dbManager.init(databaseConfig, getDataFolder());
            holdingRepo = new HoldingRepository(dbManager, getLogger());
            tradingRepo = new TradingRepository(dbManager, getLogger());

            economy = new EconomyService(getServer().getServicesManager(), getLogger());
            if (!economy.isReady()) {
                getLogger().severe("未找到 Vault Economy，插件将禁用");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // 模拟盘：必须在 StockApiService 之前创建 —— 它的行情源要插到 sources 的 index 0。
            // 注入 dbManager / mainConfig（不新增 getDbManager()），配置来自 simulation.* 键。
            simulationService = new SimulationService(this, dbManager, mainConfig);
            if (!simulationService.isEnabled()) {
                getLogger().info("[MineStock] 模拟盘已关闭（simulation.enabled: false）");
            }

            api = new StockApiService(mainConfig, getLogger(), simulationService.stockSource());
            recommendationService = new RecommendationService(mainConfig, api, getLogger());
            chatSession = new ChatInputSession();

            // 杠杆 / 外汇 / 期货 / 图表服务
            forexProvider = new BiquoteForexProvider(
                    mainConfig.getForexBaseUrl(), mainConfig.getForexSymbolCacheMs(), getLogger());
            leverageManager = new PlayerLeverageManager(tradingRepo, leverageConfig, getLogger());
            LeverageEngine leverageEngine = new LeverageEngine(leverageConfig);
            leverageService = new LeverageService(this, tradingRepo, leverageEngine,
                    leverageConfig, leverageManager);
            forexService = new ForexService(this, forexProvider, leverageService, tradingRepo);
            futuresService = new FuturesService(this, forexProvider, tradingRepo, mainConfig);
            chartService = new ChartService(this, api, forexProvider, mainConfig);
            stockLeverageService = new dev.onelili.mstock.stock.StockLeverageService(
                    this, leverageService);

            commandExecutor = new MStockCommand(this);
            PluginCommand cmd = getCommand("mstock");
            if (cmd != null) {
                cmd.setExecutor(commandExecutor);
                cmd.setTabCompleter(commandExecutor);
            }

            PluginCommand stockCmd = getCommand("stock");
            if (stockCmd != null) {
                stockCmd.setExecutor(commandExecutor);
                stockCmd.setTabCompleter(commandExecutor);
            }

            CommandMap commandMap = getServer().getCommandMap();
            stCommandRegistration = new DynamicCommandRegistration(commandMap);
            if (stCommandRegistration.register(
                    "minestock", new StProxyCommand(this, commandExecutor))) {
                getLogger().info("[MineStock] 已动态注册 /st 命令");
            } else {
                getLogger().info("[MineStock] /st 已被其他插件占用，跳过注册");
            }

            chatListener = new ChatInputListener(this, chatSession);
            getServer().getPluginManager().registerEvents(chatListener, this);

            portfolioGUIListener = new PortfolioGUIListener(this);
            getServer().getPluginManager().registerEvents(portfolioGUIListener, this);

            leverageListener = new PlayerLeverageListener(this);
            getServer().getPluginManager().registerEvents(leverageListener, this);

            tradingGuiListener = new TradingGuiListener(this, commandExecutor);
            getServer().getPluginManager().registerEvents(tradingGuiListener, this);

            stockSignService = new StockSignService(
                    this, api, recommendationService, mainConfig);
            stockSignListener = new StockSignListener(this, stockSignService);
            getServer().getPluginManager().registerEvents(stockSignListener, this);
            stockSignService.start();

            startTradingTasks();
            startSimulationTasks();

            MineStockAPI.init(this);
            operational.set(true);

            getLogger().info("MineStock 插件已启用");
        } catch (Exception e) {
            operational.set(false);
            getLogger().severe("MineStock 初始化失败: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        operational.set(false);
        cleanup("关闭公开 API", MineStockAPI::shutdown);

        if (stCommandRegistration != null) {
            DynamicCommandRegistration currentRegistration = stCommandRegistration;
            stCommandRegistration = null;
            cleanup("注销 /st 命令", currentRegistration::close);
        }

        if (chatListener != null) {
            ChatInputListener currentListener = chatListener;
            chatListener = null;
            cleanup("注销聊天监听器", () -> HandlerList.unregisterAll(currentListener));
        }
        if (portfolioGUIListener != null) {
            PortfolioGUIListener currentListener = portfolioGUIListener;
            portfolioGUIListener = null;
            cleanup("注销持仓GUI监听器", () -> HandlerList.unregisterAll(currentListener));
        }
        if (leverageListener != null) {
            PlayerLeverageListener currentListener = leverageListener;
            leverageListener = null;
            cleanup("注销杠杆监听器", () -> HandlerList.unregisterAll(currentListener));
        }
        if (tradingGuiListener != null) {
            TradingGuiListener currentListener = tradingGuiListener;
            tradingGuiListener = null;
            cleanup("注销交易GUI监听器", () -> HandlerList.unregisterAll(currentListener));
        }
        if (stockSignListener != null) {
            StockSignListener currentListener = stockSignListener;
            stockSignListener = null;
            cleanup("注销行情告示牌监听器", () -> HandlerList.unregisterAll(currentListener));
        }
        if (stockSignService != null) {
            StockSignService currentSigns = stockSignService;
            stockSignService = null;
            cleanup("停止行情告示牌刷新", currentSigns::close);
        }
        if (chatSession != null) cleanup("清空聊天会话", chatSession::clearAll);

        // 交易模块：先停定时任务，再落库，最后关闭行情客户端。
        CompatScheduler.ScheduledTaskHandle currentLiquidation = liquidationTask;
        liquidationTask = null;
        if (currentLiquidation != null) cleanup("停止强平巡检", currentLiquidation::cancel);

        CompatScheduler.ScheduledTaskHandle currentSettle = futuresSettleTask;
        futuresSettleTask = null;
        if (currentSettle != null) cleanup("停止期货盯市", currentSettle::cancel);

        // 模拟盘：必须在 dbManager.close() 与 taskManager.shutdown() 之前 ——
        // stop() 要冲刷最后一根未闭合的 K 线桶并落库股票状态（走 taskManager + DB）。
        CompatScheduler.ScheduledTaskHandle currentSimulation = simulationTask;
        simulationTask = null;
        if (currentSimulation != null) cleanup("停止模拟盘推进", currentSimulation::cancel);

        SimulationService currentSimulationService = simulationService;
        if (currentSimulationService != null) {
            cleanup("停止模拟盘并落库", currentSimulationService::stop);
        }

        PlayerLeverageManager currentLeverageManager = leverageManager;
        if (currentLeverageManager != null) cleanup("保存玩家杠杆", currentLeverageManager::flushAll);

        ForexProvider currentProvider = forexProvider;
        forexProvider = null;
        if (currentProvider != null) cleanup("关闭外汇行情源", currentProvider::close);

        ChartService currentChart = chartService;
        chartService = null;
        if (currentChart != null) cleanup("关闭图表服务", currentChart::close);

        RecommendationService currentRecommendations = recommendationService;
        recommendationService = null;
        if (currentRecommendations != null) {
            cleanup("关闭推荐服务", currentRecommendations::close);
        }

        StockApiService currentApi = api;
        api = null;
        if (currentApi != null) cleanup("关闭行情服务", currentApi::close);

        LifecycleTaskManager currentTasks = taskManager;
        taskManager = null;
        if (currentTasks != null) {
            cleanup("停止异步任务", () -> {
                try {
                    if (!currentTasks.shutdown(Duration.ofSeconds(10))) {
                        getLogger().severe("等待关键交易结算超时，已强制终止剩余任务");
                    }
                } finally {
                    currentTasks.runDeferredShutdownActions(error ->
                            getLogger().severe("执行交易补偿失败: " + error.getMessage()));
                }
            });
        }

        DatabaseManager currentDatabase = dbManager;
        dbManager = null;
        if (currentDatabase != null) cleanup("关闭数据库", currentDatabase::close);

        cleanup("重置静态命令", this::resetStaticCommands);

        mainConfig = null;
        databaseConfig = null;
        leverageConfig = null;
        lang = null;
        holdingRepo = null;
        tradingRepo = null;
        economy = null;
        chatSession = null;
        commandExecutor = null;
        portfolioGUIListener = null;
        leverageListener = null;
        tradingGuiListener = null;
        stockSignListener = null;
        leverageManager = null;
        leverageService = null;
        forexService = null;
        futuresService = null;
        simulationService = null;
        getLogger().info("MineStock 插件已禁用");
    }

    /** 启动强平巡检与期货盯市：都以异步定时任务运行，回调内部再切回玩家线程。 */
    private void startTradingTasks() {
        long sweepTicks = leverageConfig.getLiquidationSweepTicks();
        liquidationTask = CompatScheduler.get().runAsyncTimer(this, () -> {
            LeverageService service = leverageService;
            if (service == null || !isOperational()) return;
            try {
                service.sweepLiquidations(
                        // 必须用 priceSource() 而不是 this::symbolPrice：tradable 是
                        // SymbolPriceSource 的 default 方法，lambda 无法覆写它，
                        // 传 lambda 会让停牌的模拟股被冻结价强平（需求 #2 禁止）。
                        this.priceSource(),
                        (position, outcome) ->
                                notifyLiquidation(position.getPlayerUuid(), outcome.langKey(),
                                        outcome.placeholders()),
                        (position, notice) -> {
                            // 追保通知附带一键补交命令，补交额按拉回 80% 保证金率计算
                            notifyLiquidation(position.getPlayerUuid(), "margin-call-warning",
                                    "id", String.valueOf(position.getId()),
                                    "symbol", position.getSymbol(),
                                    "price", format2(notice.currentPrice()),
                                    "ratio", format2(notice.marginRatio() * 100.0),
                                    "liq_price", format2(notice.liquidationPrice()),
                                    "suggest", format2(notice.suggestedTopUp()));
                        });
            } catch (RuntimeException e) {
                getLogger().warning("[MineStock] 强平巡检异常: " + e.getMessage());
            }
        }, sweepTicks, sweepTicks);

        if (!mainConfig.isFuturesEnabled()) return;
        long settleTicks = mainConfig.getFuturesSettleCheckTicks();
        futuresSettleTask = CompatScheduler.get().runAsyncTimer(this, () -> {
            FuturesService service = futuresService;
            if (service == null || !isOperational()) return;
            try {
                service.settleAll((position, outcome) ->
                        notifyLiquidation(position.getPlayerUuid(), outcome.langKey(),
                                outcome.placeholders()));
            } catch (RuntimeException e) {
                getLogger().warning("[MineStock] 期货盯市异常: " + e.getMessage());
            }
        }, settleTicks, settleTicks);
    }

    /**
     * 启动模拟盘价格推进：单线程异步定时任务（与强平巡检同一套调度抽象）。
     *
     * <p>{@code bootstrapNow()} <b>同步</b>执行：它只是约 10 行的 {@code SELECT}/{@code INSERT}，
     * 而同一个 {@code onEnable} 里 {@code dbManager.init} 已经同步跑过建表 DDL。
     * 同步化可以消除"启动后数毫秒内 {@code isSupported("SIM001") == false}"的窗口。
     */
    private void startSimulationTasks() {
        SimulationService service = simulationService;
        if (service == null || !service.isEnabled()) return;
        if (leverageConfig != null && !leverageConfig.isStockLeverageEnabled()) {
            // 模拟盘杠杆区间沿用股票制度（Policy.of(SIMULATION) == STOCK），关闭时会连带降到 1x
            getLogger().info("[MineStock] 模拟盘杠杆已随股票杠杆关闭而降至 1x"
                    + "（leverage.stock.enabled: false）");
        }
        service.bootstrapNow();                       // 同步载入股票池 + 补齐到 auto-fill-count
        scheduleSimulationTicker(service);
    }

    /** 注册价格推进定时器；{@link #reconcileSimulationTask()} 复用同一段逻辑。 */
    private void scheduleSimulationTicker(SimulationService service) {
        long periodTicks = Math.max(5L, Math.round(service.tickSeconds() * 20.0));
        simulationTask = CompatScheduler.get().runAsyncTimer(this, () -> {
            SimulationService current = simulationService;
            if (current == null || !isOperational()) return;
            try {
                current.tick();
            } catch (RuntimeException e) {
                getLogger().warning("[MineStock] 模拟盘推进异常: " + e.getMessage());
            }
        }, periodTicks, periodTicks);
    }

    /** {@code /mstock reload} 后对模拟盘推进任务该做的事。 */
    enum SimulationTaskAction {
        /** 什么都不做。 */
        NONE,
        /** 重新启用：载入股票池并注册定时器（此前没有在跑）。 */
        START,
        /** 关闭：冲刷缓冲、落库、取消定时器（此前在跑）。 */
        STOP
    }

    /**
     * 决定 {@code reload} 后该如何调节模拟盘推进任务 —— 抽成纯函数以便脱离 Bukkit 单测。
     *
     * <p><b>为什么需要它</b>：{@code reload()} 会调
     * {@link SimulationService#reconfigure}，于是 {@code simulation.enabled} 与
     * {@code simulation.tick-seconds} 的改动会立刻被服务读到；但<b>定时器本身</b>只在
     * {@code onEnable} 里注册过一次。没有这段协调会出现两个方向相反的漏洞：
     *
     * <ul>
     *   <li><b>关不掉</b>：启动时启用、重载时改成 {@code false} —— 定时器仍在跑，
     *       继续推进价格、继续写 K 线，与"已关闭"的配置自相矛盾。</li>
     *   <li><b>开不了</b>：启动时关闭、重载时改成 {@code true} —— 注册表从未
     *       {@code bootstrap()}，池子是空的 ⇒ {@code supports("SIM001")} 恒 {@code false}，
     *       模拟盘要等一次完整的服务器重启才可用。</li>
     * </ul>
     *
     * <p>注意"关闭"必须是 {@code STOP} 而不是 {@code NONE}：{@code stop()} 会冲刷最后一根
     * 未闭合的 K 线桶并落库股票状态，只取消定时器会丢掉这段数据。
     *
     * @param servicePresent {@code simulationService != null}
     * @param enabled        服务当前读到的 {@code simulation.enabled}
     * @param taskRunning    {@code simulationTask != null}（定时器正在跑）
     */
    static SimulationTaskAction simulationTaskAction(boolean servicePresent, boolean enabled,
                                                     boolean taskRunning) {
        if (!servicePresent) return SimulationTaskAction.NONE;
        if (!enabled) return taskRunning ? SimulationTaskAction.STOP : SimulationTaskAction.NONE;
        return taskRunning ? SimulationTaskAction.NONE : SimulationTaskAction.START;
    }

    /**
     * {@code reload()} 收尾：让模拟盘推进任务与最新的 {@code simulation.enabled} 对齐。
     *
     * <p>幂等 —— 配置没改时判定结果是 {@code NONE}，不会重启定时器、不会打断内存价格路径。
     */
    private void reconcileSimulationTask() {
        SimulationService service = simulationService;
        CompatScheduler.ScheduledTaskHandle running = simulationTask;
        SimulationTaskAction action =
                simulationTaskAction(service != null, service != null && service.isEnabled(),
                        running != null);
        switch (action) {
            case START -> {
                getLogger().info("[MineStock] 模拟盘已通过重载启用，开始价格推进");
                service.bootstrapNow();
                scheduleSimulationTicker(service);
            }
            case STOP -> {
                simulationTask = null;
                cleanup("停止模拟盘推进", running::cancel);
                cleanup("停止模拟盘并落库", service::stop);
                getLogger().info("[MineStock] 模拟盘已通过重载关闭，价格推进已停止");
            }
            case NONE -> {
                // 配置未变：既不取消也不重启，避免无谓地打断推进节奏
            }
        }
    }

    /**
     * 统一的价格来源，按市场路由：股票走股票行情源（含 price-ratio），
     * 其余一律取 biquote 的 {@code mid}。
     */
    public java.util.concurrent.CompletableFuture<Double> symbolPrice(String symbol) {
        return symbolPrice(dev.onelili.mstock.model.MarketType.FOREX, symbol);
    }

    /**
     * 按市场路由的价格来源。
     *
     * <p><b>EQUITY 为什么不能简单"失败回落"</b>：{@code market == EQUITY} 的保证金仓位有两个来源 ——
     * <ol>
     *   <li><b>真实股票</b>：{@code /mstock buy} 的杠杆买入（A 股 / 港股 / 美股），
     *       价格必须来自股票行情源（含 {@code price-ratio}）；</li>
     *   <li><b>biquote 股票 CFD</b>：在外汇界面成交的品种，元数据 {@code type=Stock}
     *       所以也落成 EQUITY，但只有 biquote 认识它。</li>
     * </ol>
     *
     * <p>若一律"股票源失败就回落 biquote"，真实股票遇上行情源故障
     * （出厂配置里美股 API Key 为空，必然失败）时会静默拿到 biquote 的报价 ——
     * 开仓价来自股票源、平仓估值来自 biquote，价差会凭空制造盈亏并触发
     * <b>错误的强制平仓</b>或凭空出金。这是资金级风险，不能接受。
     *
     * <p><b>判据取 biquote 自己的元数据</b>：回落时向 biquote 询价，
     * 只有当 biquote 自己把该品种归类为 {@code EQUITY}（{@code type=Stock} /
     * {@code exchange=stocks}）时才采用它的价格。这正是开仓时给仓位打上
     * {@code EQUITY} 标记所用的<b>同一个判据</b>，因此两者天然一致 ——
     * 不依赖 {@code USDT} 后缀之类的命名约定（biquote 改命名就会失效）。
     *
     * <p>真实美股（如 {@code AAPL}）在 biquote 上 {@code exchange} 为 {@code null}，
     * 按元数据属 FOREX 而非 EQUITY，因此<b>不会</b>被误采用 → 失败关闭。
     */
    public java.util.concurrent.CompletableFuture<Double> symbolPrice(
            dev.onelili.mstock.model.MarketType market, String symbol) {
        // 模拟盘必须在 EQUITY 判定之前：没有这一支，market=SIMULATION 的仓位会被
        // forexMid 拿去问 biquote —— 拿不到价 ⇒ 永不刷新盈亏、永不被强平，且 /mstock margin 显示全零。
        if (market == dev.onelili.mstock.model.MarketType.SIMULATION) {
            SimulationService service = simulationService;
            // 只判 service 就绪，刻意不判 service.isEnabled()：
            // 本方法只服务"已落库的 market=SIMULATION 持仓"（估值 / 强平巡检 / 手动平仓），
            // 若在关闭态一并掐掉，既有仓位的保证金会永久锁死 —— 平不掉、也永不强平
            // （LeverageService.fetchPrice 遇失败返回 null，sweepLiquidations 与 closeAll 都 continue）。
            // 这正是本项目在真实股票 CFD 上踩过的同款缺陷，不能重犯。
            // 开新仓的拦截不在这里：supports()/fetch() + 指令层路由器 + tradeGate 三重挡在前面。
            if (service == null) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("模拟盘未就绪"));
            }
            // marketPrice 已乘 price-ratio，与下面的 EQUITY 分支口径完全一致
            return service.marketPrice(symbol);
        }
        if (market != dev.onelili.mstock.model.MarketType.EQUITY) {
            return forexMid(symbol);
        }
        StockApiService stockApi = api;
        if (stockApi == null) {
            // 插件正在重载 / 已关闭：真实股票此刻没有可用行情源，失败关闭。
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("股票行情源未就绪"));
        }
        double ratio = mainConfig != null ? mainConfig.getPriceRatio() : 1.0;
        ForexProvider provider = forexProvider;
        return routeEquity(
                () -> stockApi.fetch(symbol).thenApply(info -> info.getPrice() * ratio),
                provider == null ? null : () -> provider.getPrice(symbol));
    }

    /**
     * EQUITY 价格路由：股票源优先；取不到价时<b>仅在 biquote 自己认定它是股票 CFD</b> 时回落。
     *
     * <p>抽成静态方法是为了能脱离 {@code JavaPlugin} 单测各条分支 ——
     * 这条路由决定持仓估值与强平价格，必须被真实覆盖而不是只测一个判据函数。
     *
     * @param stockFetch 股票源取价（返回已含 {@code price-ratio} 的价格）
     * @param forexQuote biquote 报价查询；{@code null} 表示该源不可用
     */
    static java.util.concurrent.CompletableFuture<Double> routeEquity(
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<Double>> stockFetch,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<
                    dev.onelili.mstock.model.ForexQuote>> forexQuote) {
        if (stockFetch == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("股票行情源未就绪"));
        }
        java.util.concurrent.CompletableFuture<Double> stock = supply(stockFetch);
        return stock
                .exceptionally(error -> null)
                .thenCompose(price -> {
                    if (isUsablePrice(price)) {
                        return java.util.concurrent.CompletableFuture.completedFuture(price);
                    }
                    // 股票源取不到价：只有 biquote 自己也把它归为股票时才采用它的报价。
                    // 否则失败关闭 —— 绝不让真实股票拿到另一套数据源的价格。
                    if (forexQuote == null) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("无法获取该品种的价格"));
                    }
                    return supply(forexQuote)
                            .exceptionally(error -> null)
                            .thenCompose(quote -> {
                                if (quote == null
                                        || quote.getMarket()
                                                != dev.onelili.mstock.model.MarketType.EQUITY
                                        || !isUsablePrice(quote.getMid())) {
                                    return java.util.concurrent.CompletableFuture.failedFuture(
                                            new IllegalStateException("无法获取该品种的价格"));
                                }
                                return java.util.concurrent.CompletableFuture
                                        .completedFuture(quote.getMid());
                            });
                });
    }

    /** 执行取价动作，null / 抛异常都归一成失败的 future，避免把 null 往下传。 */
    private static <T> java.util.concurrent.CompletableFuture<T> supply(
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> action) {
        try {
            java.util.concurrent.CompletableFuture<T> result = action.get();
            if (result != null) return result;
        } catch (RuntimeException e) {
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("行情源未就绪"));
    }

    /** 价格是否可用：非 null、有限、且严格为正。 */
    static boolean isUsablePrice(Double price) {
        return price != null && Double.isFinite(price) && price > 0;
    }

    /**
     * biquote 取 {@code mid} 的兜底价格源。
     *
     * <p>价格一律取 {@code mid}：外汇 / CFD 数据源的 {@code last} 与 {@code volume}
     * 恒为 0，不可使用。
     */
    private java.util.concurrent.CompletableFuture<Double> forexMid(String symbol) {
        ForexProvider provider = forexProvider;
        if (provider == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("外汇行情源未就绪"));
        }
        return provider.getPrice(symbol).thenApply(dev.onelili.mstock.model.ForexQuote::getMid);
    }

    /**
     * 复合价格源：强平巡检与持仓总览共用，股票与外汇自动路由。
     *
     * <p><b>为什么是具名实现而不是 {@code this::symbolPrice}</b>：{@code tradable} 是
     * {@link dev.onelili.mstock.leverage.SymbolPriceSource} 的 {@code default} 方法，
     * <b>lambda 无法覆写 default 方法</b>，而强平巡检必须跳过停牌股。
     *
     * <p><b>每次调用返回新实例，且刻意不缓存进字段</b>：实例本身无状态，字段都在
     * 读取时解析，因此 {@code reload()} 换掉 service 后不会拿到过期引用。
     */
    public dev.onelili.mstock.leverage.SymbolPriceSource priceSource() {
        return new dev.onelili.mstock.leverage.SymbolPriceSource() {
            @Override
            public java.util.concurrent.CompletableFuture<Double> price(
                    dev.onelili.mstock.model.MarketType market, String symbol) {
                return MineStock.this.symbolPrice(market, symbol);
            }

            @Override
            public boolean tradable(dev.onelili.mstock.model.MarketType market, String symbol) {
                if (market != dev.onelili.mstock.model.MarketType.SIMULATION) return true;
                SimulationService service = simulationService;
                // service 未就绪时按"可交易"处理：不让界面与巡检因启动竞态静默停摆
                return service == null || service.isTradable(symbol);
            }
        };
    }

    /** 强平 / 到期通知：必须切回玩家所在线程再发消息。 */
    private void notifyLiquidation(java.util.UUID playerUuid, String langKey,
                                   String... placeholders) {
        org.bukkit.entity.Player player = getServer().getPlayer(playerUuid);
        if (player == null || !player.isOnline()) return;
        LangUtil currentLang = lang;
        if (currentLang == null) return;
        CompatScheduler.get().runOnEntity(this, player, () -> {
            if (player.isOnline()) currentLang.send(player, langKey, placeholders);
        });
    }

    private static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return java.math.BigDecimal.valueOf(value)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private void resetStaticCommands() {
        PluginCommand mstock = getCommand("mstock");
        if (mstock != null) {
            mstock.setExecutor(null);
            mstock.setTabCompleter(null);
        }
        PluginCommand stock = getCommand("stock");
        if (stock != null) {
            stock.setExecutor(null);
            stock.setTabCompleter(null);
        }
    }

    private void cleanup(String action, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            getLogger().warning(action + "失败: " + e.getMessage());
        }
    }

    public MainConfig getMainConfig() { return mainConfig; }
    public LangUtil getLang() { return lang; }
    public HoldingRepository getHoldingRepo() { return holdingRepo; }
    public TradingRepository getTradingRepo() { return tradingRepo; }
    public EconomyService getEconomy() { return economy; }
    public StockApiService getApi() { return api; }
    public RecommendationService getRecommendationService() { return recommendationService; }
    public StockSignService getStockSignService() { return stockSignService; }
    public ChatInputSession getChatSession() { return chatSession; }
    public MStockCommand getCommandExecutor() { return commandExecutor; }
    public LifecycleTaskManager getTaskManager() { return taskManager; }
    public LeverageConfig getLeverageConfig() { return leverageConfig; }
    public PlayerLeverageManager getLeverageManager() { return leverageManager; }
    public LeverageService getLeverageService() { return leverageService; }
    public ForexService getForexService() { return forexService; }
    public FuturesService getFuturesService() { return futuresService; }
    public ChartService getChartService() { return chartService; }
    public dev.onelili.mstock.stock.StockLeverageService getStockLeverageService() {
        return stockLeverageService;
    }
    /** 模拟盘服务；插件重载 / 已关闭期间可能为 {@code null}。 */
    public SimulationService getSimulationService() { return simulationService; }
    public ForexProvider getForexProvider() { return forexProvider; }
    public boolean isOperational() { return operational.get() && isEnabled(); }

    public synchronized void reload() {
        if (!isOperational()) throw new IllegalStateException("MineStock is not active");

        reloadConfig();
        MainConfig newConfig = new MainConfig(this);
        LangUtil newLang = new LangUtil(this);
        StockApiService newApi = new StockApiService(newConfig, getLogger(),
                simulationService == null ? null : simulationService.stockSource());
        RecommendationService newRecommendations =
                new RecommendationService(newConfig, newApi, getLogger());
        BiquoteForexProvider newProvider = new BiquoteForexProvider(
                newConfig.getForexBaseUrl(), newConfig.getForexSymbolCacheMs(), getLogger());
        ChartService newChart = new ChartService(this, newApi, newProvider, newConfig);

        StockApiService oldApi = api;
        RecommendationService oldRecommendations = recommendationService;
        ForexProvider oldProvider = forexProvider;
        ChartService oldChart = chartService;
        mainConfig = newConfig;
        lang = newLang;
        api = newApi;
        recommendationService = newRecommendations;
        forexProvider = newProvider;
        chartService = newChart;
        if (futuresService != null) {
            futuresService.reconfigure(newProvider, newConfig);
        }
        // 外汇服务同样持有 provider 引用。不换的话重载后它还用着 :741 即将被 close 的旧实例，
        // 之后每次报价都抛 IllegalStateException: biquote 行情客户端已关闭。
        if (forexService != null) {
            forexService.reconfigure(newProvider);
        }
        // 不调用则 /mstock reload 后 simulation.tick-seconds 等改动不生效（新开仓仍按旧配置）
        if (simulationService != null) {
            simulationService.reconfigure(newConfig);
            // reconfigure 只换配置；推进任务的注册/注销必须在这里跟进，否则
            // "启动时开、重载时关" 会继续跑，"启动时关、重载时开" 要等重启才可用。
            reconcileSimulationTask();
        }
        if (commandExecutor != null) {
            commandExecutor.refresh(newApi, newRecommendations, newConfig, newLang);
        }
        if (stockSignService != null) {
            stockSignService.reconfigure(newApi, newRecommendations, newConfig);
        }
        if (oldChart != null) oldChart.close();
        if (oldRecommendations != null) oldRecommendations.close();
        if (oldApi != null) oldApi.close();
        if (oldProvider != null) oldProvider.close();
        getLogger().info("MineStock 配置已重载");
    }

    private static final class StProxyCommand extends org.bukkit.command.Command
            implements PluginIdentifiableCommand {

        private final MineStock plugin;
        private final MStockCommand delegate;

        StProxyCommand(MineStock plugin, MStockCommand delegate) {
            super("st");
            this.plugin = plugin;
            this.delegate = delegate;
            setDescription("股票市场命令");
            setUsage("/st [recommended|<代码>|buy|sell|portfolio|positions|pg|chart]");
            setPermission("minestock.use");
        }

        @Override
        public boolean execute(
                @org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                @org.jetbrains.annotations.NotNull String label,
                @org.jetbrains.annotations.NotNull String[] args) {
            if (!testPermission(sender)) return true;
            return delegate.onCommand(sender, this, label, args);
        }

        @Override
        public @org.jetbrains.annotations.NotNull java.util.List<String> tabComplete(
                @org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                @org.jetbrains.annotations.NotNull String alias,
                @org.jetbrains.annotations.NotNull String[] args) {
            if (!testPermissionSilent(sender)) return java.util.List.of();
            java.util.List<String> result = delegate.onTabComplete(sender, this, alias, args);
            return result != null ? result : java.util.List.of();
        }

        @Override
        public @org.jetbrains.annotations.NotNull Plugin getPlugin() {
            return plugin;
        }
    }
}
