package dev.onelili.mstock.simulation;

import dev.onelili.mstock.database.DatabaseManager;
import dev.onelili.mstock.leverage.TradeOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理员注册 / 注销模拟股票，以及"卖出全部持仓"（{@link SimulationService#sellAll}）。
 *
 * <p>为什么要钉死代码格式：{@code SimulationStockSource.supports} 用
 * {@code ^[A-Z]{3}\d{3}$} 决定认不认领一只标的。注册时放过一个格式不符的代码，
 * 玩家就会看到一只"在管理列表里存在、却永远查不到价"的幽灵股票 —— 所以这里逐个
 * 形状地断言它被拒绝，而不是只测一个 {@code null}。
 */
class SimulationRegistryAdminTest {

    private static final String OK_ADD = "simulation-admin-add-ok";
    private static final String OK_REMOVE = "simulation-admin-remove-ok";

    private SimulationRepository repository;
    private SimulationService service;

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
        service = new SimulationService(null, database, null);
        service.registryView().put(new SimulationStock("SIM001", "模拟股 SIM001", "科技", 10.0, 100L));
    }

    // ── 注册 ─────────────────────────────────────────────────────────────────

    /** 代码形状：每一条都必须被拒，且不得进内存。 */
    @Test
    void registerStockRejectsCodesTheSimulationSourceWouldNotClaim() {
        String[] badCodes = {"SIM01", "SIM0001", "SI0001", "SIM0A1", "SIM-11", "11SIM", "", "   "};

        for (String bad : badCodes) {
            TradeOutcome outcome = service.registerStock(bad, "名称", "板块", 10.0).join();
            assertEquals("simulation-admin-invalid-args", outcome.langKey(),
                    "代码 [" + bad + "] 必须被拒，实得 " + outcome.langKey());
        }
        assertFalse(service.registryView().contains("SIM011"), "被拒的注册不得进入内存");
    }

    /** 名称 / 板块 / 初始价任一不合法都拒绝。 */
    @Test
    void registerStockRejectsBlankMetadataAndBadInitialPrice() {
        assertEquals("simulation-admin-invalid-args",
                service.registerStock("SIM011", "  ", "板块", 10.0).join().langKey());
        assertEquals("simulation-admin-invalid-args",
                service.registerStock("SIM011", "名称", "  ", 10.0).join().langKey());
        assertEquals("simulation-admin-invalid-args",
                service.registerStock("SIM011", "名称", "板块", 0.0).join().langKey());
        assertEquals("simulation-admin-invalid-args",
                service.registerStock("SIM011", "名称", "板块", Double.NaN).join().langKey());
        assertEquals("simulation-admin-invalid-args",
                service.registerStock("SIM011", "名称", "板块", -1.0).join().langKey());
        assertFalse(service.registryView().contains("SIM011"));
    }

    /** 重名必须被明确拒绝，而不是静默覆盖原标的（覆盖会改掉别人的持仓成本锚点）。 */
    @Test
    void registerStockRejectsDuplicates() {
        TradeOutcome outcome = service.registerStock("SIM001", "冒名", "板块", 20.0).join();

        assertEquals("simulation-admin-add-exists", outcome.langKey(),
                "重名注册必须被拒，实得 " + outcome.langKey());
        assertEquals("模拟股 SIM001", service.findStock("SIM001").orElseThrow().getName(),
                "被拒的重名注册不得改动已有标的");
    }

    /** 正常注册：成功回执 + 进内存 + 落库（重开一个 repository 也读得到）。 */
    @Test
    void registerStockAddsTheStockAndPersistsIt() throws SQLException {
        TradeOutcome outcome = service.registerStock("SIM011", "模拟银行", "金融", 12.5).join();

        assertEquals(OK_ADD, outcome.langKey(), "注册应成功，实得 " + outcome.langKey());
        SimulationStock added = service.findStock("SIM011").orElseThrow();
        assertEquals("模拟银行", added.getName());
        assertEquals("金融", added.getSector());
        assertEquals(12.5, added.getCurrentPrice(), 0.0);
        assertTrue(added.getSeed() > 0L, "新标的必须拿到一个正种子");

        List<SimulationStock> persisted = repository.findAllStocks();
        assertTrue(persisted.stream().anyMatch(s -> "SIM011".equals(s.getCode())),
                "注册必须落库，库里实得 " + persisted.size() + " 行");
    }

    /** 小写输入被规范化成大写后照常工作（normalize 是代码契约的一部分）。 */
    @Test
    void registerStockNormalizesLowerCaseCodes() {
        assertEquals(OK_ADD, service.registerStock("sim012", "小写", "板块", 8.0).join().langKey());
        assertTrue(service.findStock("SIM012").isPresent(), "小写代码应被规范化为大写后注册");
    }

    // ── 注销 ─────────────────────────────────────────────────────────────────

    /** 注销不存在的代码：明确报 missing。 */
    @Test
    void unregisterStockRejectsUnknownCodes() {
        assertEquals("simulation-admin-remove-missing",
                service.unregisterStock("SIM999").join().langKey());
        assertEquals("simulation-admin-invalid-args",
                service.unregisterStock("  ").join().langKey());
    }

    /** 注销已有代码：移出内存 + 删库，且玩家持仓与 K 线刻意保留。 */
    @Test
    void unregisterStockRemovesOnlyTheStockAndKeepsPlayerData() throws SQLException {
        UUID player = UUID.randomUUID();
        repository.upsertHoldingBuy(player, "SIM001", 40, 10.0);

        assertEquals(OK_REMOVE, service.unregisterStock("SIM001").join().langKey());

        assertFalse(service.registryView().contains("SIM001"), "注销后不得再出现在行情里");
        assertTrue(repository.findAllStocks().stream().noneMatch(s -> "SIM001".equals(s.getCode())),
                "注销必须同步删库");
        SimulationHolding kept = repository.findHolding(player, "SIM001");
        assertTrue(kept != null && kept.getAmount() == 40,
                "误删后重新注册要能接着用，持仓不得被注销动作清掉");
    }

    // ── 卖出全部 ─────────────────────────────────────────────────────────────

    /** 没有持仓：拒绝，且不产生持仓行。 */
    @Test
    void sellAllWithoutHoldingIsRejected() throws SQLException {
        UUID player = UUID.randomUUID();

        TradeOutcome outcome = service.sellAll(player, "SIM001").join();

        assertEquals("simulation-no-holding", outcome.langKey(),
                "无持仓的清仓必须被拒，实得 " + outcome.langKey());
        assertEquals(0, held(player, "SIM001"));
    }

    /** 有持仓：一次卖光，行被删除；再调用变成 no-holding。 */
    @Test
    void sellAllLiquidatesTheWholeCashPosition() throws SQLException {
        UUID player = UUID.randomUUID();
        repository.upsertHoldingBuy(player, "SIM001", 100, 10.0);

        assertEquals("simulation-sell-success", service.sellAll(player, "SIM001").join().langKey());
        assertEquals(0, held(player, "SIM001"), "清仓后持仓行应被删除");
        assertEquals("simulation-no-holding", service.sellAll(player, "SIM001").join().langKey());
    }

    /** 空代码走拒绝分支，不得抛 NPE。 */
    @Test
    void sellAllWithBlankCodeIsRejected() {
        assertEquals("simulation-no-holding",
                service.sellAll(UUID.randomUUID(), "  ").join().langKey());
        assertEquals("simulation-no-holding",
                service.sellAll(UUID.randomUUID(), null).join().langKey());
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────────

    private int held(UUID player, String code) throws SQLException {
        SimulationHolding holding = repository.findHolding(player, code);
        return holding == null ? 0 : holding.getAmount();
    }

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
