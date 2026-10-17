package dev.onelili.mstock.simulation;

import dev.onelili.mstock.database.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟盘定时价格冲击测试。
 *
 * <p>覆盖两部分：① {@link SimulationSchedule#matches} 的纯函数格式 / 分钟精度；
 * ② 触发幂等与一次性语义 —— 这一部分走真实 H2 内存库，因为"今日是否已触发"的权威来源是
 * {@code simulation_schedules.last_fired_date}。
 *
 * <p>{@code changePercent} 的可观察效果在 {@code SimulationService.tick()}（属 C3 范围）。
 * 本测试用 {@link #applyShock} / {@link #shouldFire} 两个私有助手复刻步骤 ① 的既定顺序，
 * 以便在编排层落地前就能锁住"乘法叠加 + 仍受带/上下限约束"的契约。
 */
class SimulationScheduleTest {

    private SimulationRepository repository;
    private SimulationConfig config;
    private SimulationStockRegistry registry;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DatabaseManager database = new DatabaseManager(Logger.getAnonymousLogger()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl);
            }
        };
        createTables(database);
        repository = new SimulationRepository(database, Logger.getAnonymousLogger());
        config = new SimulationConfig(null);
        registry = new SimulationStockRegistry(repository, config, Logger.getAnonymousLogger());
        registry.bootstrap();
    }

    @Test
    void parsesTwentyFourHourFormat() {
        LocalTime nineThirty = LocalTime.of(9, 30, 0);

        assertTrue(SimulationSchedule.matches("00:00", LocalTime.MIDNIGHT));
        assertTrue(SimulationSchedule.matches("09:30", nineThirty));
        assertTrue(SimulationSchedule.matches("23:59", LocalTime.of(23, 59, 59)));
        assertTrue(SimulationSchedule.matches(" 09:30 ", nineThirty));

        // 非法格式一律 false（不抛异常）
        assertFalse(SimulationSchedule.matches("24:00", LocalTime.of(0, 0, 0)));
        assertFalse(SimulationSchedule.matches("9:30", nineThirty), "小时必须占 2 位");
        assertFalse(SimulationSchedule.matches("09:5", nineThirty), "分钟必须占 2 位");
        assertFalse(SimulationSchedule.matches("09-30", nineThirty));
        assertFalse(SimulationSchedule.matches("09:60", nineThirty));
        assertFalse(SimulationSchedule.matches("ab:cd", nineThirty));
        assertFalse(SimulationSchedule.matches("", nineThirty));
        assertFalse(SimulationSchedule.matches("09:30:00", nineThirty));
        assertFalse(SimulationSchedule.matches(null, nineThirty));
        assertFalse(SimulationSchedule.matches("09:30", null));
    }

    @Test
    void matchesIsMinutePrecise() {
        assertTrue(SimulationSchedule.matches("09:30", LocalTime.of(9, 30, 0)));
        assertTrue(SimulationSchedule.matches("09:30", LocalTime.of(9, 30, 59)));
        assertFalse(SimulationSchedule.matches("09:30", LocalTime.of(9, 31, 0)));
        assertFalse(SimulationSchedule.matches("09:30", LocalTime.of(9, 29, 59)));
        assertFalse(SimulationSchedule.matches("09:30", LocalTime.of(10, 30, 0)));
    }

    @Test
    void onceFiringDisablesTheSchedule() throws SQLException {
        long id = repository.insertSchedule("SIM001", "09:30", 5.0, false);
        LocalTime now = LocalTime.of(9, 30, 30);
        String today = "2026-01-02";

        assertTrue(shouldFire(schedule(id), today, now));
        assertTrue(repository.markScheduleFired(id, today, true));

        SimulationSchedule fired = schedule(id);
        assertFalse(fired.enabled(), "一次性定时触发后必须自动禁用");
        assertFalse(shouldFire(fired, today, now), "禁用后即使时间命中也不再触发");
        assertFalse(shouldFire(fired, "2026-01-03", now), "禁用状态跨日也不会复活");
    }

    @Test
    void dailyFiringRepeatsOnTheNextDay() throws SQLException {
        long id = repository.insertSchedule("SIM001", "09:30", 5.0, true);
        LocalTime now = LocalTime.of(9, 30, 30);
        String today = "2026-01-02";
        String tomorrow = "2026-01-03";

        assertTrue(shouldFire(schedule(id), today, now));
        // repeatDaily=true ⇒ disable=false：不得触碰 enabled 列
        assertTrue(repository.markScheduleFired(id, today, false));
        assertTrue(schedule(id).enabled(), "每日重复的定时不得被禁用");
        assertEquals(today, schedule(id).lastFiredDate());

        assertFalse(shouldFire(schedule(id), today, now), "当天不得二次触发");
        assertTrue(shouldFire(schedule(id), tomorrow, now), "换一天必须可以再次触发");
    }

    @Test
    void sameDayIsNeverFiredTwice() throws SQLException {
        long id = repository.insertSchedule("SIM001", "09:30", 5.0, true);
        String today = "2026-01-02";
        LocalTime now = LocalTime.of(9, 30, 0);

        repository.markScheduleFired(id, today, false);

        // 幂等：lastFiredDate == today ⇒ 即使时间匹配也不触发
        assertFalse(shouldFire(schedule(id), today, now));
        assertFalse(shouldFire(schedule(id), today, LocalTime.of(9, 30, 59)));
    }

    @Test
    void changePercentAppliesMultiplicatively() {
        double base = 100.0;

        // +10% ⇒ 110（百分数口径：changePercent/100.0）
        assertEquals(110.0, base * (1.0 + 10.0 / 100.0), 1e-9);
        // -10% ⇒ 90
        assertEquals(90.0, base * (1.0 - 10.0 / 100.0), 1e-9);

        // 乘法叠加（而非加法）：+10% 再 +10% ⇒ 121.0，而不是 120.0
        double twice = base * (1.0 + 10.0 / 100.0) * (1.0 + 10.0 / 100.0);
        assertEquals(121.0, twice, 1e-9);
        // 顺序敏感：先 -10% 再 +10% ⇒ 99.0
        assertEquals(99.0, base * (1.0 - 10.0 / 100.0) * (1.0 + 10.0 / 100.0), 1e-9);

        // 冲击值必须过 safePrice 兜底：-100% 的冲击不得产生 0 或负数
        assertEquals(0.01, SimulationPriceEngine.safePrice(base * (1.0 - 100.0 / 100.0)), 1e-12);
    }

    @Test
    void scheduledShockStillObeysLimitsAndBounds() throws SQLException {
        SimulationStock stock = registry.find("SIM001").orElseThrow();
        assertNotNull(stock);
        // 昨收 10.00、涨跌停 ±10%（百分数口径）⇒ 带为 [9.00, 11.00]
        assertEquals(10.0, stock.getPrevClose(), 1e-9);
        assertEquals(10.0, stock.getLimitPercent(), 1e-9);
        assertEquals(9.0, stock.lowerLimit(), 1e-9);
        assertEquals(11.0, stock.upperLimit(), 1e-9);
        stock.setBoundary(SimulationBoundary.HARD_CLAMP);

        // +100% 的冲击远超涨跌停带：applyBand 必须把它压回带内
        double shocked = applyShock(stock, 100.0, LocalTime.of(9, 30, 0));
        assertEquals(stock.upperLimit(), shocked, 1e-9);
        assertEquals(11.0, shocked, 1e-9);

        // 绝对上下限仍然硬截断（上限 10.5 比涨停价更紧）
        stock.setBounds(0.01, 10.5);
        assertEquals(10.5, applyShock(stock, 100.0, LocalTime.of(9, 30, 0)), 1e-9);

        // -100% 的冲击同样被带下沿挡住，且绝不会落到 <= 0
        stock.setBounds(0.01, 10.5);
        assertEquals(9.0, applyShock(stock, -100.0, LocalTime.of(9, 30, 0)), 1e-9);
    }

    @Test
    void disabledScheduleNeverFires() throws SQLException {
        long id = repository.insertSchedule("SIM001", "09:30", 5.0, true);
        assertTrue(repository.setScheduleEnabled(id, false));

        LocalTime now = LocalTime.of(9, 30, 0);
        assertFalse(shouldFire(schedule(id), "2026-01-02", now), "enabled=false 时即使时间命中也不触发");

        // 重新启用后立即恢复触发资格（last_fired_date 仍为 ""）
        assertTrue(repository.setScheduleEnabled(id, true));
        assertTrue(shouldFire(schedule(id), "2026-01-02", now));
    }

    @Test
    void unknownCodeScheduleIsSkippedWithoutError() throws SQLException {
        long id = repository.insertSchedule("SIM999", "09:30", 5.0, true);
        String today = "2026-01-02";

        SimulationSchedule schedule = schedule(id);
        assertTrue(shouldFire(schedule, today, LocalTime.of(9, 30, 0)));

        // 复刻 tick ①：代码未注册 ⇒ continue，不抛异常、不写 last_fired_date
        SimulationStock stock = registry.find(schedule.code()).orElse(null);
        assertNull(stock);
        assertEquals(10, registry.size());
        assertEquals("", schedule(id).lastFiredDate());
    }

    @Test
    void scheduleRoundTripsThroughRepository() throws SQLException {
        long id = repository.insertSchedule("SIM002", "21:05", -7.5, false);
        assertTrue(id > 0);

        List<SimulationSchedule> schedules = repository.findSchedules();
        assertEquals(1, schedules.size());
        SimulationSchedule stored = schedules.get(0);
        assertEquals(id, stored.id());
        assertEquals("SIM002", stored.code());
        assertEquals("21:05", stored.timeOfDay());
        assertEquals(-7.5, stored.changePercent(), 1e-9);
        assertFalse(stored.repeatDaily());
        assertTrue(stored.enabled());
        assertEquals("", stored.lastFiredDate(), "未触发过时 last_fired_date 初值为空串");

        // 第二次插入获得更大的自增 id，且顺序稳定
        long second = repository.insertSchedule("SIM003", "06:00", 2.0, true);
        assertTrue(second > id);
        List<SimulationSchedule> ordered = repository.findSchedules();
        assertEquals(id, ordered.get(0).id());
        assertEquals(second, ordered.get(1).id());
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────────

    /**
     * 复刻 {@code SimulationService.tick()} 步骤 ① 的触发条件：
     * {@code enabled && !today.equals(lastFiredDate) && matches(timeOfDay, now)}。
     */
    private static boolean shouldFire(SimulationSchedule schedule, String today, LocalTime now) {
        if (!schedule.enabled()) return false;
        if (today.equals(schedule.lastFiredDate())) return false;
        return SimulationSchedule.matches(schedule.timeOfDay(), now);
    }

    /**
     * 复刻 {@code tick()} 步骤 ① 的冲击应用：先乘 {@code (1 + pct/100)} 到 {@code autoPrice}，
     * 再依次走涨跌停带 → 绝对上下限 → 最终兜底（该顺序不可调换）。
     *
     * <p>价格取整条链路的<b>最终结果</b>（而不是只写 {@code currentPrice}），这样"冲击后仍受
     * 带与上下限约束"的断言同时覆盖了步骤 ① 与步骤 ③ 的边界处理。
     *
     * @return 应用冲击后的最终价格（时间不匹配时返回当前价，不做任何修改）
     */
    private static double applyShock(SimulationStock stock, double changePercent, LocalTime now) {
        if (!SimulationSchedule.matches("09:30", now)) return stock.getCurrentPrice();
        synchronized (stock) {
            double shocked = SimulationPriceEngine.safePrice(
                    stock.getAutoPrice() * (1.0 + changePercent / 100.0));
            stock.setAutoPrice(shocked);
            double band = SimulationPriceEngine.applyBand(shocked,
                    stock.lowerLimit(), stock.upperLimit(), stock.getBoundary());
            double bounded = SimulationPriceEngine.applyHardBounds(band,
                    stock.getMinPrice(), stock.getMaxPrice());
            double finalPrice = SimulationPriceEngine.safePrice(bounded);
            stock.setCurrentPrice(finalPrice);
            return finalPrice;
        }
    }

    private SimulationSchedule schedule(long id) throws SQLException {
        for (SimulationSchedule schedule : repository.findSchedules()) {
            if (schedule.id() == id) return schedule;
        }
        throw new SQLException("schedule not found: " + id);
    }

    /** 反射调用包级的 {@code DatabaseManager#createTables()}（跨包访问，不复制 DDL）。 */
    private static void createTables(DatabaseManager database) throws SQLException {
        try {
            Method method = DatabaseManager.class.getDeclaredMethod("createTables");
            method.setAccessible(true);
            method.invoke(database);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new SQLException("无法调用 DatabaseManager#createTables()", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) throw sql;
            throw new SQLException("createTables() 执行失败", cause);
        }
    }
}
