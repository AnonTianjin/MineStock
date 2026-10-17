package dev.onelili.mstock.simulation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimulationPriceEngine} 的纯函数测试。
 *
 * <p>全部走<b>显式参数</b>而非波动预设，保证断言可复现。
 * 不碰任何 {@code Bukkit.*} / {@code JavaPlugin} / {@code CompatScheduler.*}。
 */
class SimulationPriceEngineTest {

    /** 构造一次 tick 的参数；默认只留 {@code amplitude = 1}，其余项为 0（价格不动）。 */
    private static SimulationPriceEngine.Params params(double mu, double sigma, double theta, double anchor,
                                                       double jumpLambda, double jumpMagnitude, double amplitude,
                                                       double slopePerMs, double dtSeconds) {
        return new SimulationPriceEngine.Params(mu, sigma, theta, anchor, jumpLambda, jumpMagnitude,
                amplitude, slopePerMs, dtSeconds);
    }

    /** 同种子 ⇒ 同一价格路径（逐元素完全相等，容差 0）。 */
    @Test
    void sameSeedProducesIdenticalPath() {
        SimulationStock a = new SimulationStock("SIM001", "模拟股 01", "科技", 100.0, 20260101L);
        SimulationStock b = new SimulationStock("SIM001", "模拟股 01", "科技", 100.0, 20260101L);
        SimulationPriceEngine.Params p = params(0.0, 0.01, 0.05, 100.0, 0.005, 0.02, 1.0, 0.0, 1.0);

        for (int i = 0; i < 200; i++) {
            double zA = a.random().nextGaussian();
            double u1A = a.random().nextDouble();
            double u2A = a.random().nextDouble();

            double zB = b.random().nextGaussian();
            double u1B = b.random().nextDouble();
            double u2B = b.random().nextDouble();

            a.setAutoPrice(SimulationPriceEngine.advance(a.getAutoPrice(), p, zA, u1A, u2A));
            b.setAutoPrice(SimulationPriceEngine.advance(b.getAutoPrice(), p, zB, u1B, u2B));

            assertEquals(a.getAutoPrice(), b.getAutoPrice(), 0.0);
        }
        // 非平凡：200 次推进后价格确实变了（否则"逐元素相等"是废话）
        assertNotEquals(100.0, a.getAutoPrice(), 1e-9);
    }

    /** 不同种子 ⇒ 路径分离。 */
    @Test
    void differentSeedsDiverge() {
        SimulationStock a = new SimulationStock("SIM001", "模拟股 01", "科技", 100.0, 1L);
        SimulationStock b = new SimulationStock("SIM001", "模拟股 01", "科技", 100.0, 2L);
        SimulationPriceEngine.Params p = params(0.0, 0.01, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0);

        boolean diverged = false;
        for (int i = 0; i < 200 && !diverged; i++) {
            a.setAutoPrice(SimulationPriceEngine.advance(a.getAutoPrice(), p,
                    a.random().nextGaussian(), a.random().nextDouble(), a.random().nextDouble()));
            b.setAutoPrice(SimulationPriceEngine.advance(b.getAutoPrice(), p,
                    b.random().nextGaussian(), b.random().nextDouble(), b.random().nextDouble()));
            diverged = Math.abs(a.getAutoPrice() - b.getAutoPrice()) > 1e-9;
        }
        assertTrue(diverged, "seed 1 与 seed 2 应在 200 步内产生不同路径");
    }

    /** 零波动 + 零斜率 ⇒ 价格恒定（50 次）。 */
    @Test
    void zeroVolatilityKeepsPriceConstant() {
        SimulationPriceEngine.Params p = params(0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0);
        double price = 100.0;
        Random rnd = new Random(7L);
        for (int i = 0; i < 50; i++) {
            price = SimulationPriceEngine.advance(price, p, rnd.nextGaussian(), rnd.nextDouble(), rnd.nextDouble());
            assertEquals(100.0, price, 1e-12);
        }
    }

    /** 纯漂移：{@code mu = 0.001, dt = 1} 连续 200 次 ⇒ {@code init * exp(0.2)}。 */
    @Test
    void deterministicDriftWithPositiveMu() {
        SimulationPriceEngine.Params p = params(0.001, 0.0, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0);
        double price = 100.0;
        double previous = price;
        for (int i = 0; i < 200; i++) {
            price = SimulationPriceEngine.advance(price, p, 0.0, 0.0, 0.0);
            assertTrue(price > previous, "正漂移下价格必须单调递增（第 " + i + " 步）");
            previous = price;
        }
        assertEquals(100.0 * Math.exp(0.2), price, 1e-6);
    }

    /** 均值回归把价格拉回锚点：200 → 100，100 次后误差远小于初值的 1%。 */
    @Test
    void meanReversionPullsPriceTowardAnchor() {
        SimulationPriceEngine.Params p = params(0.0, 0.0, 0.5, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0);
        double price = 200.0;
        for (int i = 0; i < 100; i++) {
            price = SimulationPriceEngine.advance(price, p, 0.0, 0.0, 0.0);
        }
        assertTrue(Math.abs(price - 100.0) < 1.0, "100 次后与锚点的距离应小于初值偏离的 1%，实际=" + price);
    }

    /** 均值回归项的符号：低于锚点为正、高于锚点为负。 */
    @Test
    void meanReversionSignIsCorrect() {
        assertTrue(SimulationPriceEngine.ou(0.5, 100.0, 50.0, 1.0) > 0.0, "低于锚点应向上回归");
        assertTrue(SimulationPriceEngine.ou(0.5, 100.0, 200.0, 1.0) < 0.0, "高于锚点应向下回归");
        assertEquals(0.0, SimulationPriceEngine.ou(0.5, 100.0, 100.0, 1.0), 1e-15);
    }

    /** 跳变只在 {@code u1 < lambda*dt} 时发生。 */
    @Test
    void jumpFiresOnlyWhenUniformBelowLambdaTimesDt() {
        assertEquals(0.1, SimulationPriceEngine.jump(0.5, 0.1, 1.0, 0.49, 1.0), 0.0);
        assertEquals(0.0, SimulationPriceEngine.jump(0.5, 0.1, 1.0, 0.51, 1.0), 0.0);
        // 边界：u1 == lambda*dt 不触发（严格小于）
        assertEquals(0.0, SimulationPriceEngine.jump(0.5, 0.1, 1.0, 0.5, 1.0), 0.0);
        // lambda == 0 恒不触发
        assertEquals(0.0, SimulationPriceEngine.jump(0.0, 0.1, 1.0, 0.0, 1.0), 0.0);
    }

    /** 跳变关于 0 对称：{@code magnitude*(2*u2-1)}。 */
    @Test
    void jumpIsSymmetricAroundZero() {
        assertEquals(-0.1, SimulationPriceEngine.jump(0.5, 0.1, 1.0, 0.1, 0.0), 0.0);
        assertEquals(0.1, SimulationPriceEngine.jump(0.5, 0.1, 1.0, 0.1, 1.0), 0.0);
        assertEquals(0.0, SimulationPriceEngine.jump(0.5, 0.1, 1.0, 0.1, 0.5), 0.0);
    }

    /** 联动相关化精确匹配公式，且 rho 被夹取。 */
    @Test
    void correlatedZMatchesFormula() {
        // rho == 0 ⇒ 逐字等于独立抽样
        assertEquals(-1.0, SimulationPriceEngine.correlatedZ(0.0, 2.0, -1.0), 0.0);
        // rho = 0.6, g = 2.0, z = -1.0
        assertEquals(0.6 * 2.0 + Math.sqrt(1.0 - 0.36) * (-1.0),
                SimulationPriceEngine.correlatedZ(0.6, 2.0, -1.0), 1e-15);
        // rho = 1.0 会被夹到 0.999，公式仍成立（不产生 NaN）
        assertEquals(0.999 * 2.0 + Math.sqrt(1.0 - 0.999 * 0.999) * (-1.0),
                SimulationPriceEngine.correlatedZ(1.0, 2.0, -1.0), 1e-15);
        // 夹取后主导项仍接近 groupZ（z 较小时 ≈ g）
        assertEquals(0.999 * 2.0, SimulationPriceEngine.correlatedZ(1.0, 2.0, 0.0), 1e-15);
    }

    /** 负相关 ⇒ 反向联动。 */
    @Test
    void negativeRhoInvertsDirection() {
        assertTrue(SimulationPriceEngine.correlatedZ(-0.5, 2.0, 0.0) < 0.0);
        assertTrue(SimulationPriceEngine.correlatedZ(0.5, 2.0, 0.0) > 0.0);
    }

    /** {@code amplitude = 0} ⇒ {@code exp(0) = 1}，对数收益完全失效。 */
    @Test
    void amplitudeZeroDisablesLogReturn() {
        SimulationPriceEngine.Params p = params(5.0, 5.0, 5.0, 1.0, 5.0, 5.0, 0.0, 0.0, 1.0);
        assertEquals(100.0, SimulationPriceEngine.advance(100.0, p, 100.0, 0.0, 1.0), 1e-12);
        // 只剩斜率项
        SimulationPriceEngine.Params sloped = params(5.0, 5.0, 5.0, 1.0, 5.0, 5.0, 0.0, 0.01, 0.5);
        assertEquals(105.0, SimulationPriceEngine.advance(100.0, sloped, 100.0, 0.0, 1.0), 1e-12);
    }

    /** {@code exp()} 溢出为 Infinity 时必须回落到推进前的价格，绝不把 Infinity / NaN 传出去。 */
    @Test
    void expOverflowFallsBackToPreviousPrice() {
        // amplitude = 1e9 且 logReturn > 0 ⇒ exp 溢出
        SimulationPriceEngine.Params p = params(1.0, 0.0, 0.0, 100.0, 0.0, 0.0, 1.0e9, 0.0, 1.0);
        double next = SimulationPriceEngine.advance(100.0, p, 0.0, 0.0, 0.0);
        assertTrue(Double.isFinite(next), "溢出后必须是有限值，实际=" + next);
        assertEquals(100.0, next, 0.0);

        // 非有限的结果不能污染后续：连续 10 次仍恒等于上一价格
        double price = 250.0;
        for (int i = 0; i < 10; i++) {
            price = SimulationPriceEngine.advance(price, p, 0.0, 0.0, 0.0);
            assertEquals(250.0, price, 0.0);
        }
    }

    /** 非正 / 非有限的自报价在 {@code exp()} 之前被夹到 {@code 0.01}。 */
    @Test
    void nonPositiveAutoPriceIsClampedBeforeExp() {
        SimulationPriceEngine.Params p = params(0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0);
        for (double bad : new double[]{-5.0, 0.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            double next = SimulationPriceEngine.advance(bad, p, 0.0, 0.0, 0.0);
            assertTrue(Double.isFinite(next), "输入 " + bad + " ⇒ 结果必须有限，实际=" + next);
            assertTrue(next >= 0.01, "输入 " + bad + " ⇒ 结果必须 >= 0.01，实际=" + next);
            assertEquals(0.01, next, 0.0);
        }
    }

    /** 斜率项 = {@code slopePerMs * dtSeconds * 1000}，与无斜率版本差值恰为该量。 */
    @Test
    void slopeAddsAbsolutePricePerMillisecond() {
        SimulationPriceEngine.Params base = params(0.001, 0.01, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0);
        SimulationPriceEngine.Params sloped = params(0.001, 0.01, 0.0, 100.0, 0.0, 0.0, 1.0, 0.001, 1.0);

        double a = SimulationPriceEngine.advance(100.0, base, 0.3, 0.0, 0.0);
        double b = SimulationPriceEngine.advance(100.0, sloped, 0.3, 0.0, 0.0);
        assertEquals(1.0, b - a, 1e-9);
    }

    /** 斜率项在指数项<b>之后</b>叠加（加法顺序不影响结果）。 */
    @Test
    void slopeTermIsAppliedAfterExponential() {
        SimulationPriceEngine.Params p = params(0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.01, 0.5);
        assertEquals(105.0, SimulationPriceEngine.advance(100.0, p, 0.0, 0.0, 0.0), 1e-9);
    }
}
