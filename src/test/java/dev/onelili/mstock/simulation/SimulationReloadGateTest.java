package dev.onelili.mstock.simulation;

import dev.onelili.mstock.database.DatabaseManager;
import dev.onelili.mstock.model.StockInfo;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code simulation.enabled: false} + {@code /mstock reload} 后的<b>关闭态门控</b>回归测试。
 *
 * <p><b>它防的是哪个缺陷</b>（且完全可达）：
 * <ol>
 *   <li>管理员把 {@code simulation.enabled} 由 {@code true} 改成 {@code false}，执行 {@code /mstock reload}；</li>
 *   <li>{@link SimulationService#reconfigure} 用<b>新配置</b>重建注册表，但把既有股票<b>逐只搬迁</b>
 *       进去而<b>不清空</b> —— 于是 {@code registry} 里仍然有 {@code SIM001}；</li>
 *   <li>若 {@link SimulationStockSource#supports} 只看注册表，{@code api.isSupported("SIM001")} 仍为
 *       {@code true}；同时指令层的路由器因为"未启用"而 {@code return false} 放行；</li>
 *   <li>结果 {@code /mstock buy SIM001 10} 会以<b>真实股票</b>身份写 {@code holdings} 表并扣真实
 *       Vault 余额。</li>
 * </ol>
 *
 * <p>启动即关闭时安全只是因为 {@code bootstrapNow()} 提前返回 ⇒ 池子为空；{@code reload} 绕过了这个早退，
 * 所以必须由 {@code supports} / {@code fetch} 自己判 {@code isEnabled()}。
 *
 * <p><b>为什么能离线跑</b>：{@code new SimulationService(null, db, null)} 是既有支持的注入式构造
 * （{@code plugin == null} 时用 {@code Logger.getLogger} 兜底），{@code SimulationLinkTest} /
 * {@code SimulationRepositoryTest} 已在用；{@code applyConfig(SimulationConfig)} 是包级钩子，
 * 精确复现 {@code reconfigure} 的语义（新配置 + 注册表已迁移 + 股票仍在池中）而无需 Bukkit 服务端。
 * 配置用 Bukkit 的 {@link YamlConfiguration} 直接构造（{@code LangUtilFallbackTest} 已有先例）。
 *
 * <p><b>DDL 不复制</b>：本类在 {@code ...simulation} 包，而
 * {@code DatabaseManager#createTables()} 是 {@code ...database} 包的包级方法，
 * 故与 {@code SimulationRepositoryTest} 同样用反射调用<b>生产建表语句</b>，
 * 避免"测试建的表对、生产的表错"的漂移。
 */
class SimulationReloadGateTest {

    private DatabaseManager database;
    private SimulationService service;

    @BeforeEach
    void setUp() throws SQLException {
        // 与 DatabaseManager.init 一样显式加载，否则单独跑本类时驱动尚未注册
        // （全量跑时别的测试类会先注册，掩盖这个问题）。
        org.h2.Driver.load();
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        database = new DatabaseManager(Logger.getAnonymousLogger()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl);
            }
        };
        createTables(database);

        service = new SimulationService(null, database, null);
        service.registryView().bootstrap();
        // 前置条件：初始态必须可用，否则"关闭后失效"的断言会因为别的原因通过（假绿）。
        assertTrue(service.isEnabled(), "初始态应当启用");
        assertTrue(service.stockSource().supports("SIM001"), "启用态 supports 必须为 true");
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
        // DatabaseManager.close() 会按插件类加载器注销 org.h2.Driver（生产上用于防止驱动泄漏），
        // 但那会污染随后所有用 DriverManager.getConnection 的测试类 —— 必须补回注册。
        org.h2.Driver.load();
    }

    /**
     * 核心回归：{@code reload} 进入关闭态后，{@code supports} 必须恒为 {@code false}。
     *
     * <p>这条断言在修复前是<b>红的</b>：修复前 {@code supports} 只查注册表，
     * 而 {@code reconfigure}/{@code applyConfig} 会把股票逐只搬过去。
     */
    @Test
    void disabledAfterReloadStillReportsUnsupported() {
        applyDisabled();

        assertFalse(service.isEnabled(), "reload 后应当读到 enabled: false");
        assertTrue(service.findStock("SIM001").isPresent(),
                "前置条件：注册表必须仍然保有 SIM001，否则本测试根本没有复现 reload 场景");
        assertFalse(service.stockSource().supports("SIM001"),
                "关闭态下 supports 必须恒为 false —— 否则 api.isSupported(\"SIM001\") "
                        + "仍为 true，指令层又会因「未启用」放行，最终写真实 holdings 并扣 Vault");
        assertFalse(service.stockSource().supports("SIM010"), "对池中每一只都必须失效");
    }

    /** {@code fetch} 自己也要门控：即使调用方没有先问 {@code supports}，也拿不到模拟价。 */
    @Test
    void disabledAfterReloadRefusesToServePrices() {
        applyDisabled();

        assertThrows(RuntimeException.class,
                () -> service.stockSource().fetch("SIM001").join(),
                "关闭态下 fetch 必须失败 —— 拿到价格就等于能写真实持仓表");
        assertThrows(RuntimeException.class,
                () -> service.stockSource().fetchKLine("SIM001", 20).join(),
                "关闭态下 fetchKLine 必须失败");
    }

    /** 重新启用后必须立刻恢复：门控读的是实时配置，不是一次性开关。 */
    @Test
    void reenablingViaReloadRestoresService() {
        applyDisabled();
        assertFalse(service.stockSource().supports("SIM001"), "关闭态失效");

        YamlConfiguration enabled = new YamlConfiguration();
        enabled.set("simulation.enabled", true);
        service.applyConfig(new SimulationConfig(enabled));

        assertTrue(service.isEnabled(), "重新启用后应当可用");
        assertTrue(service.stockSource().supports("SIM001"), "重新启用后 supports 必须恢复");
        StockInfo info = service.stockSource().fetch("SIM001").join();
        assertNotNull(info, "重新启用后必须能取到报价");
        assertEquals("SIM001", info.getCode());
    }

    /**
     * 关闭态下注册表<b>依然保有</b>股票 —— 这条断言翻转后就是"另一个更简单的修法"（reload 时清空池子），
     * 但那会打断内存价格路径（{@code reconfigure} 的 javadoc 明说不清空是刻意的）。
     * 本测试把它固化下来：门控必须由 {@code isEnabled()} 表达，不能指望池子为空。
     */
    @Test
    void registryIsDeliberatelyNotClearedOnReload() {
        applyDisabled();

        assertEquals(10, service.registryView().size(),
                "reconfigure 刻意不清空注册表（内存价格路径不能被重载打断），"
                        + "所以「关闭」只能由 isEnabled() 门控表达");
        assertTrue(service.isSimulationCode("SIM001"),
                "isSimulationCode 只看正则 + 注册表，关闭态仍为 true —— 正因如此，"
                        + "指令层路由器必须「先判归属、再判启用」并接管报错，不能放行");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 精确复现 {@code /mstock reload} 读到 {@code simulation.enabled: false} 的那一步。 */
    private void applyDisabled() {
        YamlConfiguration disabled = new YamlConfiguration();
        disabled.set("simulation.enabled", false);
        service.applyConfig(new SimulationConfig(disabled));
    }

    /**
     * 反射调用包级的 {@code DatabaseManager#createTables()}，跑真实 DDL。
     *
     * <p>本类在 {@code ...simulation} 包，无法直接访问 {@code ...database} 包的包级方法；
     * 反射是"既不复制 DDL、也不改 {@code DatabaseManager}"的唯一途径。
     */
    private static void createTables(DatabaseManager manager) throws SQLException {
        try {
            Method method = DatabaseManager.class.getDeclaredMethod("createTables");
            method.setAccessible(true);
            method.invoke(manager);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new SQLException("无法调用 DatabaseManager#createTables()", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) throw sql;
            throw new SQLException("createTables() 执行失败", cause);
        }
    }
}
