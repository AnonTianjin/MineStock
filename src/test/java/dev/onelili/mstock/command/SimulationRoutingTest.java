package dev.onelili.mstock.command;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟盘拦截的<b>接线回归测试</b>（资金级）。
 *
 * <p><b>为什么必须有这个类</b>：把
 * {@code MStockCommand.executeBuy} 与 {@code executeSell} 里的
 * {@code if (routeToSimulation(...)) return;} 注释掉，全量 309 项测试
 * <b>依然全绿</b>。也就是说这条守卫"删了也没人发现"，而它守的是
 * 整个方案唯一被标为资金级最高优先级的不变量：
 * 模拟代码 {@code SIM001} 绝不能以真实股票身份写进 {@code holdings}
 * 或 {@code margin_positions}、绝不能扣 Vault 余额。
 *
 * <p><b>为什么不能写成普通单测</b>：{@code executeBuy} 的入参是
 * {@code Player}，离线 JUnit 无法构造（本项目不依赖 MockBukkit），
 * 该拦截因此落在"编排层无法离线覆盖"的区间里。
 * 但"守卫必须是方法体第一条语句"是个<b>结构性</b>不变量，
 * 可以直接对源码断言 —— 这也正是唯一能捕获"整行被删"这一回归的手段。
 * 源码解析由 {@link JavaSource} 提供（与 {@code LeverageServiceWiringTest} 共用）。
 *
 * <p>本类故意<b>不用</b> {@code assumeTrue} 跳过：源文件读不到时
 * 必须<b>失败</b>而不是静默跳过，否则就复刻了它要消灭的那种假绿。
 */
class SimulationRoutingTest {

    /** 生产指令处理器；CWD 为项目根（{@code LangUtilFallbackTest} 读 resources 已确立该前提）。 */
    private static final Path COMMAND_SOURCE =
            Paths.get("src", "main", "java", "dev", "onelili", "mstock", "command", "MStockCommand.java");

    @Test
    void commandSourceIsReadable() {
        assertTrue(JavaSource.isReadable(COMMAND_SOURCE),
                "读不到 " + COMMAND_SOURCE.toAbsolutePath()
                        + "；本测试依赖 CWD = 项目根，若为跳过它会掩盖 R1 回归，故此处硬失败");
    }

    /**
     * 买入路径：模拟盘拦截必须是 {@code executeBuy(Player,String,int,int)} 的<b>第一条可执行语句</b>。
     *
     * <p>四参重载是全部买入入口的汇合点：指令直买、{@code initBuy} → 聊天数量输入 →
     * 确认页 → {@code _buy_confirm} 最终都调它。因此"它在第一位"等价于
     * "没有任何买入路径能在拦截之前碰真实持仓或 Wallet"。
     */
    @Test
    void simulationGuardIsFirstStatementOfExecuteBuy() throws IOException {
        String first = JavaSource.firstExecutableStatement(JavaSource.read(COMMAND_SOURCE),
                "public void executeBuy(Player player, String code, int amount, int leverage)");

        assertNotNull(first, "没能定位 executeBuy 的方法体");
        assertTrue(first.startsWith("if (routeToSimulation("),
                "executeBuy 的第一条语句必须是模拟盘拦截，实际是: " + first
                        + "\n否则 /mstock buy SIM001 10 会写入真实 holdings 并扣 Vault 余额");
    }

    /** 卖出路径：同 {@link #simulationGuardIsFirstStatementOfExecuteBuy}，守卫为 {@code routeToSimulationSell}。 */
    @Test
    void simulationGuardIsFirstStatementOfExecuteSell() throws IOException {
        String first = JavaSource.firstExecutableStatement(JavaSource.read(COMMAND_SOURCE),
                "public void executeSell(Player player, String code, int amount)");

        assertNotNull(first, "没能定位 executeSell 的方法体");
        assertTrue(first.startsWith("if (routeToSimulationSell("),
                "executeSell 的第一条语句必须是模拟盘拦截，实际是: " + first
                        + "\n否则 /mstock sell SIM001 10 会平掉并以真实持仓身份结算");
    }

    /**
     * 守卫可能被"改成永远返回 false"而不是删除 —— 那同样能让拦截失效却仍满足上面的
     * "第一条语句"断言。故此处再断言两个 router 都真的查询了启用态与代码归属。
     */
    @Test
    void routersStillConsultEnableFlagAndCodeOwnership() throws IOException {
        List<String> code = JavaSource.read(COMMAND_SOURCE);

        for (String[] router : new String[][]{
                {"private boolean routeToSimulation(Player player, String code, int amount, int leverage)",
                        "routeToSimulation"},
                {"private boolean routeToSimulationSell(Player player, String code, int amount)",
                        "routeToSimulationSell"}}) {
            String body = JavaSource.methodBody(code, router[0]);
            assertNotNull(body, "没能定位 " + router[1] + " 的方法体");
            assertTrue(body.contains("getSimulationService"),
                    router[1] + " 必须经 plugin.getSimulationService() 取服务");
            assertTrue(body.contains("isEnabled"),
                    router[1] + " 必须检查 simulation.enabled，否则关闭模拟盘后仍会接管订单");
            assertTrue(body.contains("isSimulationCode"),
                    router[1] + " 必须检查代码归属，否则任何代码都会被模拟盘接管");
            assertTrue(body.contains("minestock.simulation.use"),
                    router[1] + " 必须校验 minestock.simulation.use 权限节点");
        }
    }

    /**
     * <b>顺序</b>不变量：路由器必须"先判代码归属，再判启用状态并接管报错"。
     *
     * <p>它防的是一个真实存在过的漏洞（且完全可达）：
     * 若像修复前那样写成
     * {@code if (service == null || !service.isEnabled() || !service.isSimulationCode(code)) return false;}
     * ，那么管理员把 {@code simulation.enabled} 改成 {@code false} 再 {@code /mstock reload} 之后 ——
     * {@code reconfigure} 会把股票<b>逐只搬迁</b>进新注册表而<b>不清空</b>，
     * 于是 {@code api.isSupported("SIM001")} 仍为 true（模拟源在 index 0），
     * 而路由器却因为"未启用"而 {@code return false} <b>放行</b>，
     * 最终 {@code /mstock buy SIM001 10} 以真实股票身份写入 {@code holdings} 并扣真实 Vault 余额。
     *
     * <p>两条断言缺一不可：只查"含 isEnabled"会放过上面那种写法，
     * 只查"含 isSimulationCode"同理。
     */
    @Test
    void routersCheckCodeOwnershipBeforeEnableFlag() throws IOException {
        List<String> code = JavaSource.read(COMMAND_SOURCE);

        for (String[] router : new String[][]{
                {"private boolean routeToSimulation(Player player, String code, int amount, int leverage)",
                        "routeToSimulation"},
                {"private boolean routeToSimulationSell(Player player, String code, int amount)",
                        "routeToSimulationSell"}}) {
            String body = JavaSource.methodBody(code, router[0]);
            assertNotNull(body, "没能定位 " + router[1] + " 的方法体");

            assertTrue(JavaSource.appearsBefore(body, "isSimulationCode", "isEnabled"),
                    router[1] + " 必须先判代码归属再判启用态：反过来的话，"
                            + "在 reload 关闭模拟盘之后 !isEnabled() 会让 router 直接 return false 放行，"
                            + "订单落到真实 holdings 并扣 Vault");

            // 首个 return false 就是"本单不属于模拟盘、放行给真实路径"——它绝不能带 isEnabled 条件。
            String firstReturnFalse = firstReturnFalseStatement(body);
            assertNotNull(firstReturnFalse, "没能定位 " + router[1] + " 的 return false");
            assertFalse(firstReturnFalse.contains("isEnabled"),
                    router[1] + " 的首个 return false 不得含 isEnabled，实际是: " + firstReturnFalse
                            + " —— 关闭态必须由本方法接管并报 simulation-disabled，而不是放行给真实路径");
        }
    }

    /** 取方法体里第一条含 {@code return false} 的语句（去掉行首缩进）。 */
    private static String firstReturnFalseStatement(String body) {
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.contains("return false")) return trimmed;
        }
        return null;
    }
}
