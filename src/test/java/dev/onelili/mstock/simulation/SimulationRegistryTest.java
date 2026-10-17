package dev.onelili.mstock.simulation;

import dev.onelili.mstock.database.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟股票池测试。
 *
 * <p>用 H2 内存库手法（覆写 {@code DatabaseManager#getConnection}）——
 * 注册表与仓储是协作关系，用真实内存库同时验证两者。
 *
 * <p>完全不碰 Bukkit：{@link SimulationConfig} 用 {@code null} 配置构造，
 * 此时它返回文档化默认值（{@code auto-fill-count = 10}、{@code seed-base = 20260101}）。
 */
class SimulationRegistryTest {

    /** 与 {@code SimulationStockSource.CODE} 同式，用于本测试独立复核代码格式。 */
    private static final Pattern CODE = Pattern.compile("^[A-Z]{3}\\d{3}$");

    /** 现有三个数据源的抢注正则，用于交叉验证"模拟代码不被抢注"。 */
    private static final Pattern CHINA = Pattern.compile("^\\d{6}$");
    private static final Pattern HK = Pattern.compile("^\\d{1,5}$");
    private static final Pattern US = Pattern.compile("^[A-Za-z]{1,10}$");

    private static final long SEED_BASE = 20260101L;

    private DatabaseManager database;
    private SimulationRepository repository;
    private SimulationConfig config;
    private SimulationStockRegistry registry;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        database = new DatabaseManager(Logger.getAnonymousLogger()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl);
            }
        };
        createTables(database);
        repository = new SimulationRepository(database, Logger.getAnonymousLogger());
        config = new SimulationConfig(null);
        // 契约核对：本测试依赖的是 config 的文档化默认值
        assertEquals(10, config.autoFillCount());
        assertEquals(SEED_BASE, config.seedBase());
        registry = bareRegistry();
    }

    @Test
    void bootstrapFillsUniverseUpToConfiguredCount() throws SQLException {
        assertTrue(registry.bootstrap());

        assertEquals(10, registry.size());
        List<SimulationStock> all = registry.all();
        assertEquals(10, all.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(String.format("SIM%03d", i + 1), all.get(i).getCode());
        }
        // 已落库：换一个注册表实例重新 bootstrap 不再需要写库
        assertEquals(10, repository.findAllStocks().size());
    }

    @Test
    void bootstrapIsIdempotent() throws SQLException {
        assertTrue(registry.bootstrap());
        // 第二次：库里已满 10 只 ⇒ 无写库
        assertFalse(registry.bootstrap());

        assertEquals(10, registry.size());
        assertEquals(10, repository.findAllStocks().size());
    }

    @Test
    void bootstrapKeepsExistingStocksAndOnlyFillsTheGap() throws SQLException {
        repository.upsertStock(new SimulationStock("SIM001", "自定义 01", "自选", 123.45, 999L));
        repository.upsertStock(new SimulationStock("SIM002", "自定义 02", "自选", 66.66, 998L));
        repository.upsertStock(new SimulationStock("SIM003", "自定义 03", "自选", 7.77, 997L));

        assertTrue(registry.bootstrap());

        assertEquals(10, registry.size());
        SimulationStock kept = registry.find("SIM001").orElse(null);
        assertNotNull(kept);
        // 已存在的股票必须保持库里的值，绝不被默认宇宙覆盖（否则重启会回退管理员配置）
        assertEquals("自定义 01", kept.getName());
        assertEquals("自选", kept.getSector());
        assertEquals(123.45, kept.getInitPrice(), 1e-9);
        assertEquals(999L, kept.getSeed());
        assertEquals(66.66, registry.find("SIM002").orElseThrow().getInitPrice(), 1e-9);
        assertEquals(7.77, registry.find("SIM003").orElseThrow().getInitPrice(), 1e-9);
        // 缺口用默认宇宙补齐（SIM004..SIM010）
        assertEquals("模拟股 04", registry.find("SIM004").orElseThrow().getName());
        assertEquals(5.20, registry.find("SIM004").orElseThrow().getInitPrice(), 1e-9);
        assertEquals(20260101L + 3, registry.find("SIM004").orElseThrow().getSeed());
    }

    @Test
    void codesMatchTheSimulationPattern() throws SQLException {
        registry.bootstrap();

        for (SimulationStock stock : registry.all()) {
            String code = stock.getCode();
            assertTrue(CODE.matcher(code).matches(), code + " 必须匹配 ^[A-Z]{3}\\d{3}$");
            assertTrue(SimulationStockSource.CODE.matcher(code).matches(), code + " 必须匹配 SimulationStockSource.CODE");
            // 交叉验证：现有三源一个也不认这些代码 ⇒ 不会抢注
            assertFalse(CHINA.matcher(code).matches(), code + " 不应被 A 股源抢注");
            assertFalse(HK.matcher(code).matches(), code + " 不应被港股源抢注");
            assertFalse(US.matcher(code).matches(), code + " 不应被美股源抢注");
        }
    }

    @Test
    void containsIsCaseInsensitiveAndTrimTolerant() throws SQLException {
        registry.bootstrap();

        assertTrue(registry.contains(" sim001 "));
        assertTrue(registry.contains("SIM001"));
        assertTrue(registry.contains("sim001"));
        assertTrue(registry.find("Sim001").isPresent());
        assertEquals("SIM001", registry.find(" sim001 ").orElseThrow().getCode());

        assertFalse(registry.contains("SIM999"));
        assertFalse(registry.contains(""));
        assertFalse(registry.contains("   "));
        assertFalse(registry.contains(null));
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void defaultUniverseIsStableAndOrdered() {
        List<SimulationStock> universe = SimulationStockRegistry.defaultUniverse(10, SEED_BASE);

        assertEquals(10, universe.size());
        for (int i = 0; i < universe.size(); i++) {
            SimulationStock stock = universe.get(i);
            assertEquals(String.format("SIM%03d", i + 1), stock.getCode());
            assertEquals(SEED_BASE + i, stock.getSeed(), "seed 必须等于 seedBase + index");
            assertNotNull(stock.getSector());
            assertFalse(stock.getSector().isBlank());
            assertFalse(stock.getName().isBlank());
            assertTrue(stock.getInitPrice() > 0);
        }
        assertEquals("科技", universe.get(0).getSector());
        assertEquals("医药", universe.get(9).getSector());
        assertEquals(10.00, universe.get(0).getInitPrice(), 1e-9);
        assertEquals(55.60, universe.get(9).getInitPrice(), 1e-9);

        // count 超出模板长度时截断；0 / 负数返回空
        assertEquals(10, SimulationStockRegistry.defaultUniverse(99, SEED_BASE).size());
        assertEquals(0, SimulationStockRegistry.defaultUniverse(0, SEED_BASE).size());
        assertEquals(0, SimulationStockRegistry.defaultUniverse(-5, SEED_BASE).size());
    }

    @Test
    void allReturnsSnapshotSortedByCode() throws SQLException {
        repository.upsertStock(new SimulationStock("SIM009", "模拟股 09", "消费", 7.3, 4L));
        repository.upsertStock(new SimulationStock("SIM001", "模拟股 01", "科技", 10.0, 0L));
        registry.bootstrap();

        List<SimulationStock> snapshot = registry.all();
        assertEquals(10, snapshot.size());
        List<String> codes = new ArrayList<>();
        for (SimulationStock stock : snapshot) {
            codes.add(stock.getCode());
        }
        List<String> sorted = new ArrayList<>(codes);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, codes, "all() 必须按 code 升序");

        // 不可变快照：调用方修改返回列表不得影响后续读取
        assertThrowsUnsupported(() -> snapshot.add(snapshot.get(0)));
        assertEquals(10, registry.all().size());
        assertEquals(snapshot.size(), registry.all().size());
    }

    @Test
    void removeDropsStockAndItsLinksAndSchedules() throws SQLException {
        registry.bootstrap();
        repository.upsertLink("SIM001", "SIM002", 0.5);
        repository.upsertLink("SIM003", "SIM004", 0.5);
        long scheduleId = repository.insertSchedule("SIM001", "09:30", 5.0, true);
        long otherScheduleId = repository.insertSchedule("SIM005", "10:00", 5.0, true);

        assertTrue(registry.remove("SIM001"));

        assertFalse(registry.contains("SIM001"));
        assertTrue(registry.find("SIM001").isEmpty());
        assertEquals(9, registry.size());
        assertNull(repository.findHolding(UUID.randomUUID(), "SIM001"));
        assertTrue(repository.findAllStocks().stream()
                .noneMatch(stock -> "SIM001".equals(stock.getCode())));

        // 联动：SIM001 参与的那条被显式级联删除，另一条保留
        List<SimulationLink> links = repository.findLinks();
        assertEquals(1, links.size());
        assertEquals("SIM003", links.get(0).codeA());

        // 定时：只删指向 SIM001 的那条
        List<SimulationSchedule> schedules = repository.findSchedules();
        assertEquals(1, schedules.size());
        assertEquals(otherScheduleId, schedules.get(0).id());
        assertFalse(schedules.stream().anyMatch(schedule -> schedule.id() == scheduleId));

        // 二次删除返回 false
        assertFalse(registry.remove("SIM001"));
        assertFalse(registry.remove("SIM999"));
        assertFalse(registry.remove(null));
    }

    @Test
    void reseedRestoresTheSamePath() {
        SimulationStock stock = new SimulationStock("SIM001", "模拟股 01", "科技", 10.0, 1L);
        stock.reseed(42L);
        assertEquals(42L, stock.getSeed());

        java.util.Random replay = new java.util.Random(42L);
        for (int i = 0; i < 100; i++) {
            assertEquals(replay.nextGaussian(), stock.random().nextGaussian(), 0.0);
        }
    }

    @Test
    void stockRoundTripsThroughDatabase() throws SQLException {
        registry.bootstrap();
        SimulationStock original = registry.find("SIM001").orElseThrow();
        synchronized (original) {
            original.setCurrentPrice(11.5);
            original.setPrevClose(11.0);
            original.setAutoPrice(11.4);
            original.setAnchor(10.5);
            original.setLimitPercent(5.0);
            original.applyPreset(SimulationVolatilityPreset.HIGH);
            original.setBoundary(SimulationBoundary.HARD_CLAMP);
            original.setBounds(0.05, 5_000.0);
            original.setAmplitude(1.75);
            original.setSigma(0.0021);
            original.setTheta(0.07);
            original.setMu(0.0009);
            original.setJumpLambda(0.006);
            original.setJumpMagnitude(0.025);
            original.setManualPrice(88.0);
            original.setSlopePerMs(0.0002);
            original.setSlopeUntil(1_900_000_000_000L);
            original.setSuspended(true);
            original.setTradable(false);
            original.setPaused(true);
            original.setUpdatedAt(1_700_000_111_000L);
        }
        repository.upsertStock(original);

        // 模拟"重启"：新注册表从 DB 重新载入（库里已满 10 只 ⇒ 无写库）
        SimulationStockRegistry restarted = bareRegistry();
        assertFalse(restarted.bootstrap());
        SimulationStock back = restarted.find("SIM001").orElseThrow();

        assertEquals(original.getName(), back.getName());
        assertEquals(original.getSector(), back.getSector());
        assertEquals(original.getInitPrice(), back.getInitPrice(), 1e-9);
        assertEquals(original.getSeed(), back.getSeed());
        assertEquals(original.getPrevClose(), back.getPrevClose(), 1e-9);
        assertEquals(original.getCurrentPrice(), back.getCurrentPrice(), 1e-9);
        assertEquals(original.getAutoPrice(), back.getAutoPrice(), 1e-9);
        assertEquals(original.getAnchor(), back.getAnchor(), 1e-9);
        assertEquals(5.0, back.getLimitPercent(), 1e-9);
        assertEquals(SimulationVolatilityPreset.HIGH, back.getPreset());
        assertEquals(SimulationBoundary.HARD_CLAMP, back.getBoundary());
        assertEquals(0.05, back.getMinPrice(), 1e-9);
        assertEquals(5_000.0, back.getMaxPrice(), 1e-9);
        assertEquals(1.75, back.getAmplitude(), 1e-9);
        assertEquals(0.0021, back.getSigma(), 1e-9);
        assertEquals(0.07, back.getTheta(), 1e-9);
        assertEquals(0.0009, back.getMu(), 1e-9);
        assertEquals(0.006, back.getJumpLambda(), 1e-9);
        assertEquals(0.025, back.getJumpMagnitude(), 1e-9);
        assertEquals(88.0, back.getManualPrice(), 1e-9);
        assertNull(back.getManualTargetPercent());
        assertEquals(0.0002, back.getSlopePerMs(), 1e-9);
        assertEquals(1_900_000_000_000L, back.getSlopeUntil());
        assertTrue(back.isSuspended());
        assertFalse(back.isTradable());
        assertTrue(back.isPaused());
        assertEquals(1_700_000_111_000L, back.getUpdatedAt());

        // 另一只未被触碰的股票仍是默认值（manulPrice 的 NULL 往返）
        SimulationStock untouched = restarted.find("SIM002").orElseThrow();
        assertNull(untouched.getManualPrice());
        assertNull(untouched.getManualTargetPercent());
        assertEquals(25.50, untouched.getInitPrice(), 1e-9);
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────────

    /** 不 bootstrap 的裸注册表（空池），用于显式驱动 {@code bootstrap()}。 */
    private SimulationStockRegistry bareRegistry() throws SQLException {
        return new SimulationStockRegistry(repository, config, Logger.getAnonymousLogger());
    }

    private static void assertThrowsUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("期望不可变集合抛出 UnsupportedOperationException");
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
