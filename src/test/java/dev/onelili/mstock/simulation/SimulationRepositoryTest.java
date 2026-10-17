package dev.onelili.mstock.simulation;

import dev.onelili.mstock.database.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟盘持久化测试。
 *
 * <p>沿用 {@code TradingRepositoryTest} 的 H2 内存库范式：覆写
 * {@code DatabaseManager#getConnection} 指向 {@code jdbc:h2:mem:<随机 UUID>;MODE=MySQL;DB_CLOSE_DELAY=-1}，
 * 每个测试一个独立库，不启动任何服务端。
 *
 * <p><b>DDL 不复制</b>：本测试直接调用 {@code DatabaseManager} 的包级
 * {@code createTables()} 跑<b>真实</b>的 5 张 {@code simulation_*} 表建表语句，避免
 * "测试建的表对、生产的表错"的漂移。由于本测试类位于 {@code dev.onelili.mstock.simulation}
 * 包、而 {@code createTables()} 是 {@code dev.onelili.mstock.database} 包的包级方法，
 * 这里通过反射调用（不修改 {@code DatabaseManager} 的可见性）。
 */
class SimulationRepositoryTest {

    private SimulationRepository repository;
    private DatabaseManager database;
    private UUID playerUuid;

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
        playerUuid = UUID.randomUUID();
    }

    @Test
    void createsAllSimulationTablesOnH2MysqlMode() throws SQLException {
        String[] tables = {"simulation_stocks", "simulation_holdings", "simulation_links",
                "simulation_schedules", "simulation_klines"};
        for (String table : tables) {
            try (Connection connection = database.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                assertTrue(rs.next(), table + " 不可查询");
                assertEquals(0, rs.getInt(1), table + " 初始应为空");
            }
        }

        // R5 回归：BOOLEAN 列在 H2 + MODE=MySQL 下必须能用 setBoolean/getBoolean 往返
        long id = repository.insertSchedule("SIM001", "09:30", 5.0, true);
        assertTrue(id > 0);
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT repeat_daily, enabled FROM simulation_schedules WHERE id = " + id)) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("repeat_daily"));
            assertTrue(rs.getBoolean("enabled"));
        }
    }

    @Test
    void upsertsStockAndReadsItBack() throws SQLException {
        SimulationStock stock = new SimulationStock("SIM001", "模拟股 01", "科技", 10.0, 7L,
                5.0, SimulationVolatilityPreset.HIGH, SimulationBoundary.HARD_CLAMP, 0.05, 500.0);
        stock.setPrevClose(9.5);
        stock.setCurrentPrice(10.4);
        stock.setAutoPrice(10.3);
        stock.setAnchor(10.0);
        stock.setMu(0.001);
        stock.setSigma(0.002);
        stock.setTheta(0.06);
        stock.setJumpLambda(0.007);
        stock.setJumpMagnitude(0.03);
        stock.setAmplitude(1.5);
        stock.setSuspended(true);
        stock.setTradable(false);
        stock.setPaused(true);
        stock.setSlopePerMs(0.0004);
        stock.setSlopeUntil(1_800_000_000_000L);
        stock.setUpdatedAt(1_700_000_000_000L);

        repository.upsertStock(stock);

        List<SimulationStock> all = repository.findAllStocks();
        assertEquals(1, all.size());
        SimulationStock back = all.get(0);
        assertEquals("SIM001", back.getCode());
        assertEquals("模拟股 01", back.getName());
        assertEquals("科技", back.getSector());
        assertEquals(10.0, back.getInitPrice(), 1e-9);
        assertEquals(7L, back.getSeed());
        assertEquals(5.0, back.getLimitPercent(), 1e-9);
        assertEquals(SimulationVolatilityPreset.HIGH, back.getPreset());
        // id() 写库为 "hard_clamp"，parse() 的别名表只认 "hard"/"clamp" —— 必须能读回同一枚举
        assertEquals(SimulationBoundary.HARD_CLAMP, back.getBoundary());
        assertEquals(0.05, back.getMinPrice(), 1e-9);
        assertEquals(500.0, back.getMaxPrice(), 1e-9);
        assertEquals(9.5, back.getPrevClose(), 1e-9);
        assertEquals(10.4, back.getCurrentPrice(), 1e-9);
        assertEquals(10.3, back.getAutoPrice(), 1e-9);
        assertEquals(10.0, back.getAnchor(), 1e-9);
        assertEquals(0.001, back.getMu(), 1e-9);
        assertEquals(0.002, back.getSigma(), 1e-9);
        assertEquals(0.06, back.getTheta(), 1e-9);
        assertEquals(0.007, back.getJumpLambda(), 1e-9);
        assertEquals(0.03, back.getJumpMagnitude(), 1e-9);
        assertEquals(1.5, back.getAmplitude(), 1e-9);
        assertTrue(back.isSuspended());
        assertFalse(back.isTradable());
        assertTrue(back.isPaused());
        assertEquals(0.0004, back.getSlopePerMs(), 1e-9);
        assertEquals(1_800_000_000_000L, back.getSlopeUntil());
        assertEquals(1_700_000_000_000L, back.getUpdatedAt());
        // 两个覆盖字段可为 NULL，必须原样读回 null
        assertNull(back.getManualPrice());
        assertNull(back.getManualTargetPercent());
    }

    @Test
    void upsertStockUpdatesInsteadOfInserting() throws SQLException {
        repository.upsertStock(new SimulationStock("SIM001", "旧名", "科技", 10.0, 1L));

        SimulationStock updated = new SimulationStock("SIM001", "新名", "金融", 20.0, 2L);
        updated.setCurrentPrice(20.5);
        updated.setManualTargetPercent(3.0);
        repository.upsertStock(updated);

        // 走 ON DUPLICATE KEY UPDATE（update 分支 executeUpdate 返回 2，故判成功只能用 > 0），
        // 且子句点名的每一列都必须被真正改写
        List<SimulationStock> all = repository.findAllStocks();
        assertEquals(1, all.size());
        assertEquals("新名", all.get(0).getName());
        assertEquals("金融", all.get(0).getSector());
        assertEquals(20.0, all.get(0).getInitPrice(), 1e-9);
        assertEquals(20.5, all.get(0).getCurrentPrice(), 1e-9);
        assertEquals(2L, all.get(0).getSeed());
        assertEquals(3.0, all.get(0).getManualTargetPercent(), 1e-9);
        assertNull(all.get(0).getManualPrice());
    }

    @Test
    void deletesStock() throws SQLException {
        repository.upsertStock(new SimulationStock("SIM001", "模拟股 01", "科技", 10.0, 1L));

        assertTrue(repository.deleteStock("SIM001"));
        assertTrue(repository.findAllStocks().isEmpty());
        // 二次删除说明已被并发删除，服务层据此避免重复出金
        assertFalse(repository.deleteStock("SIM001"));
        assertTrue(repository.findAllStocks().isEmpty());
    }

    @Test
    void upsertsHoldingWithWeightedAverageCost() throws SQLException {
        repository.upsertHoldingBuy(playerUuid, "SIM001", 10, 10.0);
        long beforeSecondBuy = System.currentTimeMillis();
        repository.upsertHoldingBuy(playerUuid, "SIM001", 10, 20.0);

        SimulationHolding holding = repository.findHolding(playerUuid, "SIM001");
        assertNotNull(holding);
        assertEquals(20, holding.getAmount());
        // (10*10 + 10*20) / 20 = 15
        assertEquals(15.0, holding.getAvgCost(), 1e-9);
        assertEquals(20.0, holding.getLastPrice(), 1e-9);
        assertTrue(holding.getLastFetched() >= beforeSecondBuy, "last_fetched 应被刷新");
        assertEquals(1, repository.findHoldings(playerUuid).size());
    }

    @Test
    void reduceHoldingRejectsInsufficientAmount() throws SQLException {
        repository.upsertHoldingBuy(playerUuid, "SIM001", 5, 10.0);

        // 持仓不足用返回值表达（而非异常），且必须回滚 —— 持仓保持原值
        assertFalse(repository.reduceHoldingSell(playerUuid, "SIM001", 10, 11.0));

        SimulationHolding holding = repository.findHolding(playerUuid, "SIM001");
        assertNotNull(holding);
        assertEquals(5, holding.getAmount());
        assertEquals(10.0, holding.getAvgCost(), 1e-9);
        assertEquals(10.0, holding.getLastPrice(), 1e-9);
    }

    @Test
    void reduceHoldingDeletesRowWhenAmountReachesZero() throws SQLException {
        repository.upsertHoldingBuy(playerUuid, "SIM001", 10, 10.0);

        assertTrue(repository.reduceHoldingSell(playerUuid, "SIM001", 10, 12.0));

        assertNull(repository.findHolding(playerUuid, "SIM001"));
        assertTrue(repository.findHoldings(playerUuid).isEmpty());
    }

    @Test
    void holdingsAreIsolatedPerPlayer() throws SQLException {
        UUID other = UUID.randomUUID();
        repository.upsertHoldingBuy(playerUuid, "SIM001", 10, 10.0);
        repository.upsertHoldingBuy(other, "SIM001", 4, 30.0);

        // UNIQUE(player_uuid, code)：两个玩家各占一行，互不覆盖
        assertEquals(1, repository.findHoldings(playerUuid).size());
        assertEquals(1, repository.findHoldings(other).size());
        assertEquals(10, repository.findHolding(playerUuid, "SIM001").getAmount());
        assertEquals(4, repository.findHolding(other, "SIM001").getAmount());
        assertEquals(30.0, repository.findHolding(other, "SIM001").getAvgCost(), 1e-9);
    }

    @Test
    void linkUpsertIsIdempotentPerPair() throws SQLException {
        repository.upsertLink("SIM001", "SIM002", 0.5);
        // 反向写入必须先归一化顺序（code_a < code_b），否则 UNIQUE(code_a, code_b) 形同虚设
        repository.upsertLink("SIM002", "SIM001", 0.9);

        List<SimulationLink> links = repository.findLinks();
        assertEquals(1, links.size());
        assertEquals("SIM001", links.get(0).codeA());
        assertEquals("SIM002", links.get(0).codeB());
        assertEquals(0.9, links.get(0).correlation(), 1e-9);
    }

    @Test
    void linkCorrelationIsClampedAndSelfLinkIsRejected() throws SQLException {
        repository.upsertLink("SIM001", "SIM002", 5.0);
        repository.upsertLink("SIM001", "SIM003", -5.0);
        // 自环必须被拒（不产生 code_a == code_b 的行）
        repository.upsertLink("SIM004", "SIM004", 0.5);

        List<SimulationLink> links = repository.findLinks();
        assertEquals(2, links.size());
        assertEquals(0.999, links.get(0).correlation(), 1e-9);
        assertEquals(-0.999, links.get(1).correlation(), 1e-9);
    }

    @Test
    void deleteLinkAndDeleteLinksForRemoveTheRightRows() throws SQLException {
        repository.upsertLink("SIM001", "SIM002", 0.5);
        repository.upsertLink("SIM001", "SIM003", 0.5);
        repository.upsertLink("SIM004", "SIM005", 0.5);

        assertTrue(repository.deleteLink("SIM002", "SIM001"));
        assertFalse(repository.deleteLink("SIM002", "SIM001"));

        repository.deleteLinksFor("SIM001");
        List<SimulationLink> links = repository.findLinks();
        assertEquals(1, links.size());
        assertEquals("SIM004", links.get(0).codeA());
    }

    @Test
    void scheduleInsertReturnsGeneratedKey() throws SQLException {
        long id = repository.insertSchedule("SIM001", "09:30", 5.0, true);

        assertTrue(id > 0, "自增主键必须回填");
        List<SimulationSchedule> schedules = repository.findSchedules();
        assertEquals(1, schedules.size());
        assertEquals(id, schedules.get(0).id());
        assertEquals("SIM001", schedules.get(0).code());
        assertEquals("09:30", schedules.get(0).timeOfDay());
        assertEquals(5.0, schedules.get(0).changePercent(), 1e-9);
        assertTrue(schedules.get(0).repeatDaily());
        assertTrue(schedules.get(0).enabled());
        assertEquals("", schedules.get(0).lastFiredDate());

        assertTrue(repository.setScheduleEnabled(id, false));
        assertFalse(repository.findSchedules().get(0).enabled());

        assertTrue(repository.deleteSchedule(id));
        assertFalse(repository.setScheduleEnabled(id, true));
        assertTrue(repository.findSchedules().isEmpty());
    }

    @Test
    void markScheduleFiredPersistsDateAndDisablesOnce() throws SQLException {
        long once = repository.insertSchedule("SIM001", "09:30", 5.0, false);
        assertTrue(repository.markScheduleFired(once, "2026-01-02", true));
        SimulationSchedule oneShot = repository.findSchedules().get(0);
        assertEquals("2026-01-02", oneShot.lastFiredDate());
        assertFalse(oneShot.enabled(), "repeatDaily=false 触发一次后必须禁用");
        assertFalse(oneShot.repeatDaily());

        // disable=false 分支不得触碰 enabled：每日重复的 schedule 必须保持启用
        long daily = repository.insertSchedule("SIM001", "10:00", 3.0, true);
        assertTrue(repository.markScheduleFired(daily, "2026-01-02", false));
        SimulationSchedule repeating = findByScheduleId(repository.findSchedules(), daily);
        assertNotNull(repeating);
        assertEquals("2026-01-02", repeating.lastFiredDate());
        assertTrue(repeating.enabled());
    }

    @Test
    void deleteScheduleReturnsFalseForUnknownId() throws SQLException {
        assertFalse(repository.deleteSchedule(-1L));
        assertFalse(repository.deleteSchedule(12345L));
    }

    @Test
    void upsertsKLineAndReadsRangeInOrder() throws SQLException {
        repository.upsertKLine("SIM001", new SimulationBar(180_000L, 10.2, 10.6, 10.1, 10.5));
        repository.upsertKLine("SIM001", new SimulationBar(60_000L, 10.0, 10.1, 9.9, 10.05));
        repository.upsertKLine("SIM001", new SimulationBar(120_000L, 10.1, 10.4, 10.0, 10.3));

        List<SimulationBar> bars = repository.findKlines("SIM001", 120_000L);
        assertEquals(2, bars.size());
        assertEquals(120_000L, bars.get(0).bucketStart());
        assertEquals(180_000L, bars.get(1).bucketStart());
        assertEquals(10.1, bars.get(0).open(), 1e-9);
        assertEquals(10.4, bars.get(0).high(), 1e-9);
        assertEquals(10.0, bars.get(0).low(), 1e-9);
        assertEquals(10.3, bars.get(0).close(), 1e-9);
    }

    @Test
    void upsertKLineOverwritesTheSameBucket() throws SQLException {
        repository.upsertKLine("SIM001", new SimulationBar(60_000L, 10.0, 11.0, 9.0, 10.0));
        repository.upsertKLine("SIM001", new SimulationBar(60_000L, 10.0, 12.5, 8.5, 12.0));

        List<SimulationBar> bars = repository.findKlines("SIM001", 0L);
        assertEquals(1, bars.size());
        assertEquals(12.5, bars.get(0).high(), 1e-9);
        assertEquals(8.5, bars.get(0).low(), 1e-9);
        assertEquals(12.0, bars.get(0).close(), 1e-9);
    }

    @Test
    void upsertManyKLinesWritesTheWholeBatch() throws SQLException {
        repository.upsertKLines("SIM001", List.of(
                new SimulationBar(60_000L, 10.0, 10.2, 9.9, 10.1),
                new SimulationBar(120_000L, 10.1, 10.5, 10.0, 10.4)));

        assertEquals(2, repository.findKlines("SIM001", 0L).size());
    }

    @Test
    void klinesAreIsolatedPerCode() throws SQLException {
        repository.upsertKLine("SIM001", new SimulationBar(60_000L, 10.0, 10.0, 10.0, 10.0));
        repository.upsertKLine("SIM001", new SimulationBar(120_000L, 11.0, 11.0, 11.0, 11.0));
        repository.upsertKLine("SIM002", new SimulationBar(60_000L, 25.0, 25.0, 25.0, 25.0));
        repository.upsertKLine("SIM002", new SimulationBar(120_000L, 26.0, 26.0, 26.0, 26.0));

        assertEquals(2, repository.findKlines("SIM001", 0L).size());
        assertEquals(2, repository.findKlines("SIM002", 0L).size());
        assertEquals(10.0, repository.findKlines("SIM001", 0L).get(0).close(), 1e-9);
        assertEquals(25.0, repository.findKlines("SIM002", 0L).get(0).close(), 1e-9);
        assertTrue(repository.findKlines("SIM999", 0L).isEmpty());
    }

    @Test
    void klineOfAnotherCodeAndBucketDoesNotCollide() throws SQLException {
        // 复合主键 (code, bucket_start)：不同 code 的同一 bucket_start 必须允许共存
        repository.upsertKLine("SIM001", new SimulationBar(60_000L, 10.0, 10.0, 10.0, 10.0));
        repository.upsertKLine("SIM002", new SimulationBar(60_000L, 25.0, 25.0, 25.0, 25.0));
        repository.upsertKLine("SIM001", new SimulationBar(120_000L, 11.0, 11.0, 11.0, 11.0));

        assertEquals(2, repository.findKlines("SIM001", 0L).size());
        assertEquals(1, repository.findKlines("SIM002", 0L).size());
        assertEquals(1, repository.findKlines("SIM001", 120_000L).size());
    }

    @Test
    void deleteStockCascadesAreExplicitByCaller() throws SQLException {
        repository.upsertStock(new SimulationStock("SIM001", "模拟股 01", "科技", 10.0, 1L));
        repository.upsertHoldingBuy(playerUuid, "SIM001", 10, 10.0);
        repository.upsertLink("SIM001", "SIM002", 0.5);
        repository.upsertKLine("SIM001", new SimulationBar(60_000L, 10.0, 10.0, 10.0, 10.0));

        assertTrue(repository.deleteStock("SIM001"));

        // 契约：本仓储不做级联删除。级联由 SimulationStockRegistry.remove 显式完成
        // （删股票行 + 联动 + 定时）；持仓与 K 线历史刻意保留，避免误删玩家资产与历史。
        assertNotNull(repository.findHolding(playerUuid, "SIM001"));
        assertEquals(1, repository.findLinks().size());
        assertEquals(1, repository.findKlines("SIM001", 0L).size());
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────────

    private static SimulationSchedule findByScheduleId(List<SimulationSchedule> schedules, long id) {
        for (SimulationSchedule schedule : schedules) {
            if (schedule.id() == id) return schedule;
        }
        return null;
    }

    /**
     * 反射调用包级的 {@code DatabaseManager#createTables()}。
     *
     * <p>本测试类在 {@code ...simulation} 包，无法直接访问 {@code ...database} 包的包级方法；
     * 反射是"既不复制 DDL、也不改 {@code DatabaseManager}"的唯一途径。
     */
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
