package dev.onelili.mstock.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工覆盖指令的语义测试。
 *
 * <p>{@link SimulationService} 依赖 {@code MineStock plugin}，无法离线单测，
 * 因此这里直接驱动 {@link SimulationDirective} + {@link SimulationStock} + {@link SimulationPriceEngine}
 * 来覆盖编排层要保证的不变量。
 */
class SimulationOverrideTest {

    private static SimulationStock stock(double initPrice, long seed) {
        return new SimulationStock("SIM001", "模拟股 01", "科技", initPrice, seed);
    }

    /** 无覆盖 ⇒ 直通自动价。 */
    @Test
    void noDirectiveResolvesToAutoPrice() {
        SimulationDirective none = SimulationDirective.none();
        assertFalse(none.hasOverride());
        assertFalse(none.slopeActive(System.currentTimeMillis()));
        assertEquals(123.4, none.resolve(123.4, 100.0), 0.0);
    }

    /** 定值覆盖优先级最高（压过目标涨幅）。 */
    @Test
    void manualPriceWinsOverEverything() {
        SimulationDirective directive = new SimulationDirective(88.0, 5.0, 0.0, 0L, false);
        assertTrue(directive.hasOverride());
        assertEquals(88.0, directive.resolve(100.0, 100.0), 0.0);

        // 走 stock 的互斥写时永远只有一个命中；这里直接用 record 构造两者共存的极端输入，
        // 断言 resolve 的优先级分支（定值 > 目标涨幅 > 自动价）不被破坏
        SimulationStock s = stock(100.0, 1L);
        s.setManualTargetPercent(5.0);
        s.setManualPrice(88.0);
        assertEquals(88.0, s.directive().resolve(s.getAutoPrice(), s.getPrevClose()), 0.0);
    }

    /** 目标涨幅相对昨收计算。 */
    @Test
    void manualTargetPercentIsRelativeToPrevClose() {
        SimulationStock s = stock(100.0, 2L);
        s.setPrevClose(100.0);
        s.setManualTargetPercent(5.0);

        assertEquals(105.0, s.directive().resolve(400.0, s.getPrevClose()), 1e-12);
    }

    /** 负的目标涨幅同样支持。 */
    @Test
    void negativeTargetPercentIsSupported() {
        SimulationStock s = stock(50.0, 3L);
        s.setPrevClose(50.0);
        s.setManualTargetPercent(-10.0);

        assertEquals(45.0, s.directive().resolve(s.getAutoPrice(), s.getPrevClose()), 1e-12);
    }

    /** 目标涨幅与自动价无关。 */
    @Test
    void targetIsIndependentOfAutoPrice() {
        SimulationStock s = stock(100.0, 4L);
        s.setPrevClose(100.0);
        s.setManualTargetPercent(5.0);

        double first = s.directive().resolve(100.0, s.getPrevClose());
        double second = s.directive().resolve(1000.0, s.getPrevClose());
        assertEquals(105.0, first, 1e-12);
        assertEquals(first, second, 0.0);
    }

    /** 两种覆盖互斥写：设其一必清另一个。 */
    @Test
    void settingPriceClearsTargetAndViceVersa() {
        SimulationStock s = stock(100.0, 5L);

        s.setManualPrice(88.0);
        assertEquals(88.0, s.getManualPrice(), 0.0);
        assertNull(s.getManualTargetPercent(), "设 manualPrice 必须清 manualTargetPercent");

        s.setManualTargetPercent(5.0);
        assertEquals(5.0, s.getManualTargetPercent(), 0.0);
        assertNull(s.getManualPrice(), "设 manualTargetPercent 必须清 manualPrice");

        // 置 null 是"清除"，不应连带清掉另一个的既有值
        s.setManualPrice(77.0);
        s.setManualTargetPercent(null);
        assertEquals(77.0, s.getManualPrice(), 0.0);

        // 清空后回到无覆盖
        s.setManualPrice(null);
        assertFalse(s.directive().hasOverride());
    }

    /** {@code resume} 的语义：价格指令全清，但 suspended / tradable 不被改动。 */
    @Test
    void resumeClearsAllOverrides() {
        SimulationStock s = stock(100.0, 6L);
        s.setSuspended(true);
        s.setTradable(false);

        s.setManualPrice(88.0);
        s.setSlopePerMs(0.5);
        s.setSlopeUntil(System.currentTimeMillis() + 60_000L);
        s.setPaused(true);

        // resume = 清 manual_price / manual_target_pct / slope_per_ms / slope_until / paused=false
        s.setManualPrice(null);
        s.setManualTargetPercent(null);
        s.setSlopePerMs(0.0);
        s.setSlopeUntil(0L);
        s.setPaused(false);

        assertNull(s.getManualPrice());
        assertNull(s.getManualTargetPercent());
        assertEquals(0.0, s.getSlopePerMs(), 0.0);
        assertEquals(0L, s.getSlopeUntil());
        assertFalse(s.isPaused());
        assertTrue(s.directive().expired(System.currentTimeMillis()));
        // 治安开关不被 resume 复活
        assertTrue(s.isSuspended());
        assertFalse(s.isTradable());
    }

    /** 斜率到期后自动失效，{@code resolve} 只反映 manual / auto。 */
    @Test
    void slopeExpiresAutomatically() {
        long now = System.currentTimeMillis();
        SimulationDirective expired = new SimulationDirective(null, null, 0.5, now - 1L, false);
        assertFalse(expired.slopeActive(now));
        assertEquals(123.0, expired.resolve(123.0, 100.0), 0.0);
        assertTrue(expired.expired(now), "斜率过期且无其它干预 ⇒ expired");
    }

    /** 斜率仅在到期前生效；{@code slopePerMs == 0} 时恒不生效。 */
    @Test
    void slopeActiveOnlyBeforeDeadline() {
        long now = System.currentTimeMillis();
        assertTrue(new SimulationDirective(null, null, 0.5, now + 60_000L, false).slopeActive(now));
        assertFalse(new SimulationDirective(null, null, 0.0, now + 60_000L, false).slopeActive(now));
        // 刚好到期：now == slopeUntil 不算生效（严格小于）
        assertFalse(new SimulationDirective(null, null, 0.5, now, false).slopeActive(now));
    }

    /** 指令是快照，不别名股票。 */
    @Test
    void directiveSnapshotDoesNotAliasStock() {
        SimulationStock s = stock(100.0, 7L);
        s.setManualPrice(88.0);

        SimulationDirective snapshot = s.directive();
        assertTrue(snapshot.hasOverride());

        s.setManualPrice(null);
        assertTrue(snapshot.hasOverride(), "快照不应随股票变化");
        assertEquals(88.0, snapshot.resolve(1.0, 2.0), 0.0);
    }

    /** 暂停 ≠ 价格覆盖。 */
    @Test
    void pausedIsIndependentOfOverride() {
        SimulationStock s = stock(100.0, 8L);
        s.setPaused(true);

        SimulationDirective directive = s.directive();
        assertTrue(directive.paused());
        assertFalse(directive.hasOverride(), "暂停推进不等于价格覆盖");
        assertFalse(directive.expired(System.currentTimeMillis()), "暂停期间不算 expired");
        assertEquals(100.0, directive.resolve(100.0, 100.0), 0.0);
    }

    /** 关键设计不变量：有 manualPrice 时 autoPrice 仍由引擎推进，只有 currentPrice 被覆盖。 */
    @Test
    void autoPriceKeepsAdvancingWhileOverrideIsActive() {
        SimulationStock s = stock(100.0, 9L);
        s.setPrevClose(100.0);
        s.setManualPrice(88.0);

        SimulationPriceEngine.Params p = new SimulationPriceEngine.Params(
                0.001, 0.0, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0);

        double before = s.getAutoPrice();
        for (int i = 0; i < 50; i++) {
            // 编排层语义：先推进 autoPrice，再用 directive 解析出 currentPrice
            s.setAutoPrice(SimulationPriceEngine.advance(s.getAutoPrice(), p, 0.0, 0.0, 0.0));
            s.setCurrentPrice(s.directive().resolve(s.getAutoPrice(), s.getPrevClose()));
        }

        assertTrue(s.getAutoPrice() > before, "覆盖生效期间 autoPrice 仍必须推进");
        assertEquals(88.0, s.getCurrentPrice(), 0.0, "currentPrice 恒被 manualPrice 覆盖");
    }
}
