package dev.onelili.mstock.command;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mstock help [页码]} 分页帮助的结构不变量。
 *
 * <p>用户裁决：帮助要分页，且把过去完全不可见的一批已注册指令
 * （positions / pg / leverage stock / margin close|closeall|topup /
 * forex fiat|crypto|list|search|chart / futures close|contracts /
 * simulation list|admin 等）全部展示出来。这里锁定三件事：
 * <ol>
 *   <li>页数常量与 lang.yml 的 {@code help-page-1..N} 键一一对应；</li>
 *   <li>分页正文中<b>必须</b>出现每一组曾经缺失的指令——防止未来加指令时又忘记进帮助；</li>
 *   <li>派发层 {@code case "help", "?"} 必须走分页入口，非法页码必须被拒绝而不是回落第一页。</li>
 * </ol>
 */
class HelpPagingTest {

    private static final Path COMMAND =
            Path.of("src/main/java/dev/onelili/mstock/command/MStockCommand.java");
    private static final Path LANG = Path.of("src/main/resources/lang.yml");

    // ── 常量与 lang.yml 的一致性 ─────────────────────────────────────────────

    @Test
    void helpPagesConstantMatchesLangKeys() {
        assertEquals(3, MStockCommand.HELP_PAGES,
                "HELP_PAGES 与 lang.yml 的 help-page-1..N 必须同步增减");
        assertEquals(3, helpPageKeyCount(),
                "HELP_PAGE_KEYS 必须恰好覆盖 help-page-1..N");
    }

    /** 反射读取 HELP_PAGE_KEYS 的长度：长度与 HELP_PAGES 绑定是分页一致性的根。 */
    private static int helpPageKeyCount() {
        try {
            var field = MStockCommand.class.getDeclaredField("HELP_PAGE_KEYS");
            field.setAccessible(true);
            return ((List<?>) field.get(null)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("HELP_PAGE_KEYS 常量被改名或移除，分页正文与页数将失去绑定", e);
        }
    }

    @Test
    void everyHelpPageKeyExistsInLangYml() throws Exception {
        assertTrue(Files.exists(LANG), "读不到 lang.yml；本测试依赖 CWD = 项目根");
        String yaml = Files.readString(LANG, java.nio.charset.StandardCharsets.UTF_8);
        for (int page = 1; page <= MStockCommand.HELP_PAGES; page++) {
            assertTrue(yaml.contains("help-page-" + page + ":"),
                    "lang.yml 缺少分页帮助键: help-page-" + page);
        }
        assertTrue(yaml.contains("help-header:"), "lang.yml 缺少 help-header");
        assertTrue(yaml.contains("help-footer:"), "lang.yml 缺少 help-footer");
        assertTrue(yaml.contains("help-page-invalid:"), "lang.yml 缺少 help-page-invalid");
        // usage 键仍被十几处"参数非法"分支引用，必须保留
        assertTrue(yaml.contains("usage:"), "lang.yml 缺少 usage（参数非法短提示仍依赖它）");
    }

    // ── 派发与页码处理 ────────────────────────────────────────────────────────

    @Test
    void dispatchRoutesHelpToPagedHandler() throws Exception {
        assertTrue(JavaSource.isReadable(COMMAND), "读不到 MStockCommand.java；本测试依赖 CWD = 项目根");
        List<String> lines = JavaSource.read(COMMAND);
        // 过去是 case "help", "?" -> lang.sendNoPrefix(player, "usage");
        assertTrue(JavaSource.appearsBefore(
                        String.join("\n", lines),
                        "case \"help\", \"?\" -> handleHelp(player, args);",
                        "private void handleHelp(Player player, String[] args)"),
                "help 派发必须走 handleHelp 分页入口");
    }

    @Test
    void handleHelpRejectsInvalidPageInsteadOfFallingBack() throws Exception {
        List<String> lines = JavaSource.read(COMMAND);
        String body = JavaSource.methodBody(lines, "private void handleHelp(Player player, String[] args)");
        assertTrue(body != null, "找不到 handleHelp");
        // NumberFormatException 与越界都必须走 sendHelpPageInvalid（不回落第一页）
        assertTrue(body.contains("NumberFormatException"),
                "非数字页码必须被捕获处理");
        assertEquals(2, body.split("sendHelpPageInvalid", -1).length - 1,
                "handleHelp 必须在两条非法路径（非数字 / 越界）上都拒绝页码");
        assertTrue(JavaSource.appearsBefore(body, "NumberFormatException", "sendHelpPageInvalid"),
                "捕获异常后必须立即拒绝");
    }

    @Test
    void handleHelpSendsHeaderBodyFooterInOrder() throws Exception {
        List<String> lines = JavaSource.read(COMMAND);
        String body = JavaSource.methodBody(lines, "private void handleHelp(Player player, String[] args)");
        assertTrue(JavaSource.appearsBefore(body, "help-header", "HELP_PAGE_KEYS"),
                "先发页眉");
        assertTrue(JavaSource.appearsBefore(body, "HELP_PAGE_KEYS", "help-footer"),
                "再发正文，最后发页脚");
    }

    // ── 核心诉求：过去不可见的指令必须出现在分页正文里 ──────────────────────

    /**
     * 每个条目 = 帮助正文里必须出现的关键词 + 它属于哪一组。
     * 关键词按 lang.yml 实际渲染文本选择（含空格的整条写法最容易失配，取稳定子串）。
     */
    private static final String[][] REQUIRED_HINTS = {
            // 基础组（第一页）
            {"positions", "基础"},
            {"pg", "基础"},
            {"mstock short", "基础"},
            {"mstock cover", "基础"},
            // 杠杆组
            {"leverage stock", "杠杆"},
            {"margin close", "保证金"},
            {"margin closeall", "保证金"},
            {"margin topup", "保证金"},
            // 外汇组
            {"fiat", "外汇"},
            {"crypto", "外汇"},
            {"forex fiat|crypto|list", "外汇"},
            {"forex search", "外汇"},
            {"forex chart", "外汇"},
            // 期货组
            {"futures close", "期货"},
            {"futures contracts", "期货"},
            // 模拟盘组
            {"simulation list", "模拟盘"},
            {"simulation admin", "模拟盘"},
            {"simulation short", "模拟盘"},
            {"simulation cover", "模拟盘"},
    };

    @Test
    void pagedBodyMentionsEveryCommandThatUsedToBeHidden() throws Exception {
        String yaml = Files.readString(LANG, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder paged = new StringBuilder();
        boolean inHelp = false;
        for (String line : yaml.split("\n", -1)) {
            if (line.startsWith("help-page-1:") || line.startsWith("help-page-2:")
                    || line.startsWith("help-page-3:")) {
                inHelp = true;
            } else if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")
                    && inHelp) {
                inHelp = false;
            }
            if (inHelp) paged.append(line).append('\n');
        }
        assertTrue(paged.length() > 100, "分页正文解析结果异常，检查 lang.yml 结构");

        for (String[] hint : REQUIRED_HINTS) {
            assertTrue(paged.toString().toLowerCase(Locale.ROOT).contains(hint[0].toLowerCase(Locale.ROOT)),
                    "帮助分页正文缺少 " + hint[1] + " 指令关键词: " + hint[0]);
        }
    }

    /**
     * {@code usage} 是"参数非法"时的短提示，十几处调用点共用，所以它比 help 分页更像是
     * 玩家第一眼看到的东西。做空 / 回补是新的两条主线操作，必须在这里也出现，
     * 否则输错参数时给出的提示里永远看不到它们。
     */
    @Test
    void usageBlockMentionsShortAndCover() throws Exception {
        String yaml = Files.readString(LANG, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder usage = new StringBuilder();
        boolean inUsage = false;
        for (String line : yaml.split("\n", -1)) {
            if (line.startsWith("usage:")) {
                inUsage = true;
            } else if (inUsage && !line.isBlank() && !line.startsWith(" ")
                    && !line.startsWith("#")) {
                inUsage = false;
            }
            if (inUsage) usage.append(line).append('\n');
        }
        String body = usage.toString().toLowerCase(Locale.ROOT);
        assertTrue(body.contains("/mstock short"), "usage 短提示缺少 /mstock short");
        assertTrue(body.contains("/mstock cover"), "usage 短提示缺少 /mstock cover");
    }

    @Test
    void tabCompletionOffersHelpPageNumbers() throws Exception {
        List<String> lines = JavaSource.read(COMMAND);
        String body = JavaSource.methodBody(lines, "public List<String> onTabComplete");
        assertTrue(body != null, "找不到 onTabComplete");
        assertTrue(body.contains("HELP_PAGES"),
                "help 的 Tab 补全必须按 HELP_PAGES 枚举页码，避免页数改动后补全列表过期");
    }
}
