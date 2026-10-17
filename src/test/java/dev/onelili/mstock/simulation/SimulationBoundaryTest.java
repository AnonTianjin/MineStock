package dev.onelili.mstock.simulation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimulationBoundary} 与 {@link SimulationPriceEngine} 边界策略测试
 * 
 *
 * <p>核心事实：{@code SOFT_REGRESSION} 的公式
 * {@code price = limit + (price-limit)*0.5} 保证的是"不翻越到对侧"，<b>不是</b>"不越界"。
 */
class SimulationBoundaryTest {

    private static final double LOWER = 90.0;
    private static final double UPPER = 110.0;

    /** 硬截断：两侧都截到限价。 */
    @Test
    void hardClampTruncatesBothSides() {
        assertEquals(110.0, SimulationPriceEngine.applyBand(200.0, LOWER, UPPER, SimulationBoundary.HARD_CLAMP), 0.0);
        assertEquals(90.0, SimulationPriceEngine.applyBand(10.0, LOWER, UPPER, SimulationBoundary.HARD_CLAMP), 0.0);
    }

    /** 带内价格原样通过。 */
    @Test
    void hardClampLeavesInBandPriceUntouched() {
        assertEquals(100.0, SimulationPriceEngine.applyBand(100.0, LOWER, UPPER, SimulationBoundary.HARD_CLAMP), 1e-12);
    }

    /** 从涨停反射：{@code 2*upper - price}。 */
    @Test
    void bounceReflectsFromUpperLimit() {
        assertEquals(105.0, SimulationPriceEngine.applyBand(115.0, LOWER, UPPER, SimulationBoundary.BOUNCE), 1e-12);
    }

    /** 从跌停反射：{@code 2*lower - price}。 */
    @Test
    void bounceReflectsFromLowerLimit() {
        assertEquals(95.0, SimulationPriceEngine.applyBand(85.0, LOWER, UPPER, SimulationBoundary.BOUNCE), 1e-12);
    }

    /** 极端冲击穿两侧后夹到对侧。 */
    @Test
    void bounceClampsWhenExtremeShockCrossesBothLimits() {
        assertEquals(110.0, SimulationPriceEngine.applyBand(1000.0, LOWER, UPPER, SimulationBoundary.BOUNCE), 1e-12);
        assertEquals(90.0, SimulationPriceEngine.applyBand(-1000.0, LOWER, UPPER, SimulationBoundary.BOUNCE), 1e-12);
    }

    /** 软回归：自上越界幅度减半，结果仍在带外。 */
    @Test
    void softRegressionHalvesOvershootFromUpper() {
        assertEquals(110.5, SimulationPriceEngine.applyBand(111.0, LOWER, UPPER,
                SimulationBoundary.SOFT_REGRESSION), 1e-12);
    }

    /** 软回归：自下越界幅度减半，结果仍在带外。 */
    @Test
    void softRegressionHalvesOvershootFromLower() {
        assertEquals(89.5, SimulationPriceEngine.applyBand(89.0, LOWER, UPPER,
                SimulationBoundary.SOFT_REGRESSION), 1e-12);
    }

    /** 软回归只减半、不翻侧：极端冲击后结果仍在上沿之上，且幅度小于输入。 */
    @Test
    void softRegressionDoesNotFlipToOppositeSide() {
        double result = SimulationPriceEngine.applyBand(1.0e6, LOWER, UPPER, SimulationBoundary.SOFT_REGRESSION);
        assertTrue(result > UPPER, "软回归后仍应落在带外（上侧），实际=" + result);
        assertTrue(result - UPPER < 1.0e6 - UPPER, "越界幅度必须小于输入幅度");
        assertEquals(UPPER + (1.0e6 - UPPER) * 0.5, result, 1e-6);

        double downward = SimulationPriceEngine.applyBand(0.001, LOWER, UPPER, SimulationBoundary.SOFT_REGRESSION);
        assertTrue(downward < LOWER, "软回归后仍应落在带外（下侧），实际=" + downward);
    }

    /** 绝对上下限永远硬截断，与涨跌停带策略无关。 */
    @Test
    void hardBoundsAlwaysClampRegardlessOfBoundary() {
        // SOFT_REGRESSION 会产生 155（带外），必须被 [50,150] 截到 150
        double soft = SimulationPriceEngine.applyBand(200.0, LOWER, UPPER, SimulationBoundary.SOFT_REGRESSION);
        assertEquals(155.0, soft, 1e-12);
        assertEquals(150.0, SimulationPriceEngine.applyHardBounds(soft, 50.0, 150.0), 0.0);

        // 三种策略的结果都必须落在 [50,150] 内（HARD_CLAMP 得 110、BOUNCE 得 110、SOFT 得 150）
        for (SimulationBoundary boundary : SimulationBoundary.values()) {
            for (double price : new double[]{1.0e6, 200.0, 110.0, 100.0, 90.0, 10.0, -1.0e6}) {
                double bounded = SimulationPriceEngine.applyHardBounds(
                        SimulationPriceEngine.applyBand(price, LOWER, UPPER, boundary), 50.0, 150.0);
                assertTrue(bounded >= 50.0 && bounded <= 150.0,
                        boundary + " price=" + price + " ⇒ " + bounded + " 越出 [50,150]");
            }
        }
    }

    /** 绝对上下限压倒涨跌停带：结果恒在 {@code [95,105]} 内。 */
    @Test
    void hardBoundsWinOverBand() {
        Random rnd = new Random(20260101L);
        for (int i = 0; i < 1000; i++) {
            double price = 0.001 + rnd.nextDouble() * 200.0;
            for (SimulationBoundary boundary : SimulationBoundary.values()) {
                double result = SimulationPriceEngine.applyHardBounds(
                        SimulationPriceEngine.applyBand(price, LOWER, UPPER, boundary), 95.0, 105.0);
                assertTrue(result >= 95.0 && result <= 105.0,
                        boundary + " price=" + price + " ⇒ " + result + " 越出 [95,105]");
            }
        }
    }

    /** 非法输入一律落到下沿。 */
    @Test
    void invalidPriceFallsBackToLowerLimit() {
        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0};
        for (SimulationBoundary boundary : SimulationBoundary.values()) {
            for (double price : bad) {
                assertEquals(LOWER, SimulationPriceEngine.applyBand(price, LOWER, UPPER, boundary), 0.0,
                        boundary + " price=" + price);
            }
        }
    }

    /** {@code safePrice} 恒 {@code >= 0.01}。 */
    @Test
    void safePriceNeverBelowOneCent() {
        assertEquals(0.01, SimulationPriceEngine.safePrice(0.0), 0.0);
        assertEquals(0.01, SimulationPriceEngine.safePrice(-1.0), 0.0);
        assertEquals(0.01, SimulationPriceEngine.safePrice(Double.NaN), 0.0);
        assertEquals(0.01, SimulationPriceEngine.safePrice(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(0.01, SimulationPriceEngine.safePrice(Double.NEGATIVE_INFINITY), 0.0);
        assertEquals(0.01, SimulationPriceEngine.safePrice(0.009), 0.0);
        // 正常值原样通过
        assertEquals(100.0, SimulationPriceEngine.safePrice(100.0), 0.0);
        assertEquals(0.01, SimulationPriceEngine.safePrice(0.01), 0.0);
    }

    /** 退化区间 {@code min >= max}（由指令层拒绝）也不得产出越界值。 */
    @Test
    void degenerateHardBoundsStillProduceUsablePrice() {
        double degenerate = SimulationPriceEngine.applyHardBounds(100.0, 110.0, 90.0);
        assertTrue(Double.isFinite(degenerate) && degenerate > 0.0, "退化区间仍须给出可用价，实际=" + degenerate);
        assertEquals(110.0, degenerate, 0.0);
    }

    // ───────────────────────── id() / parse() 往返 ─────────────────────────

    /**
     * {@code parse(x.id()) == x} 对每个常量都必须成立。
     *
     * <p>回归保护：{@code id()} 的返回值会被写进 {@code config.yml} 与数据库列
     * {@code simulation_stocks.boundary}，读回时走的正是 {@code parse}。早期别名表只认
     * {@code hard}/{@code clamp}/{@code soft}/{@code regress}，不认 {@code id()} 实际输出的
     * {@code hard_clamp}/{@code soft_regression} —— 结果是"管理员设了 HARD_CLAMP，
     * 重启后静默变成 SOFT_REGRESSION"，且没有任何报错。此断言锁死该往返。
     */
    @Test
    void idRoundTripsThroughParse() {
        for (SimulationBoundary boundary : SimulationBoundary.values()) {
            assertEquals(boundary, SimulationBoundary.parse(boundary.id()),
                    "parse(id()) 必须还原常量：" + boundary.id());
            assertEquals(boundary, SimulationBoundary.parse(boundary.name()),
                    "枚举全称也必须可解析：" + boundary.name());
            assertEquals(boundary, SimulationBoundary.parse(boundary.id().toUpperCase()),
                    "大小写必须不敏感");
            assertEquals(boundary, SimulationBoundary.parse("  " + boundary.id() + "  "),
                    "两端空白必须被忽略");
        }
    }

    /** 别名与非法输入的既定语义不变（非法 → {@code null}，由调用方决定回落值）。 */
    @Test
    void parseAliasesAndRejectsUnknown() {
        assertEquals(SimulationBoundary.HARD_CLAMP, SimulationBoundary.parse("hard"));
        assertEquals(SimulationBoundary.HARD_CLAMP, SimulationBoundary.parse("clamp"));
        assertEquals(SimulationBoundary.HARD_CLAMP, SimulationBoundary.parse("HARD_CLAMP"));
        assertEquals(SimulationBoundary.BOUNCE, SimulationBoundary.parse("bounce"));
        assertEquals(SimulationBoundary.BOUNCE, SimulationBoundary.parse("reflect"));
        assertEquals(SimulationBoundary.BOUNCE, SimulationBoundary.parse("BOUNCE"));
        assertEquals(SimulationBoundary.SOFT_REGRESSION, SimulationBoundary.parse("soft"));
        assertEquals(SimulationBoundary.SOFT_REGRESSION, SimulationBoundary.parse("regress"));
        assertEquals(SimulationBoundary.SOFT_REGRESSION, SimulationBoundary.parse("soft_regression"));
        assertNull(SimulationBoundary.parse(null), "null → null");
        assertNull(SimulationBoundary.parse(""), "空串 → null（不是默认值）");
        assertNull(SimulationBoundary.parse("bogus"), "未识别 → null（不是默认值）");
    }
}
