package dev.onelili.mstock.util;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言键回落行为测试。
 *
 * <p>背景：服务端数据目录里的 {@code lang.yml} 是<b>首次启用时保存的旧副本</b>，
 * 之后升级插件新增的键在磁盘文件里并不存在。若读取时不显式回落到 jar 内置默认值，
 * 玩家聊天框就会刷屏 {@code Missing lang key: xxx}（线上已实际发生）。
 *
 * <p>这里直接用 Bukkit 的 {@link YamlConfiguration} 复现该场景，不依赖服务端。
 */
class LangUtilFallbackTest {

    /** 模拟 jar 内置的最新 lang.yml（含新键）。 */
    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new StringReader("""
                prefix: "<gold>[MineStock]</gold> "
                leverage-set: "杠杆已设置为 {leverage}x"
                margin-open-success: "开仓成功 #{id}"
                old-key: "内置默认值"
                """));
    }

    /** 模拟数据目录里的旧 lang.yml（升级前保存，缺少新键）。 */
    private static YamlConfiguration legacyOnDisk() {
        return YamlConfiguration.loadConfiguration(new StringReader("""
                prefix: "[旧前缀] "
                old-key: "玩家自定义值"
                """));
    }

    /**
     * 关键回归：两个 {@code getString} 重载对 defaults 的处理<b>不同</b>。
     *
     * <p>单参版本会回落到 {@code setDefaults} 设定的默认值；
     * 双参版本直接用传入的 {@code def} 兜底、<b>不查</b> defaults。
     *
     * <p>线上刷屏 {@code Missing lang key} 的根因就是原实现用了双参版本。
     * 这个断言把这个易踩的差异固定下来。
     */
    @Test
    void twoArgGetStringDoesNotFallBackToDefaults() {
        YamlConfiguration disk = legacyOnDisk();
        disk.setDefaults(bundled());

        // defaults 里的键"看起来存在"，但 isSet 为 false
        assertTrue(disk.contains("leverage-set"), "contains 会看到 defaults");
        assertFalse(disk.isSet("leverage-set"), "本地没有该键，isSet 为 false");

        // 单参版本 → 回落到 defaults
        assertEquals("杠杆已设置为 {leverage}x", disk.getString("leverage-set"));

        // 双参版本 → 不回落到 defaults，直接用传入的兜底值（这就是 bug 来源）
        assertEquals("DEF", disk.getString("leverage-set", "DEF"));
    }

    /** 旧磁盘文件里已有的键优先于内置默认值（玩家的自定义不能被覆盖）。 */
    @Test
    void diskValueWinsOverBundledDefault() {
        YamlConfiguration disk = legacyOnDisk();
        disk.setDefaults(bundled());

        assertEquals("玩家自定义值", disk.getString("old-key"));
        assertEquals("[旧前缀] ", disk.getString("prefix"));
    }

    /**
     * 复刻 {@link LangUtil#getRaw} 的读取顺序：单参 getString → 显式查 defaults → 缺失占位。
     *
     * <p>验证"旧磁盘文件 + 新键"能取到 jar 内置文案，玩家的自定义仍然优先。
     */
    @Test
    void explicitFallbackResolvesNewKeysAgainstLegacyFile() {
        YamlConfiguration disk = legacyOnDisk();
        disk.setDefaults(bundled());

        assertEquals("杠杆已设置为 {leverage}x", resolve(disk, "leverage-set"));
        assertEquals("开仓成功 #{id}", resolve(disk, "margin-open-success"));
        // 玩家自定义仍然优先于内置默认值
        assertEquals("玩家自定义值", resolve(disk, "old-key"));
        // 两个来源都没有 → 才报缺失
        assertEquals("<red>Missing lang key: nope</red>", resolve(disk, "nope"));
    }

    /** 与 {@code LangUtil#getRaw} 相同的读取顺序。 */
    private static String resolve(YamlConfiguration lang, String key) {
        String value = lang.getString(key);
        if (value != null) return value;
        Configuration defaults = lang.getDefaults();
        if (defaults != null) {
            String fallback = defaults.getString(key);
            if (fallback != null) return fallback;
        }
        return "<red>Missing lang key: " + key + "</red>";
    }

    /** 没有任何 defaults 时不应抛异常（例如独立测试环境）。 */
    @Test
    void missingDefaultsIsHandledGracefully() {
        YamlConfiguration disk = legacyOnDisk();
        assertNull(disk.getDefaults());
        assertEquals("<red>Missing lang key: leverage-set</red>",
                resolve(disk, "leverage-set"));
    }

    /**
     * 真实的 {@code src/main/resources/lang.yml} 必须包含本次新增的全部键。
     *
     * <p>直接读源文件（而非手写样例），这样新增功能时若忘记同步文案，
     * 测试会立刻失败，而不是等到线上刷 {@code Missing lang key}。
     */
    @Test
    void realLangFileContainsKeysUsedByNewFeatures() throws Exception {
        java.nio.file.Path langFile = java.nio.file.Paths.get(
                "src/main/resources/lang.yml");
        // 刻意不用 assumeTrue 跳过：本测试锁住 36 个 simulation-* 键，跳过就等于
        // 放行"删文案不删引用"的回归（线上表现为聊天框刷 Missing lang key）。
        // 读不到资源文件说明 CWD 不是项目根 —— 那是环境错误，应当失败。
        assertTrue(java.nio.file.Files.exists(langFile),
                "读不到 " + langFile.toAbsolutePath() + "；本测试依赖 CWD = 项目根");

        YamlConfiguration real = YamlConfiguration.loadConfiguration(langFile.toFile());

        for (String key : new String[]{
                // 杠杆 / 保证金
                "leverage-info", "leverage-set", "leverage-unchanged",
                "leverage-out-of-range", "leverage-new-positions-only",
                "margin-header", "margin-empty", "margin-summary",
                "margin-item-up", "margin-item-down",
                "margin-open-success", "margin-close-success", "margin-liquidated",
                // 全部平仓的三条分支：全成功 / 部分被停牌跳过 / 全部被停牌挡下。
                // 第三条尤其重要：它把"停牌不能平仓"与"行情获取失败"区分开，
                // 否则玩家看到"没有可平仓的仓位"会以为可以稍后重试。
                "margin-close-all-success", "margin-close-all-partial",
                "margin-close-all-suspended", "margin-close-all-blocked",
                "margin-close-all-failed",
                // 外汇
                "forex-price-header", "forex-price-up", "forex-price-down",
                "forex-category-invalid", "forex-search-prompt", "forex-search-header",
                "forex-search-item", "forex-search-empty", "forex-search-hint",
                // 期货
                "futures-open-success", "futures-close-success", "futures-settled",
                "futures-expired", "futures-positions-header", "futures-contract-item",
                // 图表
                "chart-loading", "chart-hint", "chart-error", "chart-interval-invalid",
                // 模拟盘（36 个键，与 SimulationService / MStockCommand 的引用一一对应；
                // 若此处失败，说明有人删了文案但没删引用，线上会刷 Missing lang key）
                "simulation-buy-success", "simulation-sell-success", "simulation-disabled",
                "simulation-unknown-code", "simulation-suspended", "simulation-no-holding",
                "simulation-not-enough-holding",
                "simulation-portfolio-header", "simulation-portfolio-empty",
                "simulation-portfolio-item", "simulation-portfolio-item-up",
                "simulation-portfolio-item-down",
                "simulation-admin-no-permission", "simulation-admin-success",
                "simulation-admin-invalid-args",
                "simulation-admin-setprice", "simulation-admin-target", "simulation-admin-jump",
                "simulation-admin-pause", "simulation-admin-suspend", "simulation-admin-tradable",
                "simulation-admin-slope", "simulation-admin-amplitude",
                "simulation-admin-preset", "simulation-admin-boundary",
                "simulation-admin-bounds", "simulation-admin-limit", "simulation-admin-link",
                "simulation-admin-schedule", "simulation-admin-schedules",
                "simulation-admin-schedules-item", "simulation-admin-schedules-empty",
                "simulation-admin-unschedule", "simulation-admin-seed",
                "simulation-admin-resume", "simulation-admin-reset",
                // 通用
                "gui-quantity-prompt", "trade-invalid-quantity", "trade-invalid-symbol",
                "trade-invalid-direction", "trade-no-price",
                // /mstock help 分页（3 页正文 + 页眉 / 页脚 / 页码错误）
                "help-header", "help-footer", "help-page-invalid",
                "help-page-1", "help-page-2", "help-page-3",
                // 原有键不能丢
                "prefix", "usage", "no-permission", "api-error", "db-error"}) {
            assertNotNull(real.getString(key), "lang.yml 缺少键: " + key);
        }
    }

    /** 文案里引用的占位符必须与代码传入的一致，否则玩家会看到裸露的 {xxx}。 */
    @Test
    void placeholderNamesMatchCallSites() throws Exception {
        java.nio.file.Path langFile = java.nio.file.Paths.get(
                "src/main/resources/lang.yml");
        assertTrue(java.nio.file.Files.exists(langFile),
                "读不到 " + langFile.toAbsolutePath() + "；本测试依赖 CWD = 项目根");

        YamlConfiguration real = YamlConfiguration.loadConfiguration(langFile.toFile());

        // 这些键在代码里以固定占位符调用，逐一核对
        assertTrue(real.getString("leverage-set", "").contains("{leverage}"));
        assertTrue(real.getString("leverage-out-of-range", "").contains("{max}"));
        assertTrue(real.getString("leverage-info", "").contains("{max}"));
        assertTrue(real.getString("margin-open-success", "").contains("{id}"));
        assertTrue(real.getString("margin-open-success", "").contains("{margin}"));
        assertTrue(real.getString("futures-open-success", "").contains("{expiry}"));
        assertTrue(real.getString("forex-search-header", "").contains("{count}"));
        assertTrue(real.getString("trade-invalid-quantity", "").isEmpty()
                        || !real.getString("trade-invalid-quantity", "").contains("{"),
                "无参数文案不该含占位符");

        // 分页帮助：页眉 / 页脚 / 非法页码提示的占位符与 handleHelp 传参一一对应
        assertTrue(real.getString("help-header", "").contains("{page}"));
        assertTrue(real.getString("help-header", "").contains("{total}"));
        assertTrue(real.getString("help-footer", "").contains("{page}"));
        assertTrue(real.getString("help-footer", "").contains("{total}"));
        assertTrue(real.getString("help-footer", "").contains("{prev}"));
        assertTrue(real.getString("help-footer", "").contains("{next}"));
        assertTrue(real.getString("help-page-invalid", "").contains("{input}"));
        assertTrue(real.getString("help-page-invalid", "").contains("{total}"));
    }
}
