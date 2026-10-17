package dev.onelili.mstock.chart;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.market.ForexProvider;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.stockio.StockApiService;
import dev.onelili.mstock.util.LangUtil;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 图表数据编排：选数据源、控冷却、异步取数、渲染成消息行。
 *
 * <p>数据源优先级：<b>股票优先</b>（保持原仓库行为不变），股票不支持或取数失败时
 * 才回退到 biquote 外汇 / CFD。这样 {@code /mstock chart AAPL} 仍走原路径，
 * 而 {@code /mstock chart EURUSD} 走 biquote。
 *
 * <p>价格一律使用 biquote 的 {@code mid}（由 {@link ForexProvider} 保证）。
 */
public class ChartService implements AutoCloseable {

    private final MineStock plugin;
    private final StockApiService stockApi;
    private final ForexProvider forexProvider;
    private final MainConfig config;
    private final ChatChartRenderer renderer = new ChatChartRenderer();
    private final Logger logger;

    /** 每个玩家独立的图表冷却时间戳。 */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public ChartService(MineStock plugin, StockApiService stockApi,
                        ForexProvider forexProvider, MainConfig config) {
        this.plugin = plugin;
        this.stockApi = stockApi;
        this.forexProvider = forexProvider;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    /**
     * 冷却检查（每玩家 {@code chart.cooldown-ms}，默认 3 秒）。
     *
     * @return 剩余的毫秒数；{@code 0} 表示可以执行，并且已记录本次时间戳
     */
    public long checkCooldown(UUID playerUuid) {
        long cd = config.getChartCooldownMs();
        if (cd <= 0) return 0L;
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(playerUuid);
        if (last != null) {
            long elapsed = now - last;
            if (elapsed < cd) return cd - elapsed;
        }
        cooldowns.put(playerUuid, now);
        return 0L;
    }

    /** 清理玩家冷却记录（退出时调用）。 */
    public void clearCooldown(UUID playerUuid) {
        cooldowns.remove(playerUuid);
    }

    /**
     * 取 OHLC 并渲染成聊天消息行。
     *
     * @return 渲染结果；数据不足或接口失败时以 {@code null} 完成，由调用方发错误消息
     */
    public CompletableFuture<List<String>> chart(String symbol, String interval, int limit) {
        String normalized = symbol == null ? null : symbol.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized == null || normalized.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String tf = interval == null || interval.isBlank()
                ? config.getChartDefaultInterval() : interval.strip().toLowerCase(java.util.Locale.ROOT);
        int capped = Math.max(ChatChartRenderer.MIN_BARS,
                Math.min(config.getChartMaxLimit(), limit));

        return fetchBars(normalized, tf, capped).thenApply(bars -> {
            if (!renderer.hasEnoughData(bars)) return null;
            List<String> lines = renderer.render(normalized, tf, bars);
            return lines.isEmpty() ? null : lines;
        }).exceptionally(error -> {
            logger.fine("[MineStock] 图表数据获取失败 " + normalized + ": " + error.getMessage());
            return null;
        });
    }

    /**
     * 股票优先，取数不足时回退 biquote。
     *
     * <p><b>这个回退是必需的</b>：{@link dev.onelili.mstock.stockio.UsStockApi} 的代码正则
     * {@code ^[A-Za-z]{1,10}$} 会匹配 {@code EURUSD}、{@code XAUUSD}、{@code BTCUSD}
     * 这类外汇 / CFD 代码，因此它们会先走股票源并失败 —— 必须再交给 biquote。
     *
     * <p><b>注意：这不是持仓估值可以照搬的策略。</b> 图表失败只是少一张图；
     * 而持仓估值若同样"失败就换源"，会让开仓价与平仓价来自两套不同报价，
     * 凭空制造盈亏并触发错误的强平。持仓价格走的是
     * {@code MineStock#symbolPrice} 的<b>元数据门控</b>回落，不要参照本方法。
     */
    private CompletableFuture<List<KLinePoint>> fetchBars(String symbol, String interval, int limit) {
        if (stockApi != null && !stockApi.isSupported(symbol)) {
            return forexBars(symbol, interval, limit);
        }
        if (stockApi == null) return forexBars(symbol, interval, limit);

        // 非模拟源（中/港/美）的接口只接受"天数"，由实现方忽略周期并把下面这个 window
        // 当 days 用 —— 与旧行为逐字一致；模拟盘则真正按 interval 聚合。
        int window = Math.max(limit, 30);
        return stockApi.fetchKLine(symbol, interval, window)
                .thenApply(points -> tail(points, limit))
                .exceptionally(error -> null)
                .thenCompose(points -> {
                    if (points != null && points.size() >= ChatChartRenderer.MIN_BARS) {
                        return CompletableFuture.completedFuture(points);
                    }
                    return forexBars(symbol, interval, limit);
                });
    }

    private CompletableFuture<List<KLinePoint>> forexBars(String symbol, String interval, int limit) {
        ForexProvider provider = forexProvider;
        if (provider == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("外汇行情源未就绪"));
        }
        return provider.getOHLC(symbol, interval, limit);
    }

    private static List<KLinePoint> tail(List<KLinePoint> points, int limit) {
        if (points == null) return null;
        if (points.size() <= limit) return points;
        return List.copyOf(points.subList(points.size() - limit, points.size()));
    }

    /** 供 GUI 使用的简化入口：{@code /mstock chart <symbol>} 的等价调用。 */
    public CompletableFuture<List<String>> chartWithDefaults(String symbol) {
        return chart(symbol, config.getChartDefaultInterval(), config.getChartDefaultLimit());
    }

    /** 供 UI 展示的默认值。 */
    public String defaultInterval() {
        return config.getChartDefaultInterval();
    }

    public int defaultLimit() {
        return config.getChartDefaultLimit();
    }

    public int maxLimit() {
        return config.getChartMaxLimit();
    }

    @Override
    public void close() {
        cooldowns.clear();
    }

    /**
     * 把渲染结果发到聊天框。
     *
     * <p>渲染完成的回调在异步线程上，必须切回玩家所在线程再调用 Bukkit API。
     * 数据不足或接口失败时只发一条简短错误消息，不会输出空图表。
     *
     * @param player  接收图表的玩家
     * @param pending 渲染任务
     * @param onError 失败时在玩家线程执行的动作
     */
    public void send(Player player, CompletableFuture<List<String>> pending, Runnable onError) {
        send(player, pending, onError, null);
    }

    /**
     * 同上，并在图表末尾追加一条额外提示（{@code hintKey} 为 {@code null} 时不追加）。
     *
     * <p>{@code hintKv} 是 lang 占位符的 key/value 交替串。提示由本方法在<b>玩家线程</b>
     * 上发送，因此调用方不需要自己处理线程切换。
     */
    public void send(Player player, CompletableFuture<List<String>> pending, Runnable onError,
                     String hintKey, String... hintKv) {
        UUID playerUuid = player.getUniqueId();
        pending.thenAccept(lines -> runOnPlayer(playerUuid, () -> {
            Player current = plugin.getServer().getPlayer(playerUuid);
            if (current == null || !current.isOnline()) return;
            if (lines == null || lines.isEmpty()) {
                onError.run();
                return;
            }
            for (String line : lines) {
                current.sendMessage(LangUtil.parse(line));
            }
            LangUtil lang = plugin.getLang();
            if (lang == null) return;
            current.sendMessage(lang.getNoPrefix("chart-hint"));
            if (hintKey != null) {
                current.sendMessage(lang.getNoPrefix(hintKey, hintKv));
            }
        }));
    }

    /** 切回玩家线程；插件已禁用时静默丢弃。 */
    private void runOnPlayer(UUID playerUuid, Runnable task) {
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player == null) return;
        try {
            CompatScheduler.get().runOnEntity(plugin, player, () -> {
                if (plugin.isOperational()) task.run();
            });
        } catch (IllegalStateException ignored) {
            // 插件在调度与执行之间被禁用。
        }
    }
}
