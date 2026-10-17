package dev.onelili.mstock.forex;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.database.TradingRepository;
import dev.onelili.mstock.leverage.LeverageService;
import dev.onelili.mstock.leverage.SymbolPriceSource;
import dev.onelili.mstock.leverage.TradeOutcome;
import dev.onelili.mstock.market.ForexProvider;
import dev.onelili.mstock.model.ForexCategory;
import dev.onelili.mstock.model.ForexQuote;
import dev.onelili.mstock.model.MarketSymbol;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import dev.onelili.mstock.util.SymbolSearch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 外汇交易服务。
 *
 * <p>外汇复用 {@link LeverageService} 的杠杆引擎：买卖都开成一笔保证金仓位，
 * 默认杠杆取 {@code forex.default-leverage}（默认 30）。价格一律取 biquote 的 {@code mid}。
 *
 * <p>品种按 {@link ForexCategory} 分为"国家外汇"与"虚拟货币"两个独立板块，
 * 并支持关键词模糊搜索（{@link SymbolSearch}）。
 */
public class ForexService {

    private final MineStock plugin;
    /**
     * {@code /mstock reload} 会新建 provider 并关闭旧的，故非 final（见 {@link #reconfigure}）。
     *
     * <p><b>为什么必须可换</b>：{@link MineStock#reload()} 在末尾调 {@code oldProvider.close()}，
     * 那会把旧 provider 的 {@code HttpClient} {@code shutdownNow()}。若本字段还是 {@code final}，
     * 重载后 {@code getForexService()} 仍指向旧对象、攥着已关闭的客户端，
     * 于是每一次报价都抛 {@code biquote 行情客户端已关闭}。
     */
    private volatile ForexProvider provider;
    private final LeverageService leverageService;
    @SuppressWarnings("unused")
    private final TradingRepository repository;
    private final Logger logger;

    public ForexService(MineStock plugin, ForexProvider provider,
                        LeverageService leverageService, TradingRepository repository) {
        this.plugin = plugin;
        this.provider = provider;
        this.leverageService = leverageService;
        this.repository = repository;
        this.logger = plugin.getLogger();
    }

    /**
     * {@code /mstock reload} 收尾：把 provider 换成新实例。
     *
     * <p>必须与 {@code MineStock#reload()} 里 {@code forexProvider = newProvider}
     * <b>同批</b>调用 —— 旧 provider 在方法末尾就被 close 了。
     */
    public synchronized void reconfigure(ForexProvider provider) {
        if (provider == null) return;
        this.provider = provider;
    }

    /** 买入 = 开多。 */
    public CompletableFuture<TradeOutcome> buy(UUID playerUuid, String symbol, double quantity) {
        return trade(playerUuid, symbol, quantity, TradeDirection.LONG);
    }

    /** 卖出 = 开空。 */
    public CompletableFuture<TradeOutcome> sell(UUID playerUuid, String symbol, double quantity) {
        return trade(playerUuid, symbol, quantity, TradeDirection.SHORT);
    }

    private CompletableFuture<TradeOutcome> trade(UUID playerUuid, String symbol,
                                                  double quantity, TradeDirection direction) {
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-quantity"));
        }
        // forex.enabled: false 只关闭<b>下单</b>，行情 / 图表 / 搜索仍然可用。
        // 放在 Service 层是为了让指令与 GUI 走同一条判断，不会一边拦一边放。
        dev.onelili.mstock.config.MainConfig mainConfig = plugin.getMainConfig();
        if (mainConfig != null && !mainConfig.isForexEnabled()) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("forex-trade-disabled"));
        }
        String normalized = normalize(symbol);
        if (normalized == null) {
            return CompletableFuture.completedFuture(TradeOutcome.fail("trade-invalid-symbol"));
        }
        return quote(normalized).thenCompose(quote ->
                leverageService.open(playerUuid, quote.getMarket(), normalized, direction,
                        quantity, quote.getMid(), 0));
    }

    /** 实时报价，价格取 {@code mid}。 */
    public CompletableFuture<ForexQuote> quote(String symbol) {
        String normalized = normalize(symbol);
        if (normalized == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("非法的品种代码"));
        }
        return provider.getPrice(normalized);
    }

    /** 当前有实时报价的活跃品种（{@code liveOnly: true}）。 */
    public CompletableFuture<List<MarketSymbol>> activeSymbols() {
        return provider.getActiveSymbols();
    }

    /**
     * 批量实时报价。
     *
     * <p>GUI 行情页一次要展示 45 个品种，必须走批量端点：biquote 官方要求
     * "never one symbol per request"，而且几十次并发请求更容易触发 HTTP/2 GOAWAY。
     *
     * @return 以品种代码为键的报价；无报价的品种不会出现在结果里，也不会让整批失败
     */
    public CompletableFuture<Map<String, ForexQuote>> quotes(List<String> symbols) {
        return provider.getQuotes(symbols);
    }

    /**
     * 只取某个板块的活跃品种（{@link ForexCategory#FIAT} 或 {@link ForexCategory#CRYPTO}）。
     *
     * <p>每次都基于完整活跃列表过滤：板块归属由品种元数据推导，无需额外网络请求。
     */
    public CompletableFuture<List<MarketSymbol>> symbolsOf(ForexCategory category) {
        return activeSymbols().thenApply(symbols -> filterByCategory(symbols, category));
    }

    /**
     * 板块 + 关键词搜索，返回已排序的结果。
     *
     * @param category 板块；{@code null} 表示不限板块（两个板块一起搜）
     * @param keyword  关键词；空白表示不筛选
     * @param limit    返回上限；{@code <= 0} 表示不限制
     */
    public CompletableFuture<List<MarketSymbol>> search(ForexCategory category,
                                                       String keyword, int limit) {
        return activeSymbols().thenApply(symbols ->
                SymbolSearch.filter(filterByCategory(symbols, category), keyword, limit));
    }

    /** 各板块的品种数量，用于 GUI 标签展示（如"国家外汇 (312)"）。 */
    public CompletableFuture<Map<ForexCategory, Integer>> categoryCounts() {
        return activeSymbols().thenApply(symbols -> {
            Map<ForexCategory, Integer> counts = new LinkedHashMap<>();
            for (ForexCategory category : ForexCategory.all()) {
                counts.put(category, 0);
            }
            for (MarketSymbol symbol : symbols) {
                ForexCategory category = ForexCategory.of(symbol);
                // 股票 CFD 不属于这两个板块，不计数
                if (category != null) counts.merge(category, 1, Integer::sum);
            }
            return counts;
        });
    }

    /**
     * 同步版板块过滤，便于已在异步回调中的调用方直接复用。
     *
     * @param category 目标板块；{@code null} 表示"全部"（含股票 CFD）
     * @return 匹配的品种；股票 CFD 只会在 {@code category == null} 时出现
     */
    public static List<MarketSymbol> filterByCategory(List<MarketSymbol> symbols,
                                                      ForexCategory category) {
        if (symbols == null) return List.of();
        if (category == null) return new ArrayList<>(symbols);
        List<MarketSymbol> result = new ArrayList<>();
        for (MarketSymbol symbol : symbols) {
            if (ForexCategory.of(symbol) == category) result.add(symbol);
        }
        return result;
    }

    /** K 线，最早在前。 */
    public CompletableFuture<List<dev.onelili.mstock.model.KLinePoint>> ohlc(
            String symbol, String interval, int limit) {
        String normalized = normalize(symbol);
        if (normalized == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("非法的品种代码"));
        }
        return provider.getOHLC(normalized, interval, limit);
    }

    /**
     * 可作为持仓盈亏计算输入的实时价格源。
     *
     * <p>只负责外汇 / CFD；股票价格由 {@code MineStock} 的复合价格源路由到股票行情。
     * 持仓估值请一律使用 {@code plugin.priceSource()}（它按仓位的 {@code market} 路由），
     * <b>不要</b>用本方法 —— 它不区分市场。
     */
    public SymbolPriceSource priceSource() {
        return (market, symbol) -> provider.getPrice(symbol).thenApply(ForexQuote::getMid);
    }

    /** 该品种所属市场（取 biquote 的 {@code type} / {@code exchange} 元数据）。 */
    public CompletableFuture<MarketType> marketOf(String symbol) {
        return quote(symbol).thenApply(ForexQuote::getMarket);
    }

    static String normalize(String symbol) {
        if (symbol == null) return null;
        String value = symbol.strip().toUpperCase(java.util.Locale.ROOT);
        return value.isEmpty() ? null : value;
    }
}
