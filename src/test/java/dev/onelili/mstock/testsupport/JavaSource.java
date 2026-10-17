package dev.onelili.mstock.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产源码的<b>结构性断言</b>工具。
 *
 * <p><b>为什么需要它</b>：本插件的编排层（{@code MStockCommand}、
 * {@code LeverageService}、{@code MineStock}）全部依赖 {@code Player} /
 * {@code Server} / 插件实例，而离线 JUnit 无法构造这些对象：
 * <ul>
 *   <li>{@code MStockCommand.executeBuy} 的入参是 {@code Player}；</li>
 *   <li>{@code LeverageService} 的构造器要求一个非 null 的 {@code MineStock}，
 *       而 {@code new MineStock()} 会抛
 *       {@code IllegalStateException: JavaPlugin requires to be created by a valid classloader.}
 *       —— 参见 {@code JavaPlugin} 构造器字节码里对
 *       {@code ConfiguredPluginClassLoader.init} 的强制调用（Paper 1.20.1 实测）。</li>
 * </ul>
 *
 * <p>这些方法里有一部分不变量是<b>结构性</b>的 —— 例如"某个守卫必须是方法体的第一条
 * 语句""某次判断必须排在这次写入之前"。结构不变量可以直接对源码断言，而这正是唯一能
 * 捕获"整行被删或被挪到后面"这一回归的手段：把它注释掉不会有编译错误，
 * 也不会有任何既有测试变红。
 *
 * <p><b>调用方的义务</b>：不要用 {@code assumeTrue} 在源文件读不到时跳过 ——
 * 那会复刻这类测试要消灭的假绿。读不到就<b>失败</b>。
 */
public final class JavaSource {

    private JavaSource() {
    }

    /** 按 UTF-8 读取源文件全部行。 */
    public static List<String> read(Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    /** 源文件存在且可读；供调用方做硬失败断言。 */
    public static boolean isReadable(Path path) {
        return Files.isRegularFile(path);
    }

    /**
     * 返回紧跟在给定方法签名之后的<b>第一条可执行语句</b>。
     *
     * <p>已剥离注释与空行 —— 剥离注释是必需的：否则把守卫<b>注释掉</b>
     * 仍会匹配到那行注释文本，测试就会假绿。
     *
     * <p>签名可以跨多行书写：本方法会先找到开启方法体的 <code>{</code>，
     * 再取它之后的第一条语句（若语句与 <code>{</code> 同行，则取该行剩余部分）。
     *
     * @return 第一条可执行语句（已 strip）；定位失败返回 {@code null}
     */
    public static String firstExecutableStatement(List<String> rawLines, String signature) {
        List<String> code = stripComments(rawLines);
        int start = indexOfSignature(code, signature);
        if (start < 0) return null;

        int braceLine = bodyStartLine(code, start);
        if (braceLine < 0) return null;

        String line = code.get(braceLine);
        String afterBrace = line.substring(line.indexOf('{') + 1).strip();
        if (!afterBrace.isEmpty()) return afterBrace;

        for (int i = braceLine + 1; i < code.size(); i++) {
            String candidate = code.get(i).strip();
            if (candidate.isEmpty()) continue;
            return candidate;
        }
        return null;
    }

    /**
     * 取方法体全文（同样剥离注释），用于断言内部确实调用了关键方法、以及调用顺序。
     *
     * <p>括号按字符计数配平，因此方法体内出现嵌套的 lambda / 匿名类 / 字符串里的
     * 括号也能正确截断到方法末尾。
     *
     * @return 方法体文本（含签名所在行到闭合括号行）；定位失败返回 {@code null}
     */
    public static String methodBody(List<String> rawLines, String signature) {
        List<String> code = stripComments(rawLines);
        int start = indexOfSignature(code, signature);
        if (start < 0) return null;

        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean seenBrace = false;
        for (int i = start; i < code.size(); i++) {
            String line = code.get(i);
            body.append(line).append('\n');
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    seenBrace = true;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if (seenBrace && depth <= 0) break;
        }
        return seenBrace ? body.toString() : null;
    }

    /**
     * 在方法体文本里查找片段，返回其字符下标；找不到返回 {@code -1}。
     *
     * <p>用于比较两处调用的<b>先后顺序</b>：守卫必须先于它要保护的写入。
     */
    public static int indexOf(String methodBody, String needle) {
        return methodBody == null ? -1 : methodBody.indexOf(needle);
    }

    /**
     * 判定方法体里 <code>before</code> 严格先于 <code>after</code> 出现。
     *
     * <p>任一者缺失都返回 {@code false} —— 缺失绝不能算"顺序正确"。
     */
    public static boolean appearsBefore(String methodBody, String before, String after) {
        int a = indexOf(methodBody, before);
        int b = indexOf(methodBody, after);
        return a >= 0 && b >= 0 && a < b;
    }

    /**
     * 剔除全部注释后的一行行代码（空行保留为占位，以便与原始行号对齐）。
     *
     * <p>块注释状态必须<b>跨行</b>维护：方法上方有 javadoc，方法体内也可能出现多行
     * 注释，逐行独立剥离会把注释正文误当代码。字符串字面量里的 <code>//</code>
     * 不在处理范围内（本项目源码无此写法）。
     */
    public static List<String> stripComments(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        boolean inBlock = false;
        for (String line : lines) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (inBlock) {
                    if (ch == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
                sb.append(ch);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * 签名首次出现的<b>行下标</b>，找不到返回 {@code -1}。
     *
     * <p>先把每行的空白折叠成单个空格、整体拼接成一个字符串再做查找，
     * 因此<b>跨多行书写的签名</b>也能命中 —— 这一点是必需的：本项目里
     * {@code close(UUID, long, double, SymbolPriceSource)} 这类签名恰好被换行拆开
     * （见 {@code LeverageService.java}），逐行匹配会漏掉它并让调用方
     * 得到 {@code null}（表现为"没能定位方法体"的假失败）。
     *
     * <p>命中位置再二值映射回行号，返回的是签名<b>起始</b>所在行。
     * 调用方据此向后找方法体的 <code>{</code>，因此起始行是签名首行即可。
     */
    public static int indexOfSignature(List<String> compressed, String signature) {
        String needle = signature.replaceAll("\\s+", " ").strip();
        if (needle.isEmpty()) return -1;

        // 主路径：折叠空白后整体拼接，保留一个空格作行间分隔（安全 —— 不会把
        // 上一行结尾与下一行开头粘成一个本不存在的标识符）。
        int hit = locate(compressed, needle, true);
        if (hit >= 0) return hit;

        // 兜底：把<b>全部</b>空白都去掉再比。这样签名写成一行的
        // `symbolPrice(dev.onelili...market, String symbol)` 与换行写成的
        // `symbolPrice(` + 换行 + `dev.onelili...` 两种排版都能命中，
        // 否则调用方会因源码重新排版而得到 null（表现为"没能定位方法体"的假失败）。
        // 只在主路径失败时才走这里，因此不会削弱主路径的安全性。
        return locate(compressed, needle.replace(" ", ""), false);
    }

    /**
     * 按给定分隔方式拼接源码后查找 needle，命中则返回其行下标。
     *
     * @param withSpace true = 行间插一个空格；false = 直接紧邻拼接
     */
    private static int locate(List<String> compressed, String needle, boolean withSpace) {
        StringBuilder joined = new StringBuilder();
        int[] lineStart = new int[compressed.size()];
        for (int i = 0; i < compressed.size(); i++) {
            lineStart[i] = joined.length();
            String collapsed = compressed.get(i).replaceAll("\\s+", " ").strip();
            if (!withSpace) collapsed = collapsed.replace(" ", "");
            joined.append(collapsed);
            if (withSpace) joined.append(' ');
        }

        int at = joined.indexOf(needle);
        if (at < 0) return -1;

        // 最后一个 lineStart <= at 的行即为命中行
        int low = 0;
        int high = compressed.size() - 1;
        int found = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lineStart[mid] <= at) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /** 从签名行起第一个含 <code>{</code> 的行下标（方法体的开括号）。 */
    private static int bodyStartLine(List<String> code, int signatureLine) {
        for (int i = signatureLine; i < code.size(); i++) {
            if (code.get(i).indexOf('{') >= 0) return i;
        }
        return -1;
    }
}
