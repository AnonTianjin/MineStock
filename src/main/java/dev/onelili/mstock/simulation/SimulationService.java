package dev.onelili.mstock.simulation;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.DatabaseManager;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.leverage.LeverageService;
import dev.onelili.mstock.leverage.SymbolPriceSource;
import dev.onelili.mstock.leverage.TradeOutcome;
import dev.onelili.mstock.lifecycle.LifecycleTaskManager;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 模拟盘编排中枢：tick 推进、买卖、管理入口、K 线查询。
 *
 * <p><b>价格口径（关键）</b>：{@link #rawPrice} 不含 price-ratio（与各 {@code StockSource} 一致）；
 * {@link #marketPrice} <b>含</b> price-ratio，与 {@code MineStock.symbolPrice} 的 EQUITY 分支
 * 完全一致。开仓与估值必须共用同一口径，否则会凭空制造盈亏甚至错误强平。
 *
 * <p><b>资金级不变量</b>：模拟现金持仓只写 {@code simulation_holdings}；模拟杠杆仓位
 * 一律以 {@link MarketType#SIMULATION} 交给 {@code LeverageService}（落
 * {@code margin_positions.market = 'SIMULATION'}）。本类<b>任何路径</b>都不会写真实
 * {@code holdings} 表，也不会落 {@code market = EQUITY / FOREX} 的保证金仓。
 *
 * <p><b>线程模型</b>：{@link #tick()} 由 {@code CompatScheduler.runAsyncTimer} 的单线程驱动；
 * 管理员写操作与所有 SQL 都经 {@code LifecycleTaskManager}（常规池 2 线程，**不是**单线程），
 * 因此与 tick 的并发靠"字段 {@code volatile} + 每只股 {@code synchronized (stock)}"保护。
 */
public final class SimulationService {

    /** 管理员价格类参数的合法下界（与 {@code SimulationStock#setBounds} 同口径）。 */
    private static final double MIN_PRICE = 0.01;

    /** 管理员价格类参数的合法上界。 */
    private static final double MAX_PRICE = 1.0e9;

    /** 股票状态落库的默认周期（毫秒）。 */
    private static final long PERSIST_INTERVAL_MS = 5000L;

    /** 单次 tick 最多推进的秒数（服务器卡顿时不补算、不跳跃）。 */
    private static final double MAX_DT_SECONDS = 60.0;

    /** 相关系数绝对值上界（与 {@link SimulationLink} 同值，避免 {@code sqrt(1-rho^2)} 退化）。 */
    private static final double MAX_ABS_CORRELATION = 0.999;

    private final MineStock plugin;
    private final DatabaseManager db;
    private final Logger logger;

    /** {@code /mstock reload} 会换新实例，故非 final（见 {@link #reconfigure}）。 */
    private volatile MainConfig mainConfig;

    private final SimulationRepository repository;

    /** 同上：重载后必须重建，否则 {@code tick-seconds} 等键读到的是旧文件对象。 */
    private volatile SimulationConfig config;

    private volatile SimulationStockRegistry registry;

    private final SimulationStockSource stockSource;

    /** 每只股一个 1 分钟 K 线环形缓冲；环形缓冲<b>不</b>持久化，1 分钟粒度的历史靠 DB。 */
    private final Map<String, SimulationKLineBuffer> buffers = new ConcurrentHashMap<>();

    /** 联动 / 定时操作的读缓存：每 tick 都查库代价过高，改为写操作后刷新。 */
    private volatile List<SimulationLink> links = List.of();
    private volatile List<SimulationSchedule> schedules = List.of();

    /**
     * 定时冲击的"本次运行已触发"幂等标记（{@code id@日期}）。
     *
     * <p>库里已有 {@code last_fired_date} 做持久化幂等，但这层内存标记挡住的是
     * "写库失败"这一条路径：{@code markScheduleFired} 抛异常时库里的日期没被刷新，
     * 若不拦住，同一分钟内每个 tick 都会再乘一次 {@code changePercent}
     * （{@code 1.05^60 ≈ 18} 倍，资金级事故）。跨日时清空。
     */
    private final Set<String> firedGuards = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 联动 / 定时的共同因子随机源（独立于个股随机源，保证"同种子 ⇒ 同路径"）。 */
    private final Random groupRandom;

    /** 重入保护：调度器已是单线程，仍防住"上一 tick 未结束又进来"。 */
    private final AtomicBoolean ticking = new AtomicBoolean();

    /** 上次推进时刻；{@code dt} 用它做真实流逝时间校正。 */
    private volatile long lastTickAt;

    /** 上次股票状态落库时刻。 */
    private volatile long lastPersistAt;

    /** 上次跨日滚动的日期；{@code null} 表示尚未滚动过。 */
    private volatile LocalDate lastRollDate;

    /** 是否有待落库的股票状态（任一管理员写操作后置 true）。 */
    private volatile boolean dirtyStocks;

    /**
     * 采用<b>注入式</b>构造器：显式接收 {@code db} 与 {@code mainConfig}，
     * 消除对 {@code MineStock} 全局状态的依赖（也便于测试）。
     */
    public SimulationService(MineStock plugin, DatabaseManager db, MainConfig mainConfig) {
        this.plugin = plugin;
        this.db = db;
        this.mainConfig = mainConfig;
        // plugin == null（离线构造）时给一个非空 Logger：注册表 / 仓储的 info/warning
        // 都直接调用 logger，null 会让 bootstrap 抛 NPE。
        this.logger = plugin == null ? Logger.getLogger("MineStock-Simulation") : plugin.getLogger();
        this.config = new SimulationConfig(plugin == null ? null : plugin.getConfig());
        this.repository = new SimulationRepository(db, this.logger);
        this.registry = new SimulationStockRegistry(repository, config, this.logger);
        this.stockSource = new SimulationStockSource(this);
        this.groupRandom = new Random(config.seedBase());
        long now = System.currentTimeMillis();
        this.lastTickAt = now;
        this.lastPersistAt = now;
        this.lastRollDate = LocalDate.now();
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    /** {@code simulation.enabled}。 */
    public boolean isEnabled() {
        return config.enabled();
    }

    public double tickSeconds() {
        return config.tickSeconds();
    }

    /**
     * 同步载入 / 补齐股票池（{@code onEnable} 内调用，避免"启动后数毫秒内不可用"的窗口）。
     *
     * <p>关闭态直接返回：不 bootstrap 时股票池为空，于是
     * {@link SimulationStockSource#supports} 对所有 {@code SIMxxx} 恒为 false。
     *
     * <p>写库失败<b>降级为空池</b>并把 {@code lastTickAt} 设为当前时刻，避免启动瞬间
     * 因为一个巨大的 {@code dt} 把价格一次推爆。
     */
    public void bootstrapNow() {
        long now = System.currentTimeMillis();
        this.lastTickAt = now;
        this.lastPersistAt = now;
        // 启动当天不做跨日滚动：库里的 prev_close / current_price 是停机前的真实状态，
        // 立刻滚动会把当天的涨跌幅清零。跨日留给真正的自然日切换。
        this.lastRollDate = LocalDate.now();
        if (!isEnabled()) return;
        try {
            registry.bootstrap();
        } catch (SQLException e) {
            registry.clear();
            log(Level.WARNING, "[MineStock] 模拟股票池载入失败，降级为空池: " + e.getMessage());
        }
        reloadLinks();
        reloadSchedules();
    }

    /** 冲刷 K 线桶 + 落库股票状态 + 释放缓冲。<b>必须早于 {@code dbManager.close()}。</b> */
    public void stop() {
        List<SimulationStock> stocks = registry.all();
        if (!hasDatabase()) {
            buffers.clear();
            return;
        }
        for (SimulationStock stock : stocks) {
            SimulationKLineBuffer buffer = buffers.get(stock.getCode());
            if (buffer == null) continue;
            try {
                // snapshot() 含"当前未闭合"的那一根：停机后它不会再有下一 tick 来闭合，
                // 直接按现值落库，避免丢掉最后一分钟的成交。
                List<SimulationBar> pending = buffer.snapshot();
                if (!pending.isEmpty()) {
                    repository.upsertKLines(stock.getCode(), pending);
                }
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘停机冲刷 K 线失败 "
                        + stock.getCode() + ": " + e.getMessage());
            }
            buffer.clear();
        }
        buffers.clear();
        try {
            repository.upsertStocks(stocks);
        } catch (SQLException e) {
            log(Level.WARNING, "[MineStock] 模拟盘停机落库股票状态失败: " + e.getMessage());
        }
    }

    /** {@code /mstock reload} 后重读配置。 */
    public void reconfigure(MainConfig config) {
        this.mainConfig = config;
        // Bukkit 的 reloadConfig() 会换掉 JavaPlugin 内部的 FileConfiguration 实例，
        // 因此必须重新取 plugin.getConfig()，否则 SimulationConfig 里的旧引用读到的仍是旧值。
        applyConfig(new SimulationConfig(plugin == null ? null : plugin.getConfig()));
    }

    /**
     * 换配置并重建注册表：逐只搬迁既有股票，<b>刻意不清空</b>（内存里的价格路径不能被重载打断）。
     *
     * <p>包级可见：让同包离线单测能<b>精确复现</b> {@code /mstock reload} 的语义
     * （新配置 + 注册表已迁移 + 股票仍在池中），从而驱动真实判据而不是复刻一份副本。
     * 生产入口只有 {@link #reconfigure}。
     */
    void applyConfig(SimulationConfig next) {
        SimulationStockRegistry rebuilt =
                new SimulationStockRegistry(repository, next, logger);
        for (SimulationStock stock : registry.all()) {
            rebuilt.put(stock);
        }
        this.config = next;
        this.registry = rebuilt;
    }

    // ── 对内（MineStock 接线用）────────────────────────────────────────────

    /** 单例行情源，供 {@code StockApiService} 插到 index 0。 */
    public SimulationStockSource stockSource() {
        return stockSource;
    }

    /** 正则命中 <b>且</b> 已注册。 */
    public boolean isSimulationCode(String code) {
        String normalized = SimulationStockRegistry.normalize(code);
        return normalized != null
                && SimulationStockSource.CODE.matcher(normalized).matches()
                && registry.contains(normalized);
    }

    /** {@code tradable && !suspended}；未注册 → {@code false}（不抛异常）。 */
    public boolean isTradable(String code) {
        return tradable(registry, code);
    }

    /**
     * 可交易判据（包级可见：让离线单测直接驱动<b>真实</b>判据，而不是复刻一份可能漂移的副本）。
     *
     * <p>{@code suspended} 与 {@code tradable} 语义不同：前者是"停牌"（价格也冻结、
     * 强平巡检跳过），后者是"禁售"（价格照常推进，只禁止买卖）。两者取与。
     * {@code paused} <b>不</b>影响可交易性（暂停推进 ≠ 不可交易）。
     */
    static boolean tradable(SimulationStock stock) {
        return stock != null && stock.isTradable() && !stock.isSuspended();
    }

    /** 未注册代码一律不可交易（不抛异常，也不产生 NPE）。 */
    static boolean tradable(SimulationStockRegistry stockRegistry, String code) {
        if (stockRegistry == null) return false;
        return tradable(stockRegistry.find(code).orElse(null));
    }

    /** 包级视图：供同包测试把股票塞进池子 / 直接驱动真实判据（不扩大 public 面）。 */
    SimulationStockRegistry registryView() {
        return registry;
    }

    /** 不含 price-ratio 的原始价（供 {@link SimulationStockSource#fetch} 使用）。 */
    public CompletableFuture<Double> rawPrice(String code) {
        SimulationStock stock = registry.find(code).orElse(null);
        if (stock == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Unsupported simulation code: " + code));
        }
        return CompletableFuture.completedFuture(
                SimulationPriceEngine.safePrice(stock.getCurrentPrice()));
    }

    /** 含 price-ratio 的估值价（供 {@code MineStock.symbolPrice} 的 SIMULATION 分支使用）。 */
    public CompletableFuture<Double> marketPrice(String code) {
        SimulationStock stock = registry.find(code).orElse(null);
        if (stock == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Unsupported simulation code: " + code));
        }
        return CompletableFuture.completedFuture(marketPriceOf(stock));
    }

    /**
     * K 线点。
     *
     * <p>数据源 = DB 历史（窗口内）+ 内存缓冲（含未闭合的当前分钟，同桶以缓冲为准），
     * 最后交给 {@link SimulationKLineBuffer#withFallback} 补齐到
     * {@code ChatChartRenderer.MIN_BARS} 根，避免新启用时图表直接报 {@code chart-error}。
     */
    public CompletableFuture<List<KLinePoint>> kline(String code, int days) {
        SimulationStock stock = registry.find(code).orElse(null);
        if (stock == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Unsupported simulation code: " + code));
        }
        int window = Math.max(1, Math.min(3650, days));
        return supplyAsync(() -> klineOf(stock, window));
    }

    /**
     * 带周期的 K 线点。
     *
     * <p>{@code interval} 决定<b>聚合粒度</b>（{@code 1m 5m 15m 30m 1h 4h 1d}），
     * {@code limit} 决定<b>最多几根</b>。旧签名 {@link #kline(String, int)} 的 {@code days}
     * 同时兼任这两个语义，是"要分钟级却画成日线"的根因；本重载把它拆开。
     */
    public CompletableFuture<List<KLinePoint>> kline(String code, String interval, int limit) {
        SimulationStock stock = registry.find(code).orElse(null);
        if (stock == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Unsupported simulation code: " + code));
        }
        // 下界 2 与 ChatChartRenderer.MIN_BARS 对齐：只要 1 根必然渲染失败并回落 biquote
        int capped = Math.max(2, Math.min(3650, limit));
        return supplyAsync(() -> klineOf(stock, interval, capped));
    }

    /** 全部已注册代码（Tab 补全用）。 */
    public List<String> allCodes() {
        List<SimulationStock> stocks = registry.all();
        List<String> codes = new ArrayList<>(stocks.size());
        for (SimulationStock stock : stocks) {
            codes.add(stock.getCode());
        }
        return List.copyOf(codes);
    }

    public List<SimulationStock> stocks() {
        return registry.all();
    }

    public Optional<SimulationStock> findStock(String code) {
        return registry.find(code);
    }

    // ── tick（由 CompatScheduler.runAsyncTimer 单线程驱动）──────────────────

    /**
     * 推进一档；内部用 {@code AtomicBoolean} 防重入。
     *
     * <p>步骤顺序 ⓪ 跨日滚动 → ① 定时操作 → ② 联动共同因子 → ③ 逐股推进 → ④ 落库
     * <b>顺序不得调换</b>。
     */
    public void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try {
            advanceAll();
        } catch (RuntimeException e) {
            // 周期任务抛出的异常会被调度器当成任务失败（重复任务可能就此被取消 ⇒ 价格永久停跳），
            // 因此这里兜住并只记日志：单次失败不该让整盘停摆。
            log(Level.WARNING, "[MineStock] 模拟盘推进异常: " + e.getMessage());
        } finally {
            ticking.set(false);
        }
    }

    private void advanceAll() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTickAt;
        // 首次 tick：bootstrapNow() 刚把 lastTickAt 设为当前时刻，间隔 < 1ms ⇒ 跳过本次推进
        // （不更新 lastTickAt，让下一次 tick 拿到真实的流逝时间）
        if (elapsed < 1L) return;
        // dt 用真实流逝时间校正：服务器卡顿只会拉长单次推进（上限 60s），不补算、不造成跳跃
        double dt = Math.min(MAX_DT_SECONDS, elapsed / 1000.0);
        lastTickAt = now;

        LocalDate today = LocalDate.now();

        // ⓪ 跨日滚动（一次性）
        if (!today.equals(lastRollDate)) {
            rollOver(today, now);
        }

        // ① 定时操作（先于价格推进，让冲击当 tick 生效）
        fireSchedules(today);

        // 本 tick 的股票快照：⓪②③④ 看到同一批股票，管理员并发增删不会让本次 tick 半途改变集合
        List<SimulationStock> stocks = registry.all();

        // ② 联动：按 sector 抽共同因子（只在本 tick 至少有一只活跃股时抽）
        Map<String, Double> groupZ = new HashMap<>();
        for (SimulationStock stock : stocks) {
            if (isActive(stock)) {
                groupZ.computeIfAbsent(stock.getSector(), sector -> nextGroupFactor());
            }
        }

        // ③ 逐股推进
        List<SimulationLink> currentLinks = links;
        for (SimulationStock stock : stocks) {
            if (shouldSkipPrice(stock)) continue;               // 停牌 / 暂停：不改任何价格
            synchronized (stock) {
                advanceOne(stock, linkRhoFor(stock.getCode(), currentLinks),
                        groupZ.getOrDefault(stock.getSector(), 0.0), dt, now);
            }
            // K 线记录 currentPrice（含人工覆盖、涨跌停与上下限之后的值）
            bufferOf(stock).record(now, stock.getCurrentPrice());
        }

        // ④ 分钟翻页 → 闭合 bar 落库 + 状态落库（每 tick 至多一次）
        for (SimulationStock stock : stocks) {
            List<SimulationBar> closed = bufferOf(stock).drainClosed();
            if (!closed.isEmpty()) {
                try {
                    repository.upsertKLines(stock.getCode(), closed);
                } catch (SQLException e) {
                    log(Level.WARNING, "[MineStock] 模拟盘 K 线落库失败 " + stock.getCode()
                            + ": " + e.getMessage());
                }
            }
        }
        if (dirtyStocks || now - lastPersistAt >= PERSIST_INTERVAL_MS) {
            try {
                // 用 registry.all() 而不是本 tick 的快照：快照里可能有已被管理员删除的股票，
                // upsert 会把它的行重新插回库里。
                repository.upsertStocks(registry.all());
                lastPersistAt = now;
                dirtyStocks = false;
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘状态落库失败: " + e.getMessage());
            }
        }
    }

    /**
     * ⓪ 跨日滚动：昨收跟随最新价；锚仅在"仍等于旧昨收"时才跟随（避免覆盖管理员手设的锚）。
     *
     * <p>停牌股不滚动昨收（价格已冻结，滚动会让涨跌幅凭空归零）。
     */
    private void rollOver(LocalDate today, long now) {
        for (SimulationStock stock : registry.all()) {
            synchronized (stock) {
                double oldPrevClose = stock.getPrevClose();
                if (!stock.isSuspended()) {
                    stock.setPrevClose(stock.getCurrentPrice());
                }
                if (Math.abs(stock.getAnchor() - oldPrevClose) < 1e-9) {
                    stock.setAnchor(stock.getPrevClose());
                }
                stock.setUpdatedAt(now);
            }
        }
        lastRollDate = today;
        dirtyStocks = true;
        firedGuards.clear();                 // 跨日：定时的当日幂等标记作废
    }

    /**
     * ① 定时价格冲击。
     *
     * <p>三重门槛：{@code enabled} → 今日未触发 → 当前分钟精确匹配。只匹配当前分钟，
     * 不补算离线期间错过的时刻。冲击只乘 {@code autoPrice}，
     * 之后仍会走 ③ 的 {@code applyBand / applyHardBounds}，因此不会突破涨跌停。
     */
    private void fireSchedules(LocalDate today) {
        List<SimulationSchedule> current = schedules;
        if (current.isEmpty()) return;
        String todayText = today.toString();
        LocalTime nowTime = LocalTime.now();
        boolean fired = false;
        for (SimulationSchedule schedule : current) {
            if (!schedule.enabled()) continue;
            if (todayText.equals(schedule.lastFiredDate())) continue;            // 今日已触发
            if (firedGuards.contains(schedule.id() + "@" + todayText)) continue; // 库写失败时的内存兜底
            if (!SimulationSchedule.matches(schedule.timeOfDay(), nowTime)) continue;
            SimulationStock stock = registry.find(schedule.code()).orElse(null);
            if (stock == null) continue;                                        // 指向已删除的代码
            synchronized (stock) {
                stock.setAutoPrice(SimulationPriceEngine.safePrice(
                        stock.getAutoPrice() * (1 + schedule.changePercent() / 100.0)));
            }
            firedGuards.add(schedule.id() + "@" + todayText);
            try {
                // repeatDaily == false ⇒ disable = true，一次性冲击触发后自动禁用
                repository.markScheduleFired(schedule.id(), todayText, !schedule.repeatDaily());
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘定时落库失败 #" + schedule.id()
                        + ": " + e.getMessage());
            }
            fired = true;
        }
        // 缓存里的 record 是不可变值，落库后必须重读，否则同一分钟内会重复触发
        if (fired) {
            reloadSchedules();
        }
    }

    /** 本 tick 该股是否活跃（停牌 / 暂停都不推进价格）。包级可见：供离线单测驱动真实判据。 */
    static boolean isActive(SimulationStock stock) {
        return !stock.isSuspended() && !stock.isPaused();
    }

    /** {@code tick()} ③ 的 skip 判据：停牌或暂停都跳过价格推进（两者都不改任何价格）。 */
    static boolean shouldSkipPrice(SimulationStock stock) {
        return !isActive(stock);
    }

    /**
     * ③ 的单股推进（调用方须持有 {@code synchronized (stock)}）。
     *
     * <p>包级可见：让离线单测直接驱动<b>真实</b>价格管线，而不是复刻一份可能漂移的副本。
     */
    static void advanceOne(SimulationStock stock, double rho, double groupZ, double dtSeconds,
                           long now) {
        double zRaw = stock.random().nextGaussian();
        double z = SimulationPriceEngine.correlatedZ(rho, groupZ, zRaw);
        double u1 = stock.random().nextDouble();
        double u2 = stock.random().nextDouble();

        // 过期即自动失效；到期后由下一次 dirtyStocks 落库把 slope_per_ms 归零
        double slope = now < stock.getSlopeUntil() ? stock.getSlopePerMs() : 0.0;
        SimulationPriceEngine.Params params = new SimulationPriceEngine.Params(
                stock.getMu(), stock.getSigma(), stock.getTheta(), stock.getAnchor(),
                stock.getJumpLambda(), stock.getJumpMagnitude(), stock.getAmplitude(),
                slope, dtSeconds);
        stock.setAutoPrice(SimulationPriceEngine.advance(stock.getAutoPrice(), params, z, u1, u2));

        // 价格管线顺序不可调换：指令解析 → 涨跌停带 → 绝对上下限 → 最终兜底
        double base = stock.directive().resolve(stock.getAutoPrice(), stock.getPrevClose());
        double banded = SimulationPriceEngine.applyBand(base, stock.lowerLimit(),
                stock.upperLimit(), stock.getBoundary());
        double bounded = SimulationPriceEngine.applyHardBounds(banded,
                stock.getMinPrice(), stock.getMaxPrice());
        stock.setCurrentPrice(SimulationPriceEngine.safePrice(bounded));
        stock.setUpdatedAt(now);
    }

    /** 共同因子：默认权重 {@code 1.0} 时与"裸 {@code nextGaussian()} 逐字等价"。 */
    private double nextGroupFactor() {
        double weight = config.linkGroupWeight();
        double z = groupRandom.nextGaussian();
        return Double.isFinite(weight) ? z * weight : z;
    }

    /**
     * 该股参与的全部联动 rho 之和，夹取到 {@code [-0.999, 0.999]}；无联动 → {@code 0.0}。
     *
     * <p>每条 rho 已由 {@link SimulationLink#of} 归一化并夹取，但"多边求和"仍可能越界，
     * 故这里再夹一次（{@code correlatedZ} 内部还有第三次夹取，属纵深防御）。
     *
     * <p>包级可见：供离线单测驱动真实的求和 / 夹取逻辑。
     */
    static double linkRhoFor(String code, List<SimulationLink> links) {
        if (links.isEmpty() || code == null) return 0.0;
        double rho = 0.0;
        for (SimulationLink link : links) {
            if (code.equals(link.codeA()) || code.equals(link.codeB())) {
                rho += link.correlation();
            }
        }
        if (!Double.isFinite(rho)) return 0.0;
        return Math.max(-MAX_ABS_CORRELATION, Math.min(MAX_ABS_CORRELATION, rho));
    }

    // ── 面向玩家 ────────────────────────────────────────────────────────────

    /**
     * 买入时按六条分支依次判定。
     *
     * <p>{@code leverage <= 1} 走模拟现金持仓（{@code simulation_holdings}）；
     * {@code leverage > 1} 交给保证金引擎，市场恒为 {@link MarketType#SIMULATION}。
     */
    public CompletableFuture<TradeOutcome> buy(UUID playerUuid, String code, int quantity,
                                               int leverage) {
        TradeGate gate = tradeGate(code, quantity);
        if (gate.failure() != null) {
            return CompletableFuture.completedFuture(gate.failure());
        }
        SimulationStock stock = gate.stock();
        return tradePrice(stock).thenCompose(price -> {
            if (price == null) return fail("trade-no-price");
            // 显式声明类型：三元表达式在 lambda 返回位上的类型推断容易踩坑
            CompletableFuture<TradeOutcome> outcome = leverage > 1
                    ? leveragedBuy(playerUuid, stock, quantity, leverage, price)
                    : cashBuy(playerUuid, stock, quantity, price);
            return outcome;
        });
    }

    /**
     * 卖出：先合计「杠杆多头 + 现金」总可卖量，不足则<b>整笔拒绝</b>；
     * 通过后先平杠杆部分（{@code LeverageService.closeQuantity} 内部自行结算入账），
     * 余量走 {@code reduceHoldingSell}，<b>先写库成功再入账</b>
     * （{@code payout} 带 1 次重试 + severe 日志），与 {@code LeverageService.settleClose} 同序。
     *
     * <p>整笔拒绝是必须的：先平杠杆、再对现金部分报「持仓不足」，玩家会以为
     * 整笔失败而重试，实际杠杆部分已经成交入账。
     */
    public CompletableFuture<TradeOutcome> sell(UUID playerUuid, String code, int quantity) {
        TradeGate gate = tradeGate(code, quantity);
        if (gate.failure() != null) {
            return CompletableFuture.completedFuture(gate.failure());
        }
        SimulationStock stock = gate.stock();
        return tradePrice(stock).thenCompose(price -> {
            if (price == null) return fail("trade-no-price");
            return supplyAsync(() -> combinedQuantity(playerUuid, stock.getCode()))
                    .thenCompose(total -> {
                        if (total < quantity - 1e-9) {
                            return total <= 1e-9
                                    ? fail("simulation-no-holding",
                                            "code", stock.getCode(), "hold", "0",
                                            "amount", String.valueOf(quantity))
                                    : fail("simulation-not-enough-holding",
                                            "code", stock.getCode(),
                                            "hold", formatQuantity(total),
                                            "amount", String.valueOf(quantity));
                        }
                        return closeLeveraged(playerUuid, stock.getCode(), quantity)
                                .thenCompose(closed ->
                                        sellCash(playerUuid, stock, quantity, price, closed));
                    });
        });
    }

    /** 「杠杆多头 + 现金持仓」合计可卖数量（quantityOf / holdingAmount 读库失败都按 0 计）。 */
    private double combinedQuantity(UUID playerUuid, String code) {
        LeverageService leverageService = plugin == null ? null : plugin.getLeverageService();
        double leveraged = leverageService == null ? 0.0
                : leverageService.quantityOf(playerUuid, MarketType.SIMULATION, code,
                        TradeDirection.LONG);
        return leveraged + holdingAmount(playerUuid, code);
    }

    /** 数量展示：去掉无意义的尾随零（7.0 → "7"，7.5 → "7.5"）。 */
    private static String formatQuantity(double value) {
        if (!Double.isFinite(value) || value <= 0) return "0";
        double rounded = Math.round(value * 1_000_000.0) / 1_000_000.0;
        if (rounded == Math.floor(rounded)) return String.valueOf((long) rounded);
        return String.valueOf(rounded);
    }

    /**
     * 一次性卖出该标的的全部<b>现金</b>持仓 —— 供持仓操作菜单的"卖出"按钮使用
     * （界面里没有数量输入框，卖光是最符合直觉的语义）。
     *
     * <p><b>只动现金仓、不碰杠杆仓</b>：杠杆仓位归「平仓 / 回补」入口管。
     * 之前这里把数量交给 {@link #sell}（先平杠杆、余量卖现金），玩家同时持有
     * 杠杆仓时会被整笔平掉而现金仓一股未动，还回「卖出成功」。
     *
     * <p>没有持仓时明确报 {@code simulation-no-holding}，绝不用
     * {@code Integer.MAX_VALUE} 之类的哨兵数量去碰运气：那样一旦杠杆通道回填
     * 了数量，就会在"根本没持仓"的前提下走出一条成功路径。
     */
    public CompletableFuture<TradeOutcome> sellAll(UUID playerUuid, String code) {
        String normalized = code == null ? null : code.trim().toUpperCase();
        if (normalized == null || normalized.isEmpty()) {
            return fail("simulation-no-holding",
                    "code", String.valueOf(code), "hold", "0", "amount", "0");
        }
        SimulationStock stock = registry.find(normalized).orElse(null);
        if (stock == null) {
            return fail("simulation-unknown-code", "code", displayCode(normalized));
        }
        if (!isEnabled()) {
            return fail("simulation-disabled");
        }
        if (!stock.isTradable() || stock.isSuspended()) {
            return fail("simulation-suspended", "code", stock.getCode());
        }
        return supplyAsync(() -> holdingAmount(playerUuid, normalized))
                .thenCompose(held -> {
                    if (held <= 0) {
                        return fail("simulation-no-holding",
                                "code", normalized, "hold", "0", "amount", "0");
                    }
                    return tradePrice(stock).thenCompose(price -> {
                        if (price == null) return fail("trade-no-price");
                        return sellCash(playerUuid, stock, held, price, zeroClose());
                    });
                });
    }

    /**
     * 模拟盘做空：开一条方向为 {@link TradeDirection#SHORT} 的保证金仓位。
     *
     * <p>与买入共用 {@link #leveragedOpen}，唯一差别是方向。模拟盘的现金持仓表
     * （{@code simulation_holdings}）是纯多头模型、没有方向字段，所以做空<b>没有</b>
     * 现金通道，一律走保证金引擎；{@code leverage <= 0} 时按 1 倍处理（即 100% 保证金）。
     *
     * @param leverage 指定杠杆；{@code <= 0} 表示 1 倍
     */
    public CompletableFuture<TradeOutcome> shortSell(UUID playerUuid, String code, int quantity,
                                                    int leverage) {
        TradeGate gate = tradeGate(code, quantity);
        if (gate.failure() != null) {
            return CompletableFuture.completedFuture(gate.failure());
        }
        SimulationStock stock = gate.stock();
        int effective = leverage > 0 ? leverage : 1;
        return tradePrice(stock).thenCompose(price -> {
            if (price == null) return fail("trade-no-price");
            return leveragedOpen(playerUuid, stock, quantity, effective, price,
                    TradeDirection.SHORT);
        });
    }

    /**
     * 模拟盘回补：平掉方向为 {@link TradeDirection#SHORT} 的保证金仓位。
     *
     * <p>只平空单。同一标的可以同时有多单与空单，不分方向地调用
     * {@code closeQuantity} 会把多单一起平掉。
     */
    public CompletableFuture<TradeOutcome> cover(UUID playerUuid, String code, int quantity) {
        TradeGate gate = tradeGate(code, quantity);
        if (gate.failure() != null) {
            return CompletableFuture.completedFuture(gate.failure());
        }
        SimulationStock stock = gate.stock();
        return tradePrice(stock).thenCompose(price -> {
            if (price == null) return fail("trade-no-price");
            return closeLeveraged(playerUuid, stock.getCode(), quantity, TradeDirection.SHORT)
                    .thenApply(closed -> closed == null || closed.closedQuantity() <= 0.0
                            ? TradeOutcome.fail("simulation-no-short",
                                    "code", stock.getCode(),
                                    "amount", String.valueOf(quantity))
                            : TradeOutcome.ok("simulation-cover-success",
                                    "code", stock.getCode(),
                                    "amount", formatQty(closed.closedQuantity()),
                                    "price", format2(price),
                                    "total", format2(closed.payout()),
                                    "fee", format2(closed.fee())));
        });
    }

    /**
     * 平掉该标的的全部空头仓位 —— 供持仓操作菜单的「平空」按钮使用。
     *
     * <p>与 {@link #sellAll} 同一条约定：没有仓位时明确报 {@code simulation-no-short}，
     * 不用哨兵数量去碰运气。
     */
    public CompletableFuture<TradeOutcome> coverAll(UUID playerUuid, String code) {
        String normalized = code == null ? null : code.trim().toUpperCase();
        if (normalized == null || normalized.isEmpty()) {
            return fail("simulation-no-short", "code", String.valueOf(code), "amount", "0");
        }
        return supplyAsync(() -> shortQuantity(playerUuid, normalized))
                .thenCompose(held -> held <= 0
                        ? fail("simulation-no-short", "code", normalized, "amount", "0")
                        : cover(playerUuid, normalized, held));
    }

    /** 该玩家在某标的上的空头持仓数量（四舍五入为整数，用于「平空」按钮）。 */
    public int shortQuantity(UUID playerUuid, String code) {
        LeverageService leverageService = plugin == null ? null : plugin.getLeverageService();
        if (leverageService == null) return 0;
        double total = leverageService.quantityOf(playerUuid, MarketType.SIMULATION, code,
                TradeDirection.SHORT);
        return (int) Math.round(total);
    }

    /**
     * 买卖入口的统一前置校验：① 代码未注册 → ② 未启用 → ③ 停牌 / 禁售 → ④ 数量非法。
     *
     * <p>顺序刻意与 {@link #adminGate} 一致（存在性判定在最前），这样"未知代码"永远得到
     * {@code simulation-unknown-code} —— 原先把"未注册 / 未启用"并列写在一起，
     * 但 {@code simulation.enabled: false} 时所有指令必须报
     * {@code simulation-disabled}，因此这里按"存在性 → 启用态"拆分，两种读法都满足。
     *
     * <p>通过时 {@code failure()} 为 {@code null}。
     */
    private TradeGate tradeGate(String code, int quantity) {
        SimulationStock stock = registry.find(code).orElse(null);
        if (stock == null) {
            return new TradeGate(null,
                    TradeOutcome.fail("simulation-unknown-code", "code", displayCode(code)));
        }
        if (!isEnabled()) {
            return new TradeGate(null, TradeOutcome.fail("simulation-disabled"));
        }
        if (!stock.isTradable() || stock.isSuspended()) {
            return new TradeGate(null,
                    TradeOutcome.fail("simulation-suspended", "code", stock.getCode()));
        }
        if (quantity <= 0) {
            return new TradeGate(null, TradeOutcome.fail("trade-invalid-quantity"));
        }
        return new TradeGate(stock, null);
    }

    /** ④ 开仓 / 估值共用同一口径：{@code rawPrice × price-ratio}；不可用返回 {@code null}。 */
    private CompletableFuture<Double> tradePrice(SimulationStock stock) {
        return rawPrice(stock.getCode()).handle((raw, error) -> {
            if (error != null || raw == null) {
                return (Double) null;
            }
            double ratio = mainConfig == null ? 1.0 : mainConfig.getPriceRatio();
            double price = SimulationPriceEngine.safePrice(raw * ratio);
            // 显式声明为 Double：条件表达式的两个分支类型不同，交给目标类型推断容易踩坑
            Double usable = (Double.isFinite(price) && price > 0) ? price : null;
            return usable;
        });
    }

    /** ⑤ 现金路径：扣款 → 写库；写库失败必须原额退回，绝不让玩家既没买到又丢了钱。 */
    private CompletableFuture<TradeOutcome> cashBuy(UUID playerUuid, SimulationStock stock,
                                                    int quantity, double price) {
        double fee = round2(price * quantity * feeRate());
        double total = round2(price * quantity + fee);
        return supplyAsync(() -> {
            EconomyService economy = plugin == null ? null : plugin.getEconomy();
            if (economy == null || !economy.isReady()) {
                return TradeOutcome.fail("vault-not-found");
            }
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
            if (!economy.has(offline, total)) {
                return TradeOutcome.fail("not-enough-money",
                        "need", format2(total),
                        "balance", format2(economy.getBalance(offline)));
            }
            if (!economy.withdraw(offline, total)) {
                return TradeOutcome.fail("not-enough-money",
                        "need", format2(total),
                        "balance", format2(economy.getBalance(offline)));
            }
            try {
                // 只写 simulation_holdings —— 模拟盘现金持仓与真实 holdings 完全隔离
                repository.upsertHoldingBuy(playerUuid, stock.getCode(), quantity, price);
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘买入写库失败 " + stock.getCode()
                        + ": " + e.getMessage());
                refund(playerUuid, total, "模拟盘买入");
                return TradeOutcome.fail("db-error");
            }
            return TradeOutcome.ok("simulation-buy-success",
                    "code", stock.getCode(),
                    "amount", String.valueOf(quantity),
                    "price", format2(price),
                    "total", format2(total),
                    "fee", format2(fee));
        });
    }

    /**
     * ⑥ 杠杆路径：复用保证金引擎，市场恒为 {@link MarketType#SIMULATION}。
     *
     * <p>杠杆区间校验（{@code Policy.of(SIMULATION) == STOCK} ⇒ 股票上限 + enabled 开关）、
     * 余额校验与扣款、保证金冻结、写库失败退款全部由 {@code LeverageService.open} 负责。
     */
    private CompletableFuture<TradeOutcome> leveragedBuy(UUID playerUuid, SimulationStock stock,
                                                         int quantity, int leverage, double price) {
        return leveragedOpen(playerUuid, stock, quantity, leverage, price, TradeDirection.LONG);
    }

    /**
     * ⑥ 杠杆路径（按方向）：复用保证金引擎，市场恒为 {@link MarketType#SIMULATION}。
     *
     * <p>杠杆区间校验（{@code Policy.of(SIMULATION) == STOCK} ⇒ 股票上限 + enabled 开关）、
     * 余额校验与扣款、保证金冻结、写库失败退款全部由 {@code LeverageService.open} 负责。
     */
    private CompletableFuture<TradeOutcome> leveragedOpen(UUID playerUuid, SimulationStock stock,
                                                          int quantity, int leverage, double price,
                                                          TradeDirection direction) {
        LeverageService leverageService = plugin == null ? null : plugin.getLeverageService();
        if (leverageService == null) return fail("db-error");
        double fee = round2(price * quantity * feeRate());
        return leverageService.open(playerUuid, MarketType.SIMULATION, stock.getCode(),
                direction, quantity, price, leverage, fee,
                LeverageConfig.Policy.of(MarketType.SIMULATION));
    }

    /** 平掉杠杆部分（市场恒为 SIMULATION，不分方向）；无杠杆持仓时返回零结果。 */
    private CompletableFuture<LeverageService.PartialClose> closeLeveraged(UUID playerUuid,
                                                                           String code, int quantity) {
        return closeLeveraged(playerUuid, code, quantity, TradeDirection.LONG);
    }

    /** 平掉指定方向的杠杆部分（市场恒为 SIMULATION）；无杠杆持仓时返回零结果。 */
    private CompletableFuture<LeverageService.PartialClose> closeLeveraged(UUID playerUuid,
                                                                           String code, int quantity,
                                                                           TradeDirection direction) {
        LeverageService leverageService = plugin == null ? null : plugin.getLeverageService();
        if (leverageService == null) {
            return CompletableFuture.completedFuture(zeroClose());
        }
        SymbolPriceSource prices = plugin.priceSource();
        return leverageService.closeQuantity(playerUuid, MarketType.SIMULATION, code, direction,
                quantity, prices, feeRate());
    }

    /** 现金路径卖出：先 {@code reduceHoldingSell} 成功，再入账。 */
    private CompletableFuture<TradeOutcome> sellCash(UUID playerUuid, SimulationStock stock,
                                                     int quantity, double price,
                                                     LeverageService.PartialClose closed) {
        double closedQuantity = closed == null ? 0.0 : Math.max(0.0, closed.closedQuantity());
        double totalPayout = closed == null ? 0.0 : closed.payout();
        double totalFee = closed == null ? 0.0 : closed.fee();
        int remaining = quantity - (int) Math.round(closedQuantity);
        if (remaining <= 0) {
            // 杠杆仓位已覆盖整笔请求，没有现金持仓可减。
            //
            // 但必须先确认真的平掉了东西：closedQuantity 一旦被异常结算值放大，
            // (int) 强转会饱和到 Integer.MAX_VALUE，把 remaining 变成大负数 ——
            // 这种情况下绝不能报"卖出成功"，否则就是"没有持仓也能卖出"。
            if (closedQuantity <= 0.0) {
                return CompletableFuture.completedFuture(TradeOutcome.fail("simulation-no-holding",
                        "code", stock.getCode(),
                        "hold", "0",
                        "amount", String.valueOf(quantity)));
            }
            return CompletableFuture.completedFuture(TradeOutcome.ok("simulation-sell-success",
                    "code", stock.getCode(),
                    "amount", String.valueOf(quantity),
                    "price", format2(price),
                    "total", format2(totalPayout),
                    "fee", format2(totalFee)));
        }

        double cashTotalPayout = totalPayout;
        double cashTotalFee = totalFee;
        return supplyAsync(() -> {
            boolean reduced;
            try {
                // 持仓不足用返回值表达（不抛异常），且事务内已回滚 ⇒ 持仓保持原值
                reduced = repository.reduceHoldingSell(playerUuid, stock.getCode(), remaining, price);
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘卖出写库失败 " + stock.getCode()
                        + ": " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            if (!reduced) {
                int held = holdingAmount(playerUuid, stock.getCode());
                return held <= 0
                        ? TradeOutcome.fail("simulation-no-holding",
                                "code", stock.getCode(),
                                "hold", "0",
                                "amount", String.valueOf(remaining))
                        : TradeOutcome.fail("simulation-not-enough-holding",
                                "code", stock.getCode(),
                                "hold", String.valueOf(held),
                                "amount", String.valueOf(remaining));
            }

            double fee = round2(price * remaining * feeRate());
            double payout = Math.max(0.0, round2(price * remaining - fee));
            if (payout > 0 && !payoutTo(playerUuid, payout)) {
                // 行已删除，钱必须到账；重试仍失败则记 severe 供人工补偿，绝不静默吞掉
                log(Level.SEVERE, "[MineStock] 模拟盘卖出入账失败（已重试），需人工补偿：玩家 "
                        + playerUuid + " 金额 " + format2(payout) + " 代码 " + stock.getCode());
            }
            return TradeOutcome.ok("simulation-sell-success",
                    "code", stock.getCode(),
                    "amount", String.valueOf(quantity),
                    "price", format2(price),
                    "total", format2(cashTotalPayout + payout),
                    "fee", format2(cashTotalFee + fee));
        });
    }

    public CompletableFuture<List<SimulationHolding>> holdings(UUID playerUuid) {
        if (playerUuid == null || !hasDatabase()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return supplyAsync(() -> {
            try {
                return repository.findHoldings(playerUuid);
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 读取模拟持仓失败: " + e.getMessage());
                return List.<SimulationHolding>of();
            }
        });
    }

    /** 持仓视图；估值价与 {@link #marketPrice} 同口径（含 price-ratio）。 */
    public CompletableFuture<List<SimulationPositionView>> portfolio(UUID playerUuid) {
        return holdings(playerUuid).thenApply(held -> {
            List<SimulationPositionView> views = new ArrayList<>(held.size());
            for (SimulationHolding holding : held) {
                SimulationStock stock = registry.find(holding.getCode()).orElse(null);
                String name = stock == null ? holding.getCode() : stock.getName();
                double price = stock == null
                        ? SimulationPriceEngine.safePrice(holding.getLastPrice())
                        : marketPriceOf(stock);
                int amount = holding.getAmount();
                double avgCost = holding.getAvgCost();
                double marketValue = price * amount;
                double pnl = (price - avgCost) * amount;
                double pnlPercent = avgCost > 0 ? (price / avgCost - 1.0) * 100.0 : 0.0;
                views.add(new SimulationPositionView(holding.getCode(), name, amount, avgCost,
                        price, marketValue, pnl, pnlPercent));
            }
            return List.copyOf(views);
        });
    }

    // ── 管理员：注册 / 注销模拟股票 ──────────────────────────────────────────

    /** 模拟代码格式：三位大写字母 + 三位数字，与模拟盘源的识别正则一致。 */
    private static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("[A-Z]{3}\\d{3}");

    /**
     * 管理员注册一只新的模拟股票。
     *
     * <p>代码必须匹配 {@code [A-Z]{3}\d{3}}：这就是模拟盘源
     * {@code SimulationStockSource.supports} 的筛选条件，格式不符的代码不会被
     * 模拟盘源认领，玩家买不进、K 线也画不出来。
     *
     * <p>先落库再进内存：写库失败时不会留下一只"看得见却查不到"的幽灵股票。
     */
    public CompletableFuture<TradeOutcome> registerStock(String code, String name, String sector,
                                                         double initialPrice) {
        String normalized = SimulationStockRegistry.normalize(code);
        String displayName = name == null ? "" : name.trim();
        String displaySector = sector == null ? "" : sector.trim();
        if (normalized == null || !CODE_PATTERN.matcher(normalized).matches()
                || displayName.isEmpty() || displaySector.isEmpty()
                || !Double.isFinite(initialPrice)
                || initialPrice < MIN_PRICE || initialPrice > MAX_PRICE) {
            return invalidArgs("/mstock simulation admin add <代码> <名称> <板块> <初始价>");
        }
        if (registry.contains(normalized)) {
            return fail("simulation-admin-add-exists", "code", normalized);
        }
        SimulationStock stock = new SimulationStock(normalized, displayName, displaySector,
                initialPrice, registry.nextSeed());
        return supplyAsync(() -> {
            try {
                if (hasDatabase()) {
                    repository.upsertStocks(List.of(stock));
                }
                registry.put(stock);
                return TradeOutcome.ok("simulation-admin-add-ok", "code", normalized,
                        "name", displayName, "price", format2(initialPrice));
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 注册模拟股票失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
        });
    }

    /**
     * 管理员注销一只模拟股票。
     *
     * <p>该代码下玩家已持有的持仓与历史 K 线<b>刻意保留</b>
     * （{@code SimulationStockRegistry.remove} 只删股票本身与关联/计划）：
     * 误删之后重新注册回来，玩家的账还在。
     */
    public CompletableFuture<TradeOutcome> unregisterStock(String code) {
        String normalized = SimulationStockRegistry.normalize(code);
        if (normalized == null) {
            return invalidArgs("/mstock simulation admin remove <代码>");
        }
        if (!registry.contains(normalized)) {
            return fail("simulation-admin-remove-missing", "code", normalized);
        }
        return supplyAsync(() -> {
            try {
                registry.remove(normalized);
                return TradeOutcome.ok("simulation-admin-remove-ok", "code", normalized);
            } catch (RuntimeException e) {
                log(Level.WARNING, "[MineStock] 注销模拟股票失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
        });
    }

    /**
     * 持仓展示视图。
     *
     * @param code        模拟代码
     * @param name        显示名
     * @param amount      持有数量
     * @param avgCost     加权平均成本
     * @param price       当前价（含 price-ratio）
     * @param marketValue 市值
     * @param pnl         浮动盈亏
     * @param pnlPercent  浮动盈亏率（百分数）
     */
    public record SimulationPositionView(String code, String name, int amount, double avgCost,
                                         double price, double marketValue, double pnl,
                                         double pnlPercent) {
    }

    // ── 面向管理员（指令层已判权限；此处只做参数合法性与存在性校验）────────

    /** {@code setprice}：定值覆盖；与 {@code target} 互斥写。 */
    public CompletableFuture<TradeOutcome> setPrice(String code, double price) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (!Double.isFinite(price) || price < MIN_PRICE) {
            return invalidArgs("/mstock simulation admin setprice <代码> <价格>");
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            // 互斥写：setManualPrice 内部会把 manual_target_pct 置 null
            stock.setManualPrice(price);
        }
        return persist(stock, "simulation-admin-setprice",
                "code", stock.getCode(), "price", format2(price));
    }

    /** {@code target}：相对昨收的目标涨幅覆盖；与 {@code setprice} 互斥写。 */
    public CompletableFuture<TradeOutcome> setTargetPercent(String code, double percent) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (!Double.isFinite(percent)) {
            return invalidArgs("/mstock simulation admin target <代码> <涨幅%>");
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            // 互斥写：setManualTargetPercent 内部会把 manual_price 置 null
            stock.setManualTargetPercent(percent);
        }
        return persist(stock, "simulation-admin-target",
                "code", stock.getCode(), "percent", format2(percent),
                "value", format2(stock.getPrevClose() * (1 + percent / 100.0)));
    }

    /** {@code jump}：一次性冲击，只改 {@code autoPrice}（人工覆盖与涨跌停仍然优先）。 */
    public CompletableFuture<TradeOutcome> jump(String code, double percent) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (!Double.isFinite(percent)) {
            return invalidArgs("/mstock simulation admin jump <代码> <涨跌%>");
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setAutoPrice(SimulationPriceEngine.safePrice(
                    stock.getAutoPrice() * (1 + percent / 100.0)));
        }
        return persist(stock, "simulation-admin-jump",
                "code", stock.getCode(), "percent", format2(percent));
    }

    /** {@code pause}：暂停推进（价格冻结，但仍可交易）。 */
    public CompletableFuture<TradeOutcome> setPaused(String code, boolean paused) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setPaused(paused);
        }
        return persist(stock, "simulation-admin-pause",
                "code", stock.getCode(), "state", paused ? "on" : "off");
    }

    /** {@code slope}：每毫秒附加绝对价格增量，到期自动失效。 */
    public CompletableFuture<TradeOutcome> setSlope(String code, double perMs, long durationMs) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (!Double.isFinite(perMs) || durationMs <= 0L) {
            return invalidArgs("/mstock simulation admin slope <代码> <每毫秒价格> <持续毫秒>");
        }
        SimulationStock stock = gate.stock();
        long until = System.currentTimeMillis() + durationMs;
        synchronized (stock) {
            stock.setSlopePerMs(perMs);
            stock.setSlopeUntil(until);
        }
        return persist(stock, "simulation-admin-slope",
                "code", stock.getCode(), "per_ms", format2(perMs), "ms", String.valueOf(durationMs));
    }

    /** {@code amplitude}：对数收益乘数。 */
    public CompletableFuture<TradeOutcome> setAmplitude(String code, double amplitude) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (!Double.isFinite(amplitude) || amplitude < 0.0) {
            return invalidArgs("/mstock simulation admin amplitude <代码> <倍数>");
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setAmplitude(amplitude);
        }
        return persist(stock, "simulation-admin-amplitude",
                "code", stock.getCode(), "value", format2(amplitude));
    }

    /** {@code preset}：波动预设，覆写 sigma / theta / jump 系列与 amplitude。 */
    public CompletableFuture<TradeOutcome> setPreset(String code, SimulationVolatilityPreset preset) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (preset == null) {
            return invalidArgs("/mstock simulation admin preset <代码> <low|normal|high>");
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.applyPreset(preset);
        }
        return persist(stock, "simulation-admin-preset",
                "code", stock.getCode(), "value", preset.id());
    }

    /** {@code bounds}：管理员绝对上下限（始终硬截断）。 */
    public CompletableFuture<TradeOutcome> setBounds(String code, double min, double max) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        // 绝不允许出现 min >= max 的退化区间（SimulationStock.setBounds 会整组丢弃，
        // 但这里必须给出明确的拒绝原因，而不是静默保持原值）
        if (!Double.isFinite(min) || !Double.isFinite(max)
                || min < MIN_PRICE || max > MAX_PRICE) {
            return invalidArgs("/mstock simulation admin bounds <代码> <下限> <上限>");
        }
        // 上限必须<b>严格大于</b>下限。单独一条提示（而不是上面那句用法串），
        // 因为 GUI 的 ± 步进按钮也会走到这里 —— 玩家需要知道的是"越界了"，
        // 而不是"你的参数写法不对"。
        if (min >= max) {
            return fail("simulation-admin-bounds-order",
                    "min", format2(min), "max", format2(max));
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setBounds(min, max);
        }
        return persist(stock, "simulation-admin-bounds",
                "code", stock.getCode(), "min", format2(min), "max", format2(max));
    }

    /** {@code limit}：涨跌停百分数（{@code 10.0} = ±10%）。 */
    public CompletableFuture<TradeOutcome> setLimitPercent(String code, double percent) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (!Double.isFinite(percent) || percent < 0.0001 || percent > 10.0) {
            return invalidArgs("/mstock simulation admin limit <代码> <±%>");
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setLimitPercent(percent);
        }
        return persist(stock, "simulation-admin-limit",
                "code", stock.getCode(), "percent", format2(percent));
    }

    /** {@code boundary}：涨跌停带越界策略。 */
    public CompletableFuture<TradeOutcome> setBoundary(String code, SimulationBoundary boundary) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (boundary == null) {
            return invalidArgs("/mstock simulation admin boundary <代码> <hard|bounce|soft>");
        }
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setBoundary(boundary);
        }
        return persist(stock, "simulation-admin-boundary",
                "code", stock.getCode(), "value", boundary.id());
    }

    /** {@code suspend}：停牌（价格冻结 + 禁止买卖 + 强平巡检跳过）。 */
    public CompletableFuture<TradeOutcome> setSuspended(String code, boolean suspended) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setSuspended(suspended);
        }
        return persist(stock, "simulation-admin-suspend",
                "code", stock.getCode(), "state", suspended ? "on" : "off");
    }

    /** {@code tradable}：禁售开关（价格仍照常推进，只是不允许买卖）。 */
    public CompletableFuture<TradeOutcome> setTradable(String code, boolean tradable) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setTradable(tradable);
        }
        return persist(stock, "simulation-admin-tradable",
                "code", stock.getCode(), "state", tradable ? "on" : "off");
    }

    /**
     * {@code link}：建立 / 更新联动。
     *
     * <p>顺序归一化（{@code code_a < code_b}）与 rho 夹取由
     * {@link SimulationLink#of} / 仓储完成，因此 {@code (A,B)} 与 {@code (B,A)} 落成同一行；
     * 自环在此显式拒绝（它会让该股与自己共线，并污染 rho 求和）。
     */
    public CompletableFuture<TradeOutcome> link(String codeA, String codeB, double correlation) {
        SimulationStock a = registry.find(codeA).orElse(null);
        if (a == null) {
            return fail("simulation-unknown-code", "code", displayCode(codeA));
        }
        SimulationStock b = registry.find(codeB).orElse(null);
        if (b == null) {
            return fail("simulation-unknown-code", "code", displayCode(codeB));
        }
        if (!isEnabled()) return fail("simulation-disabled");
        if (a.getCode().equals(b.getCode()) || !Double.isFinite(correlation)) {
            return invalidArgs("/mstock simulation admin link <代码A> <代码B> <相关系数>");
        }
        double rho = correlation;
        return supplyAsync(() -> {
            try {
                repository.upsertLink(a.getCode(), b.getCode(), rho);
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘联动写库失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            reloadLinks();
            return TradeOutcome.ok("simulation-admin-link",
                    "code_a", a.getCode(),
                    "code_b", b.getCode(),
                    "rho", format2(SimulationLink.of(a.getCode(), b.getCode(), rho).correlation()));
        });
    }

    /** {@code unlink}：删除联动。 */
    public CompletableFuture<TradeOutcome> unlink(String codeA, String codeB) {
        SimulationStock a = registry.find(codeA).orElse(null);
        if (a == null) {
            return fail("simulation-unknown-code", "code", displayCode(codeA));
        }
        SimulationStock b = registry.find(codeB).orElse(null);
        if (b == null) {
            return fail("simulation-unknown-code", "code", displayCode(codeB));
        }
        if (!isEnabled()) return fail("simulation-disabled");
        return supplyAsync(() -> {
            boolean removed;
            try {
                removed = repository.deleteLink(a.getCode(), b.getCode());
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘联动删除失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            reloadLinks();
            if (!removed) {
                // 无专用语言键，复用"未知对象"键并给出完整代码对
                return TradeOutcome.fail("simulation-unknown-code",
                        "code", a.getCode() + "-" + b.getCode());
            }
            return TradeOutcome.ok("simulation-admin-success",
                    "action", "unlink", "code", a.getCode(), "value", b.getCode());
        });
    }

    /** {@code schedule}：定时价格冲击；{@code daily == false} 时触发一次后自动禁用。 */
    public CompletableFuture<TradeOutcome> schedule(String code, String hhmm, double percent,
                                                    boolean daily) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        if (!isValidTimeOfDay(hhmm) || !Double.isFinite(percent)) {
            return invalidArgs("/mstock simulation admin schedule <代码> <HH:mm> <涨跌%> "
                    + "<daily|once>");
        }
        SimulationStock stock = gate.stock();
        String time = hhmm.strip();
        return supplyAsync(() -> {
            long id;
            try {
                id = repository.insertSchedule(stock.getCode(), time, percent, daily);
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘定时写库失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            reloadSchedules();
            return TradeOutcome.ok("simulation-admin-schedule",
                    "code", stock.getCode(),
                    "time", time,
                    "percent", format2(percent),
                    "mode", daily ? "daily" : "once",
                    "id", String.valueOf(id));
        });
    }

    /** {@code schedules}：列出全部定时操作（只读查询，不需要"存在性"前置校验）。 */
    public CompletableFuture<List<SimulationSchedule>> schedules() {
        if (!hasDatabase()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return supplyAsync(() -> {
            try {
                return repository.findSchedules();
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 读取模拟盘定时失败: " + e.getMessage());
                return List.<SimulationSchedule>of();
            }
        });
    }

    /** {@code unschedule}：按 id 删除定时操作。 */
    public CompletableFuture<TradeOutcome> unschedule(long id) {
        if (!isEnabled()) return fail("simulation-disabled");
        if (id <= 0L) {
            return invalidArgs("/mstock simulation admin unschedule <id>");
        }
        return supplyAsync(() -> {
            boolean removed;
            try {
                removed = repository.deleteSchedule(id);
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘定时删除失败: " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            reloadSchedules();
            if (!removed) {
                // 同上：无专用"定时不存在"语言键，复用"未知对象"键
                return TradeOutcome.fail("simulation-unknown-code", "code", String.valueOf(id));
            }
            return TradeOutcome.ok("simulation-admin-unschedule", "id", String.valueOf(id));
        });
    }

    /** {@code seed}：重建随机源；{@code seed == null} 时用 {@code registry.nextSeed()}。 */
    public CompletableFuture<TradeOutcome> reseed(String code, Long seed) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        SimulationStock stock = gate.stock();
        long effective = seed == null ? registry.nextSeed() : seed;
        synchronized (stock) {
            stock.reseed(effective);
        }
        return persist(stock, "simulation-admin-seed",
                "code", stock.getCode(), "seed", String.valueOf(effective));
    }

    /**
     * {@code resume}：清 manual_price / manual_target_pct / slope_per_ms / slope_until / paused。
     *
     * <p><b>不复活</b> {@code suspended} / {@code tradable} —— 它们是治安开关而非价格指令。
     */
    public CompletableFuture<TradeOutcome> resume(String code) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            stock.setManualPrice(null);
            stock.setManualTargetPercent(null);
            stock.setSlopePerMs(0.0);
            stock.setSlopeUntil(0L);
            stock.setPaused(false);
        }
        return persist(stock, "simulation-admin-resume",
                "code", stock.getCode(), "seed", String.valueOf(stock.getSeed()));
    }

    /**
     * {@code reset}：回到初值并重放同一条价格路径。
     *
     * <p>{@code prevClose = currentPrice = autoPrice = anchor = initPrice}、清空指令与
     * {@code paused/suspended}、套用默认预设与上下限、同种子重建 {@code Random}、
     * 清空 K 线缓冲。<b>不删</b> {@code simulation_klines} 历史（避免误删）。
     */
    public CompletableFuture<TradeOutcome> reset(String code) {
        AdminGate gate = adminGate(code);
        if (gate.failure() != null) return gate.failure();
        SimulationStock stock = gate.stock();
        synchronized (stock) {
            double init = stock.getInitPrice();
            stock.setPrevClose(init);
            stock.setCurrentPrice(init);
            stock.setAutoPrice(init);
            stock.setAnchor(init);
            stock.setManualPrice(null);
            stock.setManualTargetPercent(null);
            stock.setSlopePerMs(0.0);
            stock.setSlopeUntil(0L);
            stock.setPaused(false);
            stock.setSuspended(false);
            stock.applyPreset(config.defaultPreset());
            stock.setBounds(config.minPrice(), config.maxPrice());
            // 同种子 ⇒ 同一条价格路径，可在测试中重放
            stock.reseed(stock.getSeed());
            stock.setUpdatedAt(System.currentTimeMillis());
        }
        bufferOf(stock).clear();
        return persist(stock, "simulation-admin-reset",
                "code", stock.getCode(), "seed", String.valueOf(stock.getSeed()));
    }

    // ── 内部辅助 ────────────────────────────────────────────────────────────

    /** 买卖入口的统一前置校验结果。 */
    private record TradeGate(SimulationStock stock, TradeOutcome failure) {
    }

    /** 管理入口的统一前置校验结果。 */
    private record AdminGate(SimulationStock stock, CompletableFuture<TradeOutcome> failure) {
    }

    /**
     * 管理入口的统一前置校验（Service 层的"双重校验"）：
     * ① 代码未注册 → {@code simulation-unknown-code}；② 未启用 → {@code simulation-disabled}。
     *
     * <p>存在性判定在启用判定之前，顺序不可调换。
     */
    private AdminGate adminGate(String code) {
        SimulationStock stock = registry.find(code).orElse(null);
        if (stock == null) {
            return new AdminGate(null, fail("simulation-unknown-code", "code", displayCode(code)));
        }
        if (!isEnabled()) {
            return new AdminGate(null, fail("simulation-disabled"));
        }
        return new AdminGate(stock, null);
    }

    /**
     * 管理写操作的统一落库：在 {@code LifecycleTaskManager} 上异步 upsert 单只股票，
     * 与 {@code LeverageService} 的线程模型一致。
     *
     * <p>失败时<b>保留</b> {@code dirtyStocks}：内存里的改动是真的，下一 tick 的
     * 5 秒落库会再写一次，因此"内存已改、库未写"是短暂且可自愈的。
     */
    private CompletableFuture<TradeOutcome> persist(SimulationStock stock, String langKey,
                                                    String... placeholders) {
        dirtyStocks = true;
        if (!hasDatabase()) {
            return CompletableFuture.completedFuture(TradeOutcome.ok(langKey, placeholders));
        }
        return supplyAsync(() -> {
            try {
                repository.upsertStock(stock);
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 模拟盘管理写库失败 " + stock.getCode()
                        + ": " + e.getMessage());
                return TradeOutcome.fail("db-error");
            }
            return TradeOutcome.ok(langKey, placeholders);
        });
    }

    /** 在线程模型上与 {@code LeverageService} 对齐；无插件上下文时就地执行，绝不静默丢弃写操作。 */
    private <T> CompletableFuture<T> supplyAsync(Callable<T> work) {
        LifecycleTaskManager tasks = plugin == null ? null : plugin.getTaskManager();
        if (tasks != null) {
            return tasks.supplyAsync(work);
        }
        try {
            return CompletableFuture.completedFuture(work.call());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 每只股一个缓冲，容量取 {@code simulation.kline-capacity}。 */
    private SimulationKLineBuffer bufferOf(SimulationStock stock) {
        return buffers.computeIfAbsent(stock.getCode(),
                code -> new SimulationKLineBuffer(config.klineCapacity()));
    }

    /** 联动缓存刷新；读库失败时保留旧值（联动是增强项，不该因一次抖动被清空）。 */
    private void reloadLinks() {
        if (!hasDatabase()) return;
        try {
            links = repository.findLinks();
        } catch (SQLException e) {
            log(Level.WARNING, "[MineStock] 读取模拟盘联动失败: " + e.getMessage());
        }
    }

    /** 定时缓存刷新；读库失败时保留旧值。 */
    private void reloadSchedules() {
        if (!hasDatabase()) return;
        try {
            schedules = repository.findSchedules();
        } catch (SQLException e) {
            log(Level.WARNING, "[MineStock] 读取模拟盘定时失败: " + e.getMessage());
        }
    }

    /** K 线合并：DB 历史（窗口内）+ 内存缓冲（同桶以缓冲为准），交给 {@code withFallback} 补齐。 */
    private List<KLinePoint> klineOf(SimulationStock stock, int days) {
        long now = System.currentTimeMillis();
        long from = now - (long) days * 86_400_000L;
        return SimulationKLineBuffer.withFallback(mergedBars(stock, from), days,
                stock.getCode(), stock.getPrevClose(), now);
    }

    /**
     * 周期感知的 K 线合并。
     *
     * <p>DB 窗口由"周期 × 根数"反推（再留 2 倍余量 —— 多根 1 分钟 bar 会聚合成一根），
     * 而不是旧版写死的 {@code days} 天：这样 1m 请求不会白扫 30 天的库，
     * 1d 请求也不会因为窗口太窄而只剩一两根。
     */
    private List<KLinePoint> klineOf(SimulationStock stock, String interval, int limit) {
        long now = System.currentTimeMillis();
        long bucketMs = SimulationKLineBuffer.intervalMillis(interval);
        long span = Math.min(Math.max(86_400_000L, bucketMs * (long) limit * 2L),
                3650L * 86_400_000L);
        return SimulationKLineBuffer.withFallback(mergedBars(stock, now - span), interval, limit,
                stock.getCode(), stock.getPrevClose(), now);
    }

    /**
     * DB 历史（{@code from} 之后）+ 内存缓冲，按桶起点升序；同桶以内存值覆盖库值。
     *
     * <p>用 {@link TreeMap} 而不是 {@link java.util.HashMap}：图表要求时间单调递增，
     * 乱序的点会让 K 线画成来回横跳的折线。
     */
    private List<SimulationBar> mergedBars(SimulationStock stock, long from) {
        TreeMap<Long, SimulationBar> merged = new TreeMap<>();
        if (hasDatabase()) {
            try {
                for (SimulationBar bar : repository.findKlines(stock.getCode(), from)) {
                    if (bar != null) merged.put(bar.bucketStart(), bar);
                }
            } catch (SQLException e) {
                log(Level.WARNING, "[MineStock] 读取模拟盘 K 线失败 " + stock.getCode()
                        + ": " + e.getMessage());
            }
        }
        for (SimulationBar bar : bufferOf(stock).snapshot()) {
            if (bar != null) merged.put(bar.bucketStart(), bar);
        }
        return new ArrayList<>(merged.values());
    }

    /** 合并口径的估值价：{@code currentPrice × price-ratio}，与 EQUITY 分支完全一致。 */
    private double marketPriceOf(SimulationStock stock) {
        double ratio = mainConfig == null ? 1.0 : mainConfig.getPriceRatio();
        return SimulationPriceEngine.safePrice(stock.getCurrentPrice() * ratio);
    }

    /** 手续费率（{@code transaction-fee-percent / 100}）。 */
    private double feeRate() {
        return mainConfig == null ? 0.0 : mainConfig.getTransactionFeeRate();
    }

    /** 读一次现金持仓数量；读不到按 0 处理（只用于错误提示）。 */
    private int holdingAmount(UUID playerUuid, String code) {
        if (!hasDatabase()) return 0;
        try {
            SimulationHolding holding = repository.findHolding(playerUuid, code);
            return holding == null ? 0 : holding.getAmount();
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 入账，失败重试一次（与 {@code LeverageService.payoutTo} 同序）。 */
    private boolean payoutTo(UUID playerUuid, double amount) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                EconomyService economy = plugin == null ? null : plugin.getEconomy();
                if (economy == null || !economy.isReady()) return false;
                OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
                if (economy.deposit(offline, amount)) return true;
            } catch (RuntimeException e) {
                log(Level.WARNING, "[MineStock] 模拟盘入账异常（第 " + (attempt + 1)
                        + " 次）: " + e.getMessage());
            }
        }
        return false;
    }

    /** 写库失败时把已扣的钱原额退回，避免玩家凭空损失。 */
    private void refund(UUID playerUuid, double amount, String reason) {
        try {
            EconomyService economy = plugin == null ? null : plugin.getEconomy();
            if (economy == null || !economy.isReady()) return;
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
            if (!economy.deposit(offline, amount)) {
                log(Level.SEVERE, "[MineStock] " + reason + "退款失败！玩家 " + playerUuid
                        + " 金额 " + amount);
            }
        } catch (RuntimeException e) {
            log(Level.SEVERE, "[MineStock] " + reason + "退款异常: " + e.getMessage());
        }
    }

    /**
     * {@code "HH:mm"} 严格校验。
     *
     * <p>与 {@link SimulationSchedule#matches} 同规则：恰好 5 字符、小时 {@code 00-23}、
     * 分钟 {@code 00-59}，各占 2 位。不借助宽容度随 JDK 版本变化的解析器。
     */
    private static boolean isValidTimeOfDay(String hhmm) {
        if (hhmm == null) return false;
        String raw = hhmm.strip();
        if (raw.length() != 5 || raw.charAt(2) != ':') return false;
        int hour = twoDigits(raw, 0);
        int minute = twoDigits(raw, 3);
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }

    private static int twoDigits(String raw, int offset) {
        int high = Character.digit(raw.charAt(offset), 10);
        int low = Character.digit(raw.charAt(offset + 1), 10);
        if (high < 0 || low < 0) return -1;
        return high * 10 + low;
    }

    /** 未注册 / 非法代码的展示形式（去空白 + 大写），避免把 {@code null} 塞进语言键占位符。 */
    private static String displayCode(String code) {
        String normalized = SimulationStockRegistry.normalize(code);
        return normalized == null ? "" : normalized;
    }

    private boolean hasDatabase() {
        return db != null;
    }

    /** 零结果的部分平仓（无杠杆持仓 / 无杠杆服务）。 */
    private static LeverageService.PartialClose zeroClose() {
        return new LeverageService.PartialClose(0, 0, 0, List.of());
    }

    private static CompletableFuture<TradeOutcome> fail(String langKey, String... placeholders) {
        return CompletableFuture.completedFuture(TradeOutcome.fail(langKey, placeholders));
    }

    private static CompletableFuture<TradeOutcome> invalidArgs(String usage) {
        return fail("simulation-admin-invalid-args", "usage", usage);
    }

    private static double round2(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 数量展示：整数不带小数点，小数保留两位（杠杆仓位允许小数数量）。 */
    private static String formatQty(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return format2(value);
    }

    private void log(Level level, String message) {
        Logger current = this.logger;
        if (current != null) current.log(level, message);
    }
}
