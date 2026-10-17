package dev.onelili.mstock.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟盘骨架结构测试。
 *
 * <p>本类只验证"骨架可编译、可实例化、纯值类型自洽"，
 * <b>不验证定价算法</b> —— 算法断言由其余测试类覆盖。
 *
 * <p>刻意避开所有 {@code Bukkit.*} / {@code JavaPlugin} / {@code CompatScheduler.*}，
 * 保证离线可跑。
 */
class SimulationSkeletonTest {

    /** 枚举解析与 id 往返。 */
    @Test
    void boundaryParsesAliasesAndIds() {
        assertEquals(SimulationBoundary.HARD_CLAMP, SimulationBoundary.parse("clamp"));
        assertEquals(SimulationBoundary.HARD_CLAMP, SimulationBoundary.parse("HARD"));
        assertEquals(SimulationBoundary.BOUNCE, SimulationBoundary.parse("reflect"));
        assertEquals(SimulationBoundary.SOFT_REGRESSION, SimulationBoundary.parse("soft"));
        assertEquals(SimulationBoundary.SOFT_REGRESSION, SimulationBoundary.parse("regress"));
        assertEquals("soft_regression", SimulationBoundary.SOFT_REGRESSION.id());
        // 未识别一律 null，由调用方决定回落值（不抛异常）
        assertNull(SimulationBoundary.parse("nope"));
        assertNull(SimulationBoundary.parse(null));
    }

    /** 波动预设的数值与解析。 */
    @Test
    void volatilityPresetCarriesDocumentedNumbers() {
        assertEquals(0.0010, SimulationVolatilityPreset.NORMAL.sigma(), 1e-12);
        assertEquals(0.005, SimulationVolatilityPreset.NORMAL.jumpLambda(), 1e-12);
        assertEquals(0.5, SimulationVolatilityPreset.LOW.amplitude(), 1e-12);
        assertEquals(2.0, SimulationVolatilityPreset.HIGH.amplitude(), 1e-12);

        assertEquals(SimulationVolatilityPreset.LOW, SimulationVolatilityPreset.parse("LOW"));
        assertEquals(SimulationVolatilityPreset.NORMAL, SimulationVolatilityPreset.parse(" normal "));
        assertNull(SimulationVolatilityPreset.parse("wild"));
        assertEquals("high", SimulationVolatilityPreset.HIGH.id());
    }

    /** {@code SimulationBar.flat} 是平盘。 */
    @Test
    void flatBarHasFourEqualPrices() {
        SimulationBar bar = SimulationBar.flat(60_000L, 12.5);
        assertEquals(60_000L, bar.bucketStart());
        assertEquals(12.5, bar.open(), 0.0);
        assertEquals(12.5, bar.high(), 0.0);
        assertEquals(12.5, bar.low(), 0.0);
        assertEquals(12.5, bar.close(), 0.0);
    }

    /** 无覆盖指令的快照语义。 */
    @Test
    void directiveWithoutOverrideResolvesToAutoPrice() {
        SimulationDirective none = SimulationDirective.none();
        assertFalse(none.hasOverride());
        assertEquals(123.4, none.resolve(123.4, 100.0), 0.0);
    }

    /** 指令快照不别名股票：改股票不应影响已取出的 directive。 */
    @Test
    void directiveSnapshotDoesNotAliasStock() {
        SimulationStock stock = new SimulationStock("SIM001", "模拟股 01", "科技", 100.0, 7L);
        stock.setManualPrice(88.0);

        SimulationDirective snapshot = stock.directive();
        assertTrue(snapshot.hasOverride());

        stock.setManualPrice(null);
        assertTrue(snapshot.hasOverride());
        assertEquals(88.0, snapshot.resolve(1.0, 2.0), 0.0);
    }

    /** 构造器把 initPrice 同时作为四个价格字段的初值，并套用预设。 */
    @Test
    void stockConstructorSeedsPricesAndAppliesPreset() {
        SimulationStock stock = new SimulationStock("SIM002", "模拟股 02", "金融", 25.5, 11L);
        assertEquals(25.5, stock.getInitPrice(), 0.0);
        assertEquals(25.5, stock.getPrevClose(), 0.0);
        assertEquals(25.5, stock.getCurrentPrice(), 0.0);
        assertEquals(25.5, stock.getAutoPrice(), 0.0);
        assertEquals(25.5, stock.getAnchor(), 0.0);
        assertEquals(11L, stock.getSeed());
        assertEquals(SimulationVolatilityPreset.NORMAL, stock.getPreset());
        assertEquals(0.0010, stock.getSigma(), 1e-12);
        // 默认可交易
        assertTrue(stock.isTradable());
        assertFalse(stock.isSuspended());
    }

    /** {@code applyPreset} 覆写全部波动字段。 */
    @Test
    void applyPresetOverwritesVolatilityFields() {
        SimulationStock stock = new SimulationStock("SIM003", "模拟股 03", "能源", 88.0, 3L);
        stock.applyPreset(SimulationVolatilityPreset.HIGH);

        assertEquals(SimulationVolatilityPreset.HIGH, stock.getPreset());
        assertEquals(0.0030, stock.getSigma(), 1e-12);
        assertEquals(0.05, stock.getTheta(), 1e-12);
        assertEquals(0.020, stock.getJumpLambda(), 1e-12);
        assertEquals(0.040, stock.getJumpMagnitude(), 1e-12);
        assertEquals(2.0, stock.getAmplitude(), 1e-12);
    }

    /** 同种子重置后随机序列可复现（价格路径可复现的基础）。 */
    @Test
    void reseedRestoresTheSameRandomSequence() {
        SimulationStock stock = new SimulationStock("SIM004", "模拟股 04", "消费", 5.2, 1L);
        stock.reseed(42L);

        java.util.Random replay = new java.util.Random(42L);
        for (int i = 0; i < 100; i++) {
            assertEquals(replay.nextGaussian(), stock.random().nextGaussian(), 0.0);
        }
        assertEquals(42L, stock.getSeed());
    }

    /** 模拟现金持仓的 getter/setter 往返。 */
    @Test
    void holdingRoundTripsItsFields() {
        java.util.UUID player = java.util.UUID.randomUUID();
        SimulationHolding holding = new SimulationHolding(player, "SIM001", 10, 9.5, 10.0, 123L);

        assertEquals(player, holding.getPlayerUuid());
        assertEquals("SIM001", holding.getCode());
        assertEquals(10, holding.getAmount());

        holding.setAmount(20);
        holding.setAvgCost(9.0);
        holding.setLastPrice(11.0);
        holding.setLastFetched(456L);

        assertEquals(20, holding.getAmount());
        assertEquals(9.0, holding.getAvgCost(), 0.0);
        assertEquals(11.0, holding.getLastPrice(), 0.0);
        assertEquals(456L, holding.getLastFetched());
    }

    /** 模拟代码正则：与 A股/港股/美股三源正则互不匹配（交叉验证"不被抢注"）。 */
    @Test
    void simulationCodePatternDoesNotCollideWithExistingSources() {
        java.util.regex.Pattern china = java.util.regex.Pattern.compile("^\\d{6}$");
        java.util.regex.Pattern hk = java.util.regex.Pattern.compile("^\\d{1,5}$");
        java.util.regex.Pattern us = java.util.regex.Pattern.compile("^[A-Za-z]{1,10}$");

        assertNotNull(SimulationStockSource.CODE);
        assertTrue(SimulationStockSource.CODE.matcher("SIM001").matches());
        assertTrue(SimulationStockSource.CODE.matcher("SIM999").matches());
        // 真实股票代码不应命中模拟正则
        assertFalse(SimulationStockSource.CODE.matcher("600519").matches());
        assertFalse(SimulationStockSource.CODE.matcher("AAPL").matches());

        // 模拟代码不命中任何既有源的 supports 正则
        assertFalse(china.matcher("SIM001").matches());
        assertFalse(hk.matcher("SIM001").matches());
        assertFalse(us.matcher("SIM001").matches());
    }

    /** 默认宇宙的代码、板块、种子稳定性。 */
    @Test
    void defaultUniverseIsStableOrderedAndSeeded() {
        java.util.List<SimulationStock> universe = SimulationStockRegistry.defaultUniverse(10, 20260101L);
        assertEquals(10, universe.size());

        for (int i = 0; i < universe.size(); i++) {
            SimulationStock stock = universe.get(i);
            assertEquals(String.format("SIM%03d", i + 1), stock.getCode());
            assertEquals(20260101L + i, stock.getSeed());
            assertTrue(SimulationStockSource.CODE.matcher(stock.getCode()).matches());
            assertFalse(stock.getSector().isBlank());
        }
        assertEquals(10.00, universe.get(0).getInitPrice(), 1e-9);
        assertEquals(300.00, universe.get(7).getInitPrice(), 1e-9);

        // count 会被截断到模板长度（模板共 10 只）
        assertEquals(10, SimulationStockRegistry.defaultUniverse(99, 1L).size());
        assertEquals(0, SimulationStockRegistry.defaultUniverse(0, 1L).size());
    }

    /** {@code SimulationConfig} 在无 Bukkit 配置时给出文档化默认值。 */
    @Test
    void simulationConfigFallsBackToDocumentedDefaults() {
        SimulationConfig config = new SimulationConfig(null);
        assertTrue(config.enabled());
        assertEquals(1.0, config.tickSeconds(), 0.0);
        assertEquals(10, config.autoFillCount());
        assertEquals(20260101L, config.seedBase());
        assertEquals(10.0, config.defaultLimitPercent(), 0.0);
        assertEquals(SimulationVolatilityPreset.NORMAL, config.defaultPreset());
        assertEquals(SimulationBoundary.SOFT_REGRESSION, config.defaultBoundary());
        assertEquals(0.01, config.minPrice(), 0.0);
        assertEquals(1_000_000.0, config.maxPrice(), 0.0);
        assertEquals(512, config.klineCapacity());
        assertEquals(1.0, config.linkGroupWeight(), 0.0);
    }

    /** K 线环形缓冲的容量兜底。 */
    @Test
    void klineBufferFallsBackToDefaultCapacity() {
        SimulationKLineBuffer buffer = new SimulationKLineBuffer(0);
        assertEquals(0, buffer.size());
        assertEquals(java.util.List.of(), buffer.snapshot());
        assertEquals(java.util.List.of(), buffer.drainClosed());
        assertEquals(512, SimulationKLineBuffer.DEFAULT_CAPACITY);
    }
}
