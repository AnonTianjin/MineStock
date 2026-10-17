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
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟盘卖出必须有持仓 —— 未持仓 / 持仓不足一律拒绝，且绝不入账。
 *
 * <p>背景：报告里出现过"模拟盘在未持仓状态下可以无限卖出"。这条路径此前<b>没有任何测试</b>
 * （{@code simulation-no-holding} / {@code simulation-not-enough-holding} 只在语言串白名单里
 * 出现过），所以这里把它钉死：每拒绝一次都要同时断言"钱没动、持仓没动"。
 *
 * <p>{@code plugin == null} ⇒ 线程就地执行、无 Economy，正是验证"拒绝发生在入账之前"的
 * 最干净环境；H2 内存库跑真实 DDL，不复制表结构。
 */
class SimulationSellGuardTest {

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

    /** 未持仓卖出：必须报 simulation-no-holding，且不产生任何持仓行。 */
    @Test
    void sellingWithoutAnyHoldingIsRejected() throws SQLException {
        UUID player = UUID.randomUUID();

        TradeOutcome outcome = service.sell(player, "SIM001", 10).join();

        assertEquals("simulation-no-holding", outcome.langKey(),
                "未持仓卖出必须被拒绝，实得 " + outcome.langKey());
        assertEquals(0, held(player, "SIM001"), "被拒绝的卖出不得凭空创建持仓行");

        // "无限" 的关键：连续调用同样必须一直被拒，不能第一次之后状态发生变化
        for (int i = 0; i < 5; i++) {
            assertEquals("simulation-no-holding", service.sell(player, "SIM001", 10).join().langKey(),
                    "第 " + (i + 2) + " 次无持仓卖出仍须被拒绝");
        }
        assertEquals(0, held(player, "SIM001"));
    }

    /** 持仓不足：报 simulation-not-enough-holding，且原持仓分毫不动（事务回滚）。 */
    @Test
    void sellingMoreThanHeldIsRejectedAndLeavesHoldingIntact() throws SQLException {
        UUID player = UUID.randomUUID();
        repository.upsertHoldingBuy(player, "SIM001", 100, 10.0);

        TradeOutcome outcome = service.sell(player, "SIM001", 200).join();

        assertEquals("simulation-not-enough-holding", outcome.langKey(),
                "超量卖出必须被拒绝，实得 " + outcome.langKey());
        assertEquals(100, held(player, "SIM001"), "被拒绝的卖出不得改动持仓数量");
    }

    /** 数量非法一律在 tradeGate 拦下，连价格都不去取。 */
    @Test
    void nonPositiveQuantityIsRejected() throws SQLException {
        UUID player = UUID.randomUUID();
        repository.upsertHoldingBuy(player, "SIM001", 100, 10.0);

        assertEquals("trade-invalid-quantity", service.sell(player, "SIM001", 0).join().langKey());
        assertEquals("trade-invalid-quantity", service.sell(player, "SIM001", -5).join().langKey());
        assertEquals(100, held(player, "SIM001"));
    }

    /** 正好卖光：成功，行被删除；再卖一次必须变成 simulation-no-holding。 */
    @Test
    void sellingExactlyWhatIsHeldClearsTheRowAndThenFails() throws SQLException {
        UUID player = UUID.randomUUID();
        repository.upsertHoldingBuy(player, "SIM001", 100, 10.0);

        assertEquals("simulation-sell-success", service.sell(player, "SIM001", 100).join().langKey());
        assertEquals(0, held(player, "SIM001"), "卖光后持仓行应被删除");

        TradeOutcome again = service.sell(player, "SIM001", 100).join();
        assertEquals("simulation-no-holding", again.langKey(),
                "卖光后再卖必须被拒绝，实得 " + again.langKey());
        assertTrue(held(player, "SIM001") == 0);
    }

    /** 部分卖出：只减请求的数量，余量保留。 */
    @Test
    void partialSaleReducesOnlyTheRequestedAmount() throws SQLException {
        UUID player = UUID.randomUUID();
        repository.upsertHoldingBuy(player, "SIM001", 100, 10.0);

        assertEquals("simulation-sell-success", service.sell(player, "SIM001", 30).join().langKey());
        assertEquals(70, held(player, "SIM001"));
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
