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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联动语义测试。
 *
 * <p><b>为什么这里能真测编排层</b>：{@link SimulationService} 的线程模型是
 * "有 {@code plugin} 就走 {@code LifecycleTaskManager}，没有就地执行"，
 * 因此用 {@code new SimulationService(null, h2Database, null)} 就能在离线 JUnit 里
 * <b>驱动真实的 {@code link} / {@code unlink} / 管理入口</b>，配真实 DDL 的 H2 内存库做断言。
 * 这也让 {@code linkRhoFor} 的"求和后夹取"落在真实代码路径上，而不是复刻一份副本。
 *
 * <p>凡是在 {@code plugin == null} 下走不到的分支（{@code simulation.enabled: false}、
 * {@code LifecycleTaskManager} 的异步线程切换、Economy 扣款），本类<b>不</b>声称覆盖，
 * 属于离线覆盖不到的盲区。
 */
class SimulationLinkTest {

    private DatabaseManager database;
    private SimulationRepository repository;
    private SimulationService service;

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
        Logger logger = Logger.getAnonymousLogger();
        repository = new SimulationRepository(database, logger);
        // plugin == null ⇒ 管理入口与查询全部就地执行，无需 Bukkit 运行时
        service = new SimulationService(null, database, null);
    }

    // ── 纯函数：相关系数化 ────────────────────────────────────────────────────

    /** {@code rho == 0} 时与独立抽样<b>逐字相等</b>（同一 z / group 的 1000 次抽样）。 */
    @Test
    void zeroCorrelationEqualsIndependentDraw() {
        for (int i = 0; i < 1000; i++) {
            double z = gaussian(i);
            double group = gaussian(i + 7919);
            assertEquals(z, SimulationPriceEngine.correlatedZ(0.0, group, z), 0.0,
                    "rho=0 ⇒ 0*group + sqrt(1)*z，IEEE754 下必须逐字等于 zRaw");
        }
    }

    /** {@code rho} 被夹到 0.999 后按公式计算。 */
    @Test
    void perfectCorrelationFollowsGroupFactor() {
        double z = -5.0;
        double group = 2.0;
        double expected = 0.999 * group + Math.sqrt(1.0 - 0.999 * 0.999) * z;
        assertEquals(expected, SimulationPriceEngine.correlatedZ(0.999, group, z), 1e-9);
        // 传 1.0 也会被夹到 0.999：否则 sqrt(1-1)=0，结果恰好等于 group，掩盖夹取失效
        assertEquals(expected, SimulationPriceEngine.correlatedZ(1.0, group, z), 1e-9);
    }

    /** 负相关：共同因子上涨时该股下行。 */
    @Test
    void negativeCorrelationInvertsGroupMove() {
        assertTrue(SimulationPriceEngine.correlatedZ(-0.8, 3.0, 0.0) < 0.0);
    }

    /**
     * 同 sector 的两只股拿到<b>同一个</b>共同因子，不同 sector 拿到不同的。
     *
     * <p>复刻 {@code tick()} 第 ② 步的 {@code computeIfAbsent}（3 行结构），
     * 断言其可观察后果：同 sector 的两只股在相同 rho / zRaw 下 z' 完全相等。
     * {@code tick()} 里该循环本身由端到端冒烟验证。
     */
    @Test
    void sameSectorDrawsOneSharedGroupFactorPerTick() {
        java.util.Map<String, Double> groupZ = new java.util.HashMap<>();
        java.util.Random groupRandom = new java.util.Random(7L);
        for (String sector : List.of("科技", "科技", "金融")) {
            groupZ.computeIfAbsent(sector, key -> groupRandom.nextGaussian());
        }

        assertEquals(2, groupZ.size(), "同 sector 只抽一个共同因子");
        assertNotEquals(groupZ.get("科技"), groupZ.get("金融"), 0.0,
                "不同 sector 必须是独立抽样的因子");

        double rho = 0.7;
        double sameSectorA = SimulationPriceEngine.correlatedZ(rho, groupZ.get("科技"), 1.25);
        double sameSectorB = SimulationPriceEngine.correlatedZ(rho, groupZ.get("科技"), 1.25);
        assertEquals(sameSectorA, sameSectorB, 0.0, "同 sector + 同 rho ⇒ 同一 z'");
        assertNotEquals(sameSectorA,
                SimulationPriceEngine.correlatedZ(rho, groupZ.get("金融"), 1.25), 1e-12);
    }

    /** 多边求和后的 rho 仍被夹取，避免 {@code sqrt(1-rho^2)} 退化。 */
    @Test
    void correlationIsClampedToAvoidDegenerateSpread() {
        // linkRhoFor 的真实夹取：三条 0.5/1.0 的联动求和越界 → 夹到 ±0.999
        List<SimulationLink> positive = List.of(
                SimulationLink.of("SIM001", "SIM002", 1.0),
                SimulationLink.of("SIM001", "SIM003", 1.0),
                SimulationLink.of("SIM001", "SIM004", 1.0));
        assertEquals(0.999, SimulationService.linkRhoFor("SIM001", positive), 1e-12);

        List<SimulationLink> negative = List.of(
                SimulationLink.of("SIM001", "SIM002", -1.0),
                SimulationLink.of("SIM001", "SIM003", -1.0),
                SimulationLink.of("SIM001", "SIM004", -1.0));
        assertEquals(-0.999, SimulationService.linkRhoFor("SIM001", negative), 1e-12);

        double spread = Math.sqrt(1.0 - 0.999 * 0.999);
        assertTrue(spread > 0.0 && Double.isFinite(spread), "夹取后 spread 不得退化为 0 或 NaN");
    }

    /** 未联动的股票 rho 恒为 {@code 0.0} ⇒ z' 逐字等于 zRaw（默认每股独立 GBM）。 */
    @Test
    void unlinkedStockIsUnaffectedByGroupFactor() {
        assertEquals(0.0, SimulationService.linkRhoFor("SIM001", List.of()), 0.0);
        for (int i = 0; i < 200; i++) {
            double zRaw = gaussian(i);
            assertEquals(zRaw, SimulationPriceEngine.correlatedZ(
                    SimulationService.linkRhoFor("SIM001", List.of()), 12.34, zRaw), 0.0);
        }

        // 只有 A、B 联动、C 不联动
        List<SimulationLink> links = List.of(SimulationLink.of("SIM001", "SIM002", 0.5));
        assertEquals(0.5, SimulationService.linkRhoFor("SIM001", links), 1e-12);
        assertEquals(0.5, SimulationService.linkRhoFor("SIM002", links), 1e-12);
        assertEquals(0.0, SimulationService.linkRhoFor("SIM003", links), 0.0);
    }

    // ── 真实 SimulationService.link / unlink ────────────────────────────────

    /** rho=1.0 存库前夹到 0.999；rho=-5 夹到 -0.999（读回断言）。 */
    @Test
    void linkClampsCorrelationBeforePersisting() throws SQLException {
        register("SIM001", "SIM002", "SIM003");

        assertTrue(service.link("SIM001", "SIM002", 1.0).join().success());
        assertTrue(service.link("SIM001", "SIM003", -5.0).join().success());

        List<SimulationLink> stored = repository.findLinks();
        assertEquals(2, stored.size());
        assertEquals(0.999, stored.get(0).correlation(), 1e-12, "rho=1.0 必须被夹到 0.999");
        assertEquals(-0.999, stored.get(1).correlation(), 1e-12, "rho=-5 必须被夹到 -0.999");

        // 夹取来自 SimulationLink.of，因此读回的 rho 与纯值类型逐字一致
        assertEquals(SimulationLink.of("SIM001", "SIM002", 1.0).correlation(),
                stored.get(0).correlation(), 0.0);
    }

    /** 顺序归一化：{@code link(A,B)} 与 {@code link(B,A)} 只产生一行（UNIQUE 生效）。 */
    @Test
    void linkIsStoredInNormalizedOrder() throws SQLException {
        register("SIM001", "SIM002");

        assertTrue(service.link("SIM002", "SIM001", 0.5).join().success());
        assertTrue(service.link("SIM001", "SIM002", 0.9).join().success());

        List<SimulationLink> links = repository.findLinks();
        assertEquals(1, links.size(), "UNIQUE(code_a, code_b) 必须真正去重");
        assertEquals("SIM001", links.get(0).codeA(), "code_a 必须是字典序小的一方");
        assertEquals("SIM002", links.get(0).codeB());
        assertEquals(0.9, links.get(0).correlation(), 1e-12, "第二次写入是更新而非新增");

        // 归一化在纯值类型上同样成立（不依赖 DB）
        assertEquals(SimulationLink.of("SIM002", "SIM001", 0.5),
                SimulationLink.of("SIM001", "SIM002", 0.5));
    }

    /** 自环被拒：返回 {@code simulation-admin-invalid-args}，且不产生任何行。 */
    @Test
    void selfLinkIsRejected() throws SQLException {
        register("SIM001", "SIM002");

        TradeOutcome outcome = service.link("SIM001", "SIM001", 0.5).join();
        assertFalse(outcome.success(), "自环必须被拒");
        assertEquals("simulation-admin-invalid-args", outcome.langKey());
        assertTrue(repository.findLinks().isEmpty(), "自环不得落库");

        // 大小写 / 空白差异不得绕过自环判定（注册表查找是归一化的）
        assertFalse(service.link("sim002", " SIM002 ", 0.5).join().success());
        assertTrue(repository.findLinks().isEmpty());

        // 非有限 rho 同样是非法参数，不是静默写成 0
        assertFalse(service.link("SIM001", "SIM002", Double.NaN).join().success());
        assertTrue(repository.findLinks().isEmpty());

        // 未注册代码 → unknown-code（而不是 invalid-args）
        assertEquals("simulation-unknown-code",
                service.link("SIM001", "SIM999", 0.5).join().langKey());
    }

    /** {@code unlink} 删掉该行；再次 unlink 返回失败。 */
    @Test
    void unlinkRemovesTheRow() throws SQLException {
        register("SIM001", "SIM002", "SIM003");
        service.link("SIM001", "SIM002", 0.5).join();
        service.link("SIM001", "SIM003", 0.5).join();
        assertEquals(2, repository.findLinks().size());

        assertTrue(service.unlink("SIM002", "SIM001").join().success(),
                "反向入参同样能删到（顺序归一化）");
        assertFalse(service.unlink("SIM002", "SIM001").join().success(),
                "已删除后再删必须失败而非静默成功");

        List<SimulationLink> links = repository.findLinks();
        assertEquals(1, links.size());
        assertEquals("SIM003", links.get(0).codeB());
        // 联动断开后该股回到独立 GBM
        assertEquals(0.0, SimulationService.linkRhoFor("SIM002", links), 0.0);
    }

    // ── 管理入口的统一前置校验（真实服务）────────────────────────────────────

    /** 未注册代码 → {@code simulation-unknown-code}（存在性判定在启用判定之前）。 */
    @Test
    void unknownCodeIsRejectedBeforeAnythingElse() {
        register("SIM001", "SIM002");

        assertEquals("simulation-unknown-code", service.setPrice("SIM999", 1.0).join().langKey());
        assertEquals("simulation-unknown-code", service.unlink("SIM999", "SIM001").join().langKey());
        assertEquals("simulation-unknown-code", service.unlink("SIM001", "SIM999").join().langKey());
        assertEquals("simulation-unknown-code", service.resume("SIM999").join().langKey());
        // 代码展示形式被归一化（不把 null / 原始大小写塞进占位符）
        TradeOutcome outcome = service.setPrice("sim888", 1.0).join();
        assertEquals("SIM888", outcome.placeholders()[1]);
    }

    /** 参数越界 → {@code simulation-admin-invalid-args}，且不改动任何状态。 */
    @Test
    void outOfRangeArgumentsAreRejectedWithoutMutatingState() throws SQLException {
        register("SIM001");
        SimulationStock stock = service.findStock("SIM001").orElseThrow();
        double priceBefore = stock.getCurrentPrice();

        String[] keys = {
                service.setPrice("SIM001", Double.NaN).join().langKey(),
                service.setPrice("SIM001", 0.0).join().langKey(),
                service.setPrice("SIM001", 0.005).join().langKey(),
                service.setTargetPercent("SIM001", Double.NaN).join().langKey(),
                service.jump("SIM001", Double.NaN).join().langKey(),
                service.setAmplitude("SIM001", -1.0).join().langKey(),
                service.setLimitPercent("SIM001", 0.0).join().langKey(),
                service.setLimitPercent("SIM001", 11.0).join().langKey(),
                service.setSlope("SIM001", 0.001, 0L).join().langKey(),
                service.schedule("SIM001", "9:30", 5.0, true).join().langKey(),
                service.unschedule(0L).join().langKey(),
        };
        for (String key : keys) {
            assertEquals("simulation-admin-invalid-args", key);
        }

        // min >= max 的退化区间必须被拒，且报「区间顺序错」而不是泛化的参数错——
        // 玩家填的是合法数字，错的是先后顺序，泛化提示会让人以为是自己格式写错了。
        assertEquals("simulation-admin-bounds-order",
                service.setBounds("SIM001", 10.0, 10.0).join().langKey());
        assertEquals("simulation-admin-bounds-order",
                service.setBounds("SIM001", 20.0, 10.0).join().langKey());
        // 与上面两条互补：非有限值 / 越界仍然走通用参数错。
        // 两条分支必须可区分 —— 否则「顺序错了」和「数字不合法」会退化成同一句提示。
        assertEquals("simulation-admin-invalid-args",
                service.setBounds("SIM001", Double.NaN, 10.0).join().langKey());
        assertEquals("simulation-admin-invalid-args",
                service.setBounds("SIM001", 0.0, 10.0).join().langKey());

        assertNull(stock.getManualPrice(), "非法参数不得写入定值覆盖");
        assertNull(stock.getManualTargetPercent(), "非法参数不得写入目标涨幅覆盖");
        assertEquals(priceBefore, stock.getCurrentPrice(), 0.0, "非法管理操作不得改价");
        assertEquals(0.0, stock.getSlopePerMs(), 0.0);
        assertTrue(repository.findSchedules().isEmpty());
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────────

    private void register(String... codes) {
        for (int i = 0; i < codes.length; i++) {
            service.registryView().put(new SimulationStock(codes[i], "模拟股 " + codes[i],
                    i % 2 == 0 ? "科技" : "金融", 10.0 + i, 100L + i));
        }
    }

    /** 确定性伪随机样本：同一 i 在所有断言里产生同一个数，避免测试自身引入随机性。 */
    private static double gaussian(int i) {
        return new java.util.Random(i * 31L + 17L).nextGaussian();
    }

    /**
     * 反射调用包级的 {@code DatabaseManager#createTables()}，跑<b>真实 DDL</b>
     * （范式与 {@code SimulationRepositoryTest} 一致：不复制 DDL、不改 {@code DatabaseManager}）。
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
