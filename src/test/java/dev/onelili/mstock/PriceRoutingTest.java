package dev.onelili.mstock;

import dev.onelili.mstock.model.ForexQuote;
import dev.onelili.mstock.model.MarketType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EQUITY 价格路由测试 —— 覆盖 {@code symbolPrice} 的<b>实际分支</b>，而不只是判据函数。
 *
 * <p>背景：{@code market=EQUITY} 的保证金仓位有两个来源 —— 真实股票（A 股 / 港股 / 美股）
 * 与 biquote 的股票 CFD（{@code type=Stock}，如 {@code MSFTUSDT}）。
 *
 * <p>核心风险：股票行情源取不到价时若<b>无条件</b>回落到 biquote，真实美股
 * （出厂配置 API Key 为空，必然失败）会拿到 biquote 的另一套报价 ——
 * 开仓价来自股票源、平仓估值来自 biquote，价差凭空制造盈亏，
 * 触发错误的强制平仓或凭空出金。因此回落必须由 <b>biquote 自己的元数据</b>门控：
 * 只有 biquote 也把该品种归为 {@code EQUITY} 时才采用。
 *
 * <p>这个门控与开仓时给仓位打 {@code EQUITY} 标记用的是同一判据，因此天然一致；
 * 不依赖 {@code USDT} 后缀这类命名约定。
 */
class PriceRoutingTest {

    private static ForexQuote equityQuote(double mid) {
        return new ForexQuote("MSFTUSDT", "Microsoft", mid, mid, mid, mid, mid, 0.0,
                "open", false, 0, MarketType.EQUITY);
    }

    private static ForexQuote forexQuote(double mid) {
        return new ForexQuote("AAPL", "Apple Inc", mid, mid, mid, mid, mid, 0.0,
                "open", false, 0, MarketType.FOREX);
    }

    // ── 主路径 ────────────────────────────────────────────────────────────────

    @Test
    void usesStockPriceWhenAvailable() {
        Double price = MineStock.routeEquity(
                () -> CompletableFuture.completedFuture(213.49),
                () -> CompletableFuture.completedFuture(equityQuote(999.0))).join();

        assertEquals(213.49, price, 1e-9, "股票源有价时必须用股票价，不得回落");
    }

    /** 股票 CFD：股票源失败，但 biquote 认它是股票 → 回落取其 mid。 */
    @Test
    void fallsBackWhenBiquoteClassifiesSymbolAsEquity() {
        AtomicBoolean forexUsed = new AtomicBoolean();

        Double price = MineStock.routeEquity(
                () -> CompletableFuture.failedFuture(new RuntimeException("unknown symbol")),
                () -> {
                    forexUsed.set(true);
                    return CompletableFuture.completedFuture(equityQuote(431.25));
                }).join();

        assertTrue(forexUsed.get(), "应尝试 biquote 兜底");
        assertEquals(431.25, price, 1e-9);
    }

    /**
     * 最关键的一条：真实股票遇上股票源故障时，<b>不得</b>采用 biquote 报价。
     *
     * <p>biquote 对 {@code AAPL} 确实返回有效 tick，但 {@code exchange} 为 {@code null}
     * → 归为 FOREX 而非 EQUITY。此时必须失败关闭，否则会用另一套报价给
     * 股票仓位估值甚至强平。
     */
    @Test
    void doesNotFallBackForRealStockWhenBiquoteSaysForex() {
        AtomicBoolean forexUsed = new AtomicBoolean();

        assertThrows(CompletionException.class, () ->
                MineStock.routeEquity(
                        () -> CompletableFuture.failedFuture(
                                new RuntimeException("Finnhub key not configured")),
                        () -> {
                            forexUsed.set(true);
                            // biquote 有价，但它自己把 AAPL 归为 FOREX
                            return CompletableFuture.completedFuture(forexQuote(344.195));
                        }).join());

        assertTrue(forexUsed.get(), "可以询问 biquote，但不得采用其结果");
    }

    /** biquote 报价为 null / 0 / NaN 时同样失败关闭。 */
    @Test
    void rejectsUnusableBiquoteQuotes() {
        for (double bad : new double[]{0.0, -1.0, Double.NaN}) {
            assertThrows(CompletionException.class, () ->
                    MineStock.routeEquity(
                            () -> CompletableFuture.failedFuture(new RuntimeException("down")),
                            () -> CompletableFuture.completedFuture(equityQuote(bad))).join(),
                    "biquote mid=" + bad + " 不可用，必须失败关闭");
        }
        assertThrows(CompletionException.class, () ->
                MineStock.routeEquity(
                        () -> CompletableFuture.failedFuture(new RuntimeException("down")),
                        () -> CompletableFuture.completedFuture(null)).join());
    }

    /** 每个分支都必须回落而不是让整笔交易失败。 */
    @Test
    void fallsBackWhenStockReturnsNonPositiveOrNull() {
        for (Double bad : new Double[]{0.0, -5.0, Double.NaN, null}) {
            Double price = MineStock.routeEquity(
                    () -> CompletableFuture.completedFuture(bad),
                    () -> CompletableFuture.completedFuture(equityQuote(100.0))).join();
            assertEquals(100.0, price, 1e-9, "股票价 " + bad + " 不可用，应回落");
        }
    }

    /** 两条路都不可用时异常必须冒出来（调用方据此提示"无法获取实时价格"）。 */
    @Test
    void propagatesWhenBothSourcesUnavailable() {
        assertThrows(CompletionException.class, () ->
                MineStock.routeEquity(
                        () -> CompletableFuture.failedFuture(new RuntimeException("stock down")),
                        () -> CompletableFuture.failedFuture(new RuntimeException("forex down")))
                        .join());
    }

    /**
     * 股票源抛异常（而非返回失败 future）时须被吃掉并尝试兜底；
     * 股票源整体未就绪（{@code null}）则失败关闭 —— 与
     * {@code symbolPrice} 在 {@code api == null} 时的处理一致。
     */
    @Test
    void handlesNullAndThrowingStockSource() {
        assertEquals(12.5, MineStock.routeEquity(
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> CompletableFuture.completedFuture(equityQuote(12.5))).join(), 1e-9);

        assertThrows(CompletionException.class, () ->
                MineStock.routeEquity(
                        null,
                        () -> CompletableFuture.completedFuture(equityQuote(77.5))).join(),
                "股票源未就绪时不得改用 biquote 报价");
    }

    /** 兜底源本身不可用（null）时必须失败关闭，不能 NPE。 */
    @Test
    void handlesNullForexSource() {
        assertThrows(CompletionException.class, () ->
                MineStock.routeEquity(
                        () -> CompletableFuture.failedFuture(new RuntimeException("down")),
                        null).join());

        assertEquals(50.0, MineStock.routeEquity(
                () -> CompletableFuture.completedFuture(50.0), null).join(), 1e-9,
                "股票源有价时无需兜底源");
    }

    // ── 价格可用性判据 ────────────────────────────────────────────────────────

    @Test
    void acceptsOnlyFinitePositivePrices() {
        assertTrue(MineStock.isUsablePrice(213.49));
        assertTrue(MineStock.isUsablePrice(0.00001));
        assertTrue(MineStock.isUsablePrice(Double.MAX_VALUE));

        assertFalse(MineStock.isUsablePrice(0.0));
        assertFalse(MineStock.isUsablePrice(-5.0));
        assertFalse(MineStock.isUsablePrice(Double.NaN));
        assertFalse(MineStock.isUsablePrice(Double.POSITIVE_INFINITY));
        assertFalse(MineStock.isUsablePrice(Double.NEGATIVE_INFINITY));
        assertFalse(MineStock.isUsablePrice(null));
    }
}

