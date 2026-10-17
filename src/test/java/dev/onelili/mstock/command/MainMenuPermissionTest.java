package dev.onelili.mstock.command;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minestock.menu} 权限节点的<b>接线回归测试</b>。
 *
 * <p><b>为什么需要这个类</b>：该节点原先在 {@code plugin.yml} 里
 * <b>声明了却全仓零 {@code hasPermission} 调用</b> —— 管理员把它设为 {@code false}
 * 在服务端毫无效果，会以为自己关掉了入口。修好接线后又出现同一类风险：
 * 主菜单有三条指令入口（无参 {@code /mstock}、{@code /mstock menu}、
 * {@code _gui_menu}），只堵住其中一条，另外两条仍能绕过，
 * 而"漏堵一条"是纯结构缺陷，离线 JUnit 无法通过调用行为发现
 * （{@code Player} 不可构造），只能对源码断言。
 *
 * <p>本类故意<b>不用</b> {@code assumeTrue} 跳过：源文件读不到时必须<b>失败</b>，
 * 否则就复刻了它要消灭的那种"读不到就跳过"的假绿。
 */
class MainMenuPermissionTest {

    private static final Path COMMAND_SOURCE =
            Paths.get("src", "main", "java", "dev", "onelili", "mstock", "command", "MStockCommand.java");

    private static final Path PLUGIN_YML =
            Paths.get("src", "main", "resources", "plugin.yml");

    /** 指令层打开主菜单的唯一写法（三处入口都必须走它）。 */
    private static final String MENU_OPEN = "mainMenuGui.open(player)";

    /** 守卫调用。 */
    private static final String MENU_GUARD = "canOpenMenu(player)";

    @Test
    void sourcesAreReadable() {
        assertTrue(JavaSource.isReadable(COMMAND_SOURCE),
                "读不到 " + COMMAND_SOURCE.toAbsolutePath() + "；本测试依赖 CWD = 项目根");
        assertTrue(Files.isRegularFile(PLUGIN_YML),
                "读不到 " + PLUGIN_YML.toAbsolutePath() + "；本测试依赖 CWD = 项目根");
    }

    /** 守卫本身必须真的查 {@code minestock.menu} 权限，并用既有的 {@code no-permission} 文案拒绝。 */
    @Test
    void guardConsultsTheDeclaredPermissionNode() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(COMMAND_SOURCE),
                "private boolean canOpenMenu(Player player)");

        assertNotNull(body, "没能定位 canOpenMenu 的方法体");
        assertTrue(body.contains("minestock.menu"),
                "canOpenMenu 必须查 minestock.menu 权限节点，否则该节点依旧是死声明");
        assertTrue(body.contains("hasPermission"),
                "canOpenMenu 必须调用 hasPermission");
        assertTrue(body.contains("no-permission"),
                "被拒绝时必须发送 no-permission 文案，不能静默什么都不做");
    }

    /**
     * <b>核心不变量</b>：{@code onCommand} 里每一处打开主菜单之前，都必须有<b>各自独立</b>的守卫。
     *
     * <p>"独立"是关键 —— 只断言"方法体里既有 open 又有 canOpenMenu"会放过
     * "只在其中一条 case 里加守卫、另外两条裸奔"这种写法。
     * 故此处逐个 open 回溯：它之前必须存在一个<b>位于上一个守卫之后</b>的 canOpenMenu，
     * 也就是每个 open 各配一个守卫。
     */
    @Test
    void everyMenuOpenInOnCommandIsGuarded() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(COMMAND_SOURCE),
                "public boolean onCommand(");
        assertNotNull(body, "没能定位 onCommand 的方法体");

        int opens = 0;
        int lastGuard = -1;
        int cursor = 0;
        int openIndex;
        while ((openIndex = body.indexOf(MENU_OPEN, cursor)) >= 0) {
            opens++;
            int guardIndex = body.lastIndexOf(MENU_GUARD, openIndex);
            assertTrue(guardIndex > lastGuard,
                    "第 " + opens + " 处 " + MENU_OPEN + " 之前没有它自己的权限守卫："
                            + "每条入口都必须单独判 minestock.menu，只堵一条会被其余入口绕过");
            assertTrue(guardIndex < openIndex, "守卫必须排在 open 之前");
            lastGuard = guardIndex;
            cursor = openIndex + MENU_OPEN.length();
        }

        // 三处入口：args.length == 0、case "menu"、case "_gui_menu"。
        assertTrue(opens == 3,
                "onCommand 里应当恰好有 3 处主菜单入口（无参 / menu / _gui_menu），实际找到 " + opens
                        + "；若新增或删除入口，请同步更新本断言并确认它也被守卫覆盖");
    }

    /** 权限节点必须在 {@code plugin.yml} 里真实声明 —— 代码里查了、配置里没有，同样不生效。 */
    @Test
    void permissionNodeIsDeclaredInPluginYml() throws IOException {
        List<String> lines = Files.readAllLines(PLUGIN_YML);
        boolean declared = lines.stream().anyMatch(line -> line.strip().startsWith("minestock.menu:"));
        assertTrue(declared,
                "plugin.yml 必须声明 minestock.menu；否则 Player#hasPermission 会因节点未定义而"
                        + "走 default 判定，管理员无法通过权限插件精确控制");
    }
}
