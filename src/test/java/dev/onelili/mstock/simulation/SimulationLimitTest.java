package dev.onelili.mstock.simulation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 涨跌停带与涨跌幅口径测试。
 *
 * <p><b>单位口径</b>：{@code limitPercent} 是<b>百分数</b>，{@code 10.0} = ±10%。
 * 换算<b>只</b>出现在 {@link SimulationStock#lowerLimit()} / {@link SimulationStock#upperLimit()} 两处。
 */
class SimulationLimitTest {

    private static SimulationStock stock(double initPrice, long seed) {
        return new SimulationStock("SIM001", "模拟股 01", "科技", initPrice, seed);
    }

    /** 上下限由昨收与涨跌停百分数推导。 */
    @Test
    void lowerAndUpperLimitsAreDerivedFromPrevClose() {
        SimulationStock s = stock(100.0, 1L);
        s.setPrevClose(100.0);
        s.setLimitPercent(10.0);

        assertEquals(90.0, s.lowerLimit(), 1e-9);
        assertEquals(110.0, s.upperLimit(), 1e-9);
        // 百分数口径的直接证据：上限相对昨收恰为 +10%
        // （若被误读成"比例"口径，0.1 会被夹到 0.0001 而产生 90.0 == 90.0 的零宽带）
        assertEquals(10.0,
                (s.upperLimit() - s.getPrevClose()) / s.getPrevClose() * 100.0, 1e-9);
        assertTrue(s.upperLimit() - s.lowerLimit() > 0.0, "零宽带死锁：上下限相等");
    }

    /** 夹取到 {@code [0.0001, 10.0]}。 */
    @Test
    void limitPercentIsClampedToLegalRange() {
        SimulationStock s = stock(100.0, 2L);

        s.setLimitPercent(0.0);
        assertEquals(0.0001, s.getLimitPercent(), 0.0);

        s.setLimitPercent(-5.0);
        assertEquals(0.0001, s.getLimitPercent(), 0.0);

        s.setLimitPercent(10.0001);
        assertEquals(10.0, s.getLimitPercent(), 0.0);

        s.setLimitPercent(500.0);
        assertEquals(10.0, s.getLimitPercent(), 0.0);

        // 边界内的值原样保留
        s.setLimitPercent(5.0);
        assertEquals(5.0, s.getLimitPercent(), 0.0);
        // 非有限值落回下界，绝不产出 NaN 带宽
        s.setLimitPercent(Double.NaN);
        assertEquals(0.0001, s.getLimitPercent(), 0.0);
    }

    /** 最小合法带宽仍严格为正，不会出现 {@code lower == upper} 的死锁。 */
    @Test
    void zeroWidthBandCannotDeadlock() {
        SimulationStock s = stock(100.0, 3L);
        s.setPrevClose(100.0);
        s.setLimitPercent(0.0001);

        double width = s.upperLimit() - s.lowerLimit();
        assertTrue(width > 0.0, "带宽必须严格大于 0，实际=" + width);
        assertEquals(2.0 * 100.0 * 0.0001 / 100.0, width, 1e-12);

        // 极小带宽下夹取后仍落在带内（HARD_CLAMP）
        double clamped = SimulationPriceEngine.applyBand(1000.0, s.lowerLimit(), s.upperLimit(),
                SimulationBoundary.HARD_CLAMP);
        assertEquals(s.upperLimit(), clamped, 1e-12);
        assertTrue(clamped > s.lowerLimit());
    }

    /** 三种策略 × 随机价格：硬截断与反射恒落在带内；软回归只保证"不翻侧"。 */
    @Test
    void allBoundariesKeepPriceInsideAfterHardClamp() {
        Random rnd = new Random(42L);
        for (int i = 0; i < 1000; i++) {
            double prevClose = 0.5 + rnd.nextDouble() * 500.0;
            double limitPercent = 0.0001 + rnd.nextDouble() * 9.9999;
            double price = 0.01 + rnd.nextDouble() * 2000.0;

            SimulationStock s = stock(prevClose, 4L + i);
            s.setPrevClose(prevClose);
            s.setLimitPercent(limitPercent);
            double lower = s.lowerLimit();
            double upper = s.upperLimit();
            assertTrue(upper > lower, "带宽必须为正");

            // HARD_CLAMP：结果恒在带内
            double hard = SimulationPriceEngine.applyBand(price, lower, upper, SimulationBoundary.HARD_CLAMP);
            assertTrue(hard >= lower && hard <= upper, "HARD_CLAMP 越界：" + hard + " 不在 [" + lower + "," + upper + "]");

            // BOUNCE：结果恒在带内
            double bounce = SimulationPriceEngine.applyBand(price, lower, upper, SimulationBoundary.BOUNCE);
            assertTrue(bounce >= lower && bounce <= upper,
                    "BOUNCE 越界：" + bounce + " 不在 [" + lower + "," + upper + "]");

            // SOFT_REGRESSION：只保证"不翻侧"（不越到相反一侧），明确不保证"不越界"
            double soft = SimulationPriceEngine.applyBand(price, lower, upper, SimulationBoundary.SOFT_REGRESSION);
            if (price >= lower && price <= upper) {
                assertEquals(price, soft, 0.0, "带内价格应原样通过");
            } else if (price > upper) {
                assertTrue(soft > upper, "SOFT_REGRESSION 翻到下侧：" + soft + " <= " + upper);
                assertTrue(soft <= price, "SOFT_REGRESSION 反而放大了上越界幅度");
            } else {
                assertTrue(soft < lower, "SOFT_REGRESSION 翻到上侧：" + soft + " >= " + lower);
                assertTrue(soft >= price, "SOFT_REGRESSION 反而放大了下越界幅度");
            }
        }
    }

    /** 软回归只是衰减、不是夹取：单次应用仍落在带外，连续应用按 0.5^n 收敛。 */
    @Test
    void softRegressionDampsButDoesNotClamp() {
        // 单次：120 → 115，明确落在带外
        double once = SimulationPriceEngine.applyBand(120.0, 90.0, 110.0, SimulationBoundary.SOFT_REGRESSION);
        assertEquals(115.0, once, 1e-12);
        assertTrue(once > 110.0, "SOFT_REGRESSION 是衰减而非夹取，单次结果必须仍在带外");

        // 连续 20 次：越界幅度按 0.5^n 衰减
        double price = 120.0;
        for (int i = 0; i < 20; i++) {
            price = SimulationPriceEngine.applyBand(price, 90.0, 110.0, SimulationBoundary.SOFT_REGRESSION);
        }
        assertTrue(Math.abs(price - 110.0) < 1e-5, "第 20 次越界幅度应 < 1e-5，实际=" + Math.abs(price - 110.0));
        assertEquals(110.0 + 10.0 * Math.pow(0.5, 20), price, 1e-12);
    }

    /** 昨收滚动会立刻重置涨跌停带。 */
    @Test
    void prevCloseRolloverResetsBand() {
        SimulationStock s = stock(100.0, 5L);
        s.setPrevClose(100.0);
        s.setLimitPercent(10.0);
        assertEquals(90.0, s.lowerLimit(), 1e-9);
        assertEquals(110.0, s.upperLimit(), 1e-9);

        s.setPrevClose(50.0);
        assertEquals(45.0, s.lowerLimit(), 1e-9);
        assertEquals(55.0, s.upperLimit(), 1e-9);
    }

    /** 涨跌幅基于昨收。 */
    @Test
    void changePercentUsesPrevClose() {
        SimulationStock s = stock(100.0, 6L);
        s.setPrevClose(100.0);
        s.setCurrentPrice(110.0);

        assertEquals(10.0, s.changeAmount(), 1e-12);
        assertEquals(10.0, s.changePercent(), 1e-12);

        // 下跌方向
        s.setCurrentPrice(90.0);
        assertEquals(-10.0, s.changeAmount(), 1e-12);
        assertEquals(-10.0, s.changePercent(), 1e-12);
    }

    /** 昨收非正时涨跌幅为 0（不得产生 NaN / Infinity）。 */
    @Test
    void changePercentIsZeroWhenPrevCloseIsNotPositive() {
        SimulationStock s = stock(100.0, 7L);
        s.setCurrentPrice(120.0);

        for (double badClose : new double[]{0.0, -10.0, Double.NaN}) {
            s.setPrevClose(badClose);
            assertEquals(0.0, s.changePercent(), 0.0, "prevClose=" + badClose + " 时涨跌幅必须为 0");
            assertTrue(Double.isFinite(s.changePercent()));
        }
    }
}
