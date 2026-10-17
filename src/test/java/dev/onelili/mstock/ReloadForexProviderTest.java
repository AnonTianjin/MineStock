package dev.onelili.mstock;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mstock reload} 之后<b>外汇服务必须绑在新 provider 上</b>的回归测试。
 *
 * <p><b>它防的缺陷</b>：{@code reload()} 会 {@code new BiquoteForexProvider(...)} 并把插件级
 * {@code forexProvider} 换成新实例，然后在方法末尾 {@code oldProvider.close()} —— 那会把旧
 * provider 的 {@code HttpClient} {@code shutdownNow()}。但 {@code ForexService} 的
 * {@code provider} 字段原本是 {@code final}，没有任何行重建它，于是重载后
 * {@code getForexService()} 仍是开局那个对象、攥着已关闭的客户端。
 *
 * <p>症状是一句与网络毫无关系的报错：
 * <pre>
 * [WARN] [MineStock] 获取外汇报价失败:
 *   java.lang.IllegalStateException: biquote 行情客户端已关闭
 * </pre>
 * 玩家侧表现为"外汇板块整个哑掉"，而 {@code /mstock forex price} 的失败原因看起来像
 * 网络故障，极易被误判。关掉重载、重启服务器就恢复 —— 这正是当年漏掉它的原因：
 * 重启路径（{@code onEnable}）是对的，只有重载路径错了。
 *
 * <p><b>为什么用源码断言</b>：{@code reload()} 依赖 {@code JavaPlugin} 实例，
 * 离线 JUnit 构造不出来（见 {@link JavaSource} 的说明）。而这条不变量是<b>结构性</b>的 ——
 * "交接必须发生在关闭之前"。把它注释掉既不会有编译错误，也不会有任何既有测试变红。
 *
 * <p><b>诚实边界</b>：这里断言的是<b>接线</b>，不是运行时行为。真正证明修复生效的是
 * 把 jar 换到服上执行一次 {@code /mstock reload} 再 {@code /mstock forex price EURUSD}。
 */
class ReloadForexProviderTest {

    private static final Path MINESTOCK =
            Paths.get("src/main/java/dev/onelili/mstock/MineStock.java");
    private static final Path FOREX_SERVICE =
            Paths.get("src/main/java/dev/onelili/mstock/forex/ForexService.java");

    private static String reloadBody() throws IOException {
        assertTrue(JavaSource.isReadable(MINESTOCK), "读不到 MineStock.java: " + MINESTOCK);
        List<String> lines = JavaSource.read(MINESTOCK);
        String body = JavaSource.methodBody(lines, "public synchronized void reload()");
        assertNotNull(body, "MineStock.java 里找不到 reload() 方法体");
        return body;
    }

    @Test
    void reloadHandsNewProviderToForexService() throws IOException {
        String body = reloadBody();
        assertTrue(body.contains("forexService.reconfigure(newProvider)"),
                "reload() 没有把新的 BiquoteForexProvider 交给 ForexService；"
                        + "重载后外汇服务会攥着已被 close 的旧客户端");
    }

    /**
     * 顺序断言：交接必须严格早于断开旧连接。
     *
     * <p>反过来写（先 close 再 reconfigure）同样是 bug —— 交接的瞬间旧对象已经死了，
     * 中间任何一次并发报价仍会撞上 "客户端已关闭"。{@link JavaSource#appearsBefore}
     * 在任一片段缺失时返回 {@code false}，因此删掉任一行都会让本用例变红。
     */
    @Test
    void providerHandoverHappensBeforeOldProviderIsClosed() throws IOException {
        String body = reloadBody();
        assertTrue(JavaSource.appearsBefore(body,
                        "forexService.reconfigure(newProvider)",
                        "oldProvider.close()"),
                "reload() 里必须先把新 provider 交给 forexService，再 oldProvider.close()");
    }

    /** {@code ForexService.reconfigure} 必须真实存在，否则上面两条断言只是匹配了空气。 */
    @Test
    void forexServiceExposesReconfigure() throws IOException {
        assertTrue(JavaSource.isReadable(FOREX_SERVICE), "读不到 ForexService.java: " + FOREX_SERVICE);
        List<String> lines = JavaSource.read(FOREX_SERVICE);
        assertNotNull(JavaSource.methodBody(lines, "public synchronized void reconfigure(ForexProvider provider)"),
                "ForexService 缺少 reconfigure(ForexProvider)");
    }

    /**
     * provider 字段不能退回 {@code final}。
     *
     * <p>这是本缺陷的<b>根因</b>：{@code final} 字段无法被 {@code reconfigure} 重新赋值。
     * 只断言 {@code reconfigure} 存在是不够的 —— 加回 {@code final} 会直接编译失败，
     * 但把它改成"重新赋值一个局部变量"之类的退化写法不会，这条断言把根因钉死。
     */
    @Test
    void forexProviderFieldIsNotFinal() throws IOException {
        List<String> lines = JavaSource.read(FOREX_SERVICE);
        List<String> code = JavaSource.stripComments(lines);
        boolean declared = false;
        for (String line : code) {
            String trimmed = line.strip();
            if (!trimmed.contains("ForexProvider provider")) continue;
            // 排除方法签名/参数行，只看字段声明（以分号结尾）
            if (!trimmed.endsWith(";")) continue;
            declared = true;
            assertTrue(!trimmed.contains("final"),
                    "ForexService.provider 退回了 final，reconfigure 无法换掉它: " + trimmed);
        }
        assertTrue(declared, "ForexService 里找不到 ForexProvider 字段声明");
    }
}
