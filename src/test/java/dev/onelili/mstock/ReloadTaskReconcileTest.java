package dev.onelili.mstock;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mstock reload} 之后模拟盘<b>推进任务</b>的协调回归测试。
 *
 * <p><b>它防的是哪两个缺陷</b>：{@code reload()} 会调
 * {@link dev.onelili.mstock.simulation.SimulationService#reconfigure}，于是
 * {@code simulation.enabled} 与 {@code simulation.tick-seconds} 的改动立刻被服务读到；
 * 但定时器本身只在 {@code onEnable()} 里注册过一次。没有协调就会出现两个方向相反的漏洞：
 *
 * <ul>
 *   <li><b>关不掉</b>：启动时启用、重载时改成 {@code false} —— 定时器仍在跑，
 *       继续推进价格、继续写 K 线，与"已关闭"的配置自相矛盾。此时
 *       {@code supports("SIM001")} 已经是 {@code false}（模拟盘门控），
 *       所以玩家<b>看得到价格在动却买不进去</b>，且库里的 {@code current_price}
 *       继续被无人能交易的行情改写。</li>
 *   <li><b>开不了</b>：启动时关闭、重载时改成 {@code true} —— 注册表从未
 *       {@code bootstrap()}，池子是空的 ⇒ {@code supports("SIM001")} 恒 {@code false}，
 *       模拟盘要等一次完整的服务器重启才可用。</li>
 * </ul>
 *
 * <p><b>为什么能离线跑</b>：判定逻辑抽成了纯函数
 * {@link MineStock#simulationTaskAction(boolean, boolean, boolean)}（静态、无 Bukkit 依赖），
 * 所以三个布尔量的全部 8 种组合都能直接断言，不需要实例化 {@code JavaPlugin}。
 * 接线本身（{@code reload()} 里确实调了协调方法）用 {@link JavaSource} 对源码断言 ——
 * 纯函数的组合穷举<b>不能</b>发现"reload 忘了调它"。
 */
class ReloadTaskReconcileTest {

    private static final Path MINE_STOCK =
            Paths.get("src", "main", "java", "dev", "onelili", "mstock", "MineStock.java");

    /** 源文件必须可读；读不到要<b>失败</b>而不是跳过，否则本类会变成假绿。 */
    @Test
    void mineStockSourceIsReadable() {
        assertTrue(JavaSource.isReadable(MINE_STOCK),
                "读不到 " + MINE_STOCK.toAbsolutePath() + "；本测试依赖 CWD = 项目根");
    }

    // ── 纯函数：三个布尔量的全部组合 ──────────────────────────────────────────

    @Test
    void enabledWithoutRunningTaskStartsIt() {
        assertEquals(MineStock.SimulationTaskAction.START,
                MineStock.simulationTaskAction(true, true, false),
                "启动时关闭、重载时启用 —— 必须 bootstrap 并注册定时器，"
                        + "否则股票池为空、simulation 要等重启才可用");
    }

    @Test
    void enabledWithRunningTaskIsLeftAlone() {
        assertEquals(MineStock.SimulationTaskAction.NONE,
                MineStock.simulationTaskAction(true, true, true),
                "配置没变时不能重启定时器：那会无谓地打断推进节奏，"
                        + "还会把 lastTickAt 重置掉");
    }

    @Test
    void disabledWithRunningTaskStopsIt() {
        assertEquals(MineStock.SimulationTaskAction.STOP,
                MineStock.simulationTaskAction(true, false, true),
                "启动时启用、重载时关闭 —— 必须取消定时器；"
                        + "否则配置说关闭、价格却还在被推进");
    }

    @Test
    void disabledWithoutRunningTaskIsLeftAlone() {
        assertEquals(MineStock.SimulationTaskAction.NONE,
                MineStock.simulationTaskAction(true, false, false),
                "本来就没在跑，重复 stop() 没有意义");
    }

    @Test
    void missingServiceIsAlwaysNone() {
        assertEquals(MineStock.SimulationTaskAction.NONE,
                MineStock.simulationTaskAction(false, false, false),
                "service 不存在时无任务可管");
        assertEquals(MineStock.SimulationTaskAction.NONE,
                MineStock.simulationTaskAction(false, true, true),
                "service 不存在时即使 enabled 也不能去 START —— 会 NPE");
        assertEquals(MineStock.SimulationTaskAction.NONE,
                MineStock.simulationTaskAction(false, true, false),
                "service 不存在且 enabled 时必须保持 NONE（这条防的是"
                        + "「只看 enabled、不看 service 是否为 null」的写法）");
    }

    // ── 接线：reload() 必须真的调用协调方法 ──────────────────────────────────

    /**
     * 纯函数的组合穷举无法发现"{@code reload()} 忘了调 {@code reconcileSimulationTask()}"，
     * 所以这里直接锁调用点。
     */
    @Test
    void reloadReconcilesSimulationTaskAfterReconfigure() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "public synchronized void reload()");
        assertNotNull(body, "没能定位 MineStock.reload() 的方法体");

        assertTrue(body.contains("reconcileSimulationTask()"),
                "reload() 必须在 reconfigure 之后调用 reconcileSimulationTask()，"
                        + "否则「启动时开、重载时关」会继续跑价格，"
                        + "「启动时关、重载时开」要等重启才可用");
        assertTrue(JavaSource.appearsBefore(body, "simulationService.reconfigure(",
                        "reconcileSimulationTask()"),
                "协调必须发生在 reconfigure 之后 —— reconfigure 才会把新的 "
                        + "simulation.enabled 装进服务，顺序反了会读到旧配置");
    }

    /**
     * {@code STOP} 分支不能只取消定时器：{@code service.stop()} 会冲刷最后一根未闭合的
     * K 线桶并落库股票状态，漏掉它就丢数据。
     */
    @Test
    void stopBranchFlushesBeforeGivingUp() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "private void reconcileSimulationTask()");
        assertNotNull(body, "没能定位 MineStock.reconcileSimulationTask() 的方法体");

        assertTrue(body.contains("service::stop") || body.contains("service.stop()"),
                "STOP 分支必须调 service.stop()：它负责冲刷最后一根未闭合的 K 线桶并落库"
                        + "股票状态，只 cancel 会丢数据");
        assertTrue(body.contains("cancel"),
                "STOP 分支必须取消定时器");
        assertTrue(JavaSource.appearsBefore(body, "running::cancel", "service::stop")
                        || JavaSource.appearsBefore(body, "running.cancel()", "service.stop()"),
                "必须先取消定时器再 stop()：反过来的话，stop() 冲刷期间定时器仍可能"
                        + "并发写入同一个缓冲");
    }

    /** 两个入口必须共用同一段注册逻辑，避免"重载后周期与启动时不一致"。 */
    @Test
    void bothEntryPointsShareTheSameTickerRegistration() throws IOException {
        String start = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "private void startSimulationTasks()");
        assertNotNull(start, "没能定位 startSimulationTasks() 的方法体");
        assertTrue(start.contains("scheduleSimulationTicker("),
                "startSimulationTasks() 必须走抽出来的 scheduleSimulationTicker(service)，"
                        + "否则 reload 重启后的周期与实际 tick-seconds 可能不一致");

        String reconcile = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "private void reconcileSimulationTask()");
        assertNotNull(reconcile, "没能定位 reconcileSimulationTask() 的方法体");
        assertTrue(reconcile.contains("scheduleSimulationTicker("),
                "reconcileSimulationTask() 的 START 分支必须复用同一个注册方法");
    }
}
