package dev.onelili.mstock.stock;

import dev.onelili.mstock.config.LeverageConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 股票杠杆配置测试：股票与外汇的上限、默认值互不影响。 */
class StockLeverageConfigTest {

    /** 默认值：外汇 max 100 / default 30；股票 max 10 / default 1。 */
    private static LeverageConfig config() {
        return new LeverageConfig(null) {
            @Override public int getMaxLeverage() { return 100; }
            @Override public int getDefaultLeverage() { return 10; }
            @Override public int getForexDefaultLeverage() { return 30; }
            @Override public int getStockMaxLeverage() { return 10; }
            @Override public int getStockDefaultLeverage() { return 1; }
            @Override public boolean isStockLeverageEnabled() { return true; }
        };
    }

    @Test
    void stockLeverageIsIndependentFromForex() {
        LeverageConfig config = config();

        // 外汇与股票的默认值、上限互不串扰
        assertEquals(100, config.getMaxLeverage());
        assertEquals(10, config.getStockMaxLeverage());
        assertEquals(10, config.getDefaultLeverage());
        assertEquals(1, config.getStockDefaultLeverage());
        // 外汇制度取 forex.default-leverage(30)，与 leverage.default-leverage(10) 区分开
        assertEquals(30, config.getForexDefaultLeverage());
    }

    @Test
    void stockClampUsesItsOwnMax() {
        LeverageConfig config = config();

        assertEquals(1, config.clampStock(0));
        assertEquals(1, config.clampStock(-5));
        assertEquals(10, config.clampStock(50));
        assertEquals(10, config.clampStock(10));
        assertEquals(5, config.clampStock(5));
    }

    @Test
    void stockValidationUsesItsOwnRange() {
        LeverageConfig config = config();

        assertTrue(config.isValidStockLeverage(1));
        assertTrue(config.isValidStockLeverage(10));
        assertFalse(config.isValidStockLeverage(11));
        assertFalse(config.isValidStockLeverage(0));
        // 11 超出股票上限，但在外汇范围内 —— 必须用股票的校验
        assertTrue(config.isValidLeverage(11));
        assertFalse(config.isValidStockLeverage(11));
    }

    @Test
    void stockLeverageOneMeansCashPurchase() {
        // 默认股票杠杆为 1：买入 = 全额现金，不产生保证金仓位。
        // 这是向后兼容的关键：老玩家不调设置，行为与历史版本完全一致。
        assertEquals(1, config().getStockDefaultLeverage());
    }

    @Test
    void codeNormalization() {
        assertEquals("600519", StockLeverageService.normalize("600519"));
        assertEquals("AAPL", StockLeverageService.normalize(" aapl "));
        assertNull(StockLeverageService.normalize(null));
        assertNull(StockLeverageService.normalize("   "));
        assertNull(StockLeverageService.normalize(""));
    }

    // ── 制度路由（Policy）：修复"股票买 100x 通过外汇上限校验"的越权 ──────────────

    /**
     * {@code accepts(STOCK, ...)} 必须用股票自己的上限。
     *
     * <p>回归测试：调用方曾经直接传 {@code leverage} 给 LeverageService，
     * 那里校验的是外汇区间 [1,100]，于是 {@code /mstock buy AAPL 1 100}
     * 会开出 100 倍股票仓位 —— 远超 {@code leverage.stock.max-leverage: 10}。
     */
    @Test
    void stockPolicyRejectsLeverageAboveStockMax() {
        LeverageConfig config = config();

        assertTrue(config.accepts(LeverageConfig.Policy.STOCK, 1));
        assertTrue(config.accepts(LeverageConfig.Policy.STOCK, 10));
        // 11 在外汇区间内，但股票上限是 10 —— 必须拒绝
        assertFalse(config.accepts(LeverageConfig.Policy.STOCK, 11));
        assertFalse(config.accepts(LeverageConfig.Policy.STOCK, 100));

        // 同一批数字在外汇制度下必须仍然合法，证明两套制度确实分开
        assertTrue(config.accepts(LeverageConfig.Policy.FOREX, 11));
        assertTrue(config.accepts(LeverageConfig.Policy.FOREX, 100));
    }

    @Test
    void policyResolvesItsOwnMaxAndDefault() {
        LeverageConfig config = config();

        assertEquals(100, config.maxLeverage(LeverageConfig.Policy.FOREX));
        assertEquals(10, config.maxLeverage(LeverageConfig.Policy.STOCK));
        assertEquals(30, config.defaultLeverage(LeverageConfig.Policy.FOREX));
        assertEquals(1, config.defaultLeverage(LeverageConfig.Policy.STOCK));
    }

    @Test
    void forexPolicyStillAcceptsLeverageOne() {
        assertTrue(config().accepts(LeverageConfig.Policy.FOREX, 1));
        assertFalse(config().accepts(LeverageConfig.Policy.FOREX, 0));
        assertFalse(config().accepts(LeverageConfig.Policy.FOREX, 101));
    }

    /**
     * {@code leverage.stock.enabled: false} 必须让股票杠杆真的失效。
     *
     * <p>回归测试：这个配置项此前只被 getter 与测试引用，插件里没有任何地方读它，
     * 因此 config.yml 里写明的"关闭后股票只能全额现金买入"从未发生过。
     */
    @Test
    void disabledStockLeverageOnlyAcceptsCashPath() {
        LeverageConfig disabled = new LeverageConfig(null) {
            @Override public int getMaxLeverage() { return 100; }
            @Override public int getStockMaxLeverage() { return 10; }
            @Override public boolean isStockLeverageEnabled() { return false; }
        };

        assertTrue(disabled.accepts(LeverageConfig.Policy.STOCK, 1),
                "1x = 不借钱，关闭杠杆后仍须允许（等价现金买入）");
        assertFalse(disabled.accepts(LeverageConfig.Policy.STOCK, 2),
                "关闭后任何 >1 的股票杠杆都必须被拒绝");
        assertFalse(disabled.accepts(LeverageConfig.Policy.STOCK, 10));

        // 关闭股票杠杆不影响外汇
        assertTrue(disabled.accepts(LeverageConfig.Policy.FOREX, 50));
    }

    /**
     * 外汇默认杠杆必须来自 {@code forex.default-leverage}，而不是 {@code leverage.default-leverage}。
     *
     * <p>回归测试：config.yml 一直写着 {@code forex.default-leverage: 30}
     * （"外汇市场默认杠杆倍数"），但 {@code getForexDefaultLeverage()} 没有任何调用方，
     * 新人外汇实际拿到的是 {@code leverage.default-leverage: 10} —— 文档与行为矛盾。
     */
    @Test
    void forexDefaultComesFromForexKeyNotLeverageKey() {
        LeverageConfig config = new LeverageConfig(null) {
            @Override public int getDefaultLeverage() { return 10; }
            @Override public int getForexDefaultLeverage() { return 30; }
            @Override public int getStockDefaultLeverage() { return 1; }
        };

        assertEquals(30, config.defaultLeverage(LeverageConfig.Policy.FOREX),
                "外汇制度必须取 forex.default-leverage");
        assertEquals(10, config.getDefaultLeverage(),
                "leverage.default-leverage 只是兜底值，不该被当成外汇默认值");
        assertEquals(1, config.defaultLeverage(LeverageConfig.Policy.STOCK));
    }

    // ── 制度由市场推导：堵住"外汇页买股票 CFD 绕过股票上限" ─────────────────────

    /**
     * {@code Policy.of(EQUITY)} 必须是 STOCK。
     *
     * <p>回归测试：biquote 上约 186 个"股票 CFD"（{@code MSFTUSDT}、{@code SPYUSDT} …）
     * 元数据是 {@code type=Stock}，因此在外汇界面成交时会以
     * {@code market=EQUITY} 落库。此前 {@code LeverageService.open} 的 7 参重载
     * <b>硬编码</b> {@code Policy.FOREX}，于是：
     * <ul>
     *   <li>上限按外汇的 100 校验，而不是股票的 10；</li>
     *   <li>默认值取外汇的 30，而不是股票的 1；</li>
     *   <li>{@code leverage.stock.enabled:false} 完全失效 —— 这条路径根本不看它。</li>
     * </ul>
     * 净效果：{@code /mstock buy MSFT} 限 10x，而买 {@code MSFTUSDT} 能开 100x。
     */
    @Test
    void equityMarketResolvesToStockPolicy() {
        assertEquals(LeverageConfig.Policy.STOCK,
                LeverageConfig.Policy.of(dev.onelili.mstock.model.MarketType.EQUITY),
                "股票市场（含股票 CFD）必须受股票制度约束");
    }

    /** 外汇 / 贵金属 / 加密 / 指数都走外汇制度。 */
    @Test
    void nonEquityMarketsResolveToForexPolicy() {
        for (var market : new dev.onelili.mstock.model.MarketType[]{
                dev.onelili.mstock.model.MarketType.FOREX,
                dev.onelili.mstock.model.MarketType.COMMODITY,
                dev.onelili.mstock.model.MarketType.CRYPTO,
                dev.onelili.mstock.model.MarketType.INDEX}) {
            assertEquals(LeverageConfig.Policy.FOREX, LeverageConfig.Policy.of(market),
                    market + " 应走外汇制度");
        }
    }

    /**
     * 把 {@code Policy.of} 接到 {@code accepts} 上，验证股票 CFD 的越权请求会被拒。
     *
     * <p>等价于：{@code /mstock forex buy MSFTUSDT 1} 时若玩家外汇杠杆是 50x，
     * 该请求必须以股票制度（上限 10）被拒绝，而不是放行成 50x 微软敞口。
     */
    @Test
    void stockCfdCannotBypassStockLimitThroughForexMarket() {
        LeverageConfig config = config();
        LeverageConfig.Policy derived =
                LeverageConfig.Policy.of(dev.onelili.mstock.model.MarketType.EQUITY);

        // 50x 在外汇制度下合法，但股票 CFD 必须按股票制度拒绝
        assertTrue(config.accepts(LeverageConfig.Policy.FOREX, 50));
        assertFalse(config.accepts(derived, 50),
                "股票 CFD 不能借道外汇市场开到 50x");
        assertFalse(config.accepts(derived, 11));
        assertTrue(config.accepts(derived, 10), "10x 是股票上限，应当允许");
    }

    /** 关闭股票杠杆后，即使通过市场推导也必须只能走 1x。 */
    @Test
    void disabledStockLeverageAlsoBlocksStockCfds() {
        LeverageConfig disabled = new LeverageConfig(null) {
            @Override public int getMaxLeverage() { return 100; }
            @Override public int getStockMaxLeverage() { return 10; }
            @Override public boolean isStockLeverageEnabled() { return false; }
        };
        LeverageConfig.Policy derived =
                LeverageConfig.Policy.of(dev.onelili.mstock.model.MarketType.EQUITY);

        assertFalse(disabled.accepts(derived, 30),
                "关闭股票杠杆后，MSFTUSDT 也不能用外汇默认的 30x");
        assertTrue(disabled.accepts(derived, 1));
    }
}
