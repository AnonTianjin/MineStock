package dev.onelili.mstock.config;

import dev.onelili.mstock.MineStock;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainConfig {
    private final MineStock plugin;

    public MainConfig(MineStock plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public List<String> getRecommendedPool() {
        return cfg().getStringList("recommended-pool");
    }

    /** Whether MineStock should discover the daily recommendation pool automatically. */
    public boolean isRecommendedPoolAuto() {
        String value = cfg().getString("recommended-pool", "");
        return "auto".equalsIgnoreCase(value.strip());
    }

    public int getRecommendedCount() {
        return Math.max(1, cfg().getInt("recommended-count", 3));
    }

    public int getAutoCandidateCount() {
        return Math.max(getRecommendedCount(), Math.min(500,
                cfg().getInt("auto-candidate-count", 100)));
    }

    public long getSignRefreshTicks() {
        long seconds = Math.max(30L, cfg().getLong("sign-refresh-seconds", 300L));
        return seconds * 20L;
    }

    public int getApiCooldownSeconds() {
        return cfg().getInt("api-cooldown-seconds", 30);
    }

    /**
     * 获取操作冷却时间（毫秒）。
     * 优先读取 api-cooldown-ms，不存在则兼容旧配置 api-cooldown-seconds × 1000。
     */
    public long getApiCooldownMs() {
        if (cfg().contains("api-cooldown-ms")) {
            return cfg().getLong("api-cooldown-ms", 1000L);
        }
        return cfg().getInt("api-cooldown-seconds", 30) * 1000L;
    }

    public double getPriceRatio() {
        return cfg().getDouble("price-ratio", 1.0);
    }

    /**
     * 手续费<b>费率</b>（已归一化的小数，范围 [0, 1)），供各交易路径直接乘名义价值。
     *
     * <p>配置项 {@code transaction-fee-percent} 的单位是<b>百分数</b>：
     * 填 {@code 0.5} 表示 0.5%，本方法返回 {@code 0.005}；填 {@code 1.0} 表示 1%，
     * 返回 {@code 0.01}；填 {@code 0} 表示免手续费。
     *
     * <p><b>注意别把费率写进配置</b>：过去这里的注释写的是"例如 0.005 表示 0.5%"，
     * 与实现正好差 100 倍 —— 照着注释填 {@code 0.005} 会得到十万分之五。
     * 负数按 0 处理（等价免手续费），不做报错。
     */
    public double getTransactionFeeRate() {
        double pct = cfg().getDouble("transaction-fee-percent", 0.5);
        return Math.max(0.0, pct) / 100.0;
    }

    public List<Map<?, ?>> getUsStockApis() {
        List<Map<?, ?>> list = new ArrayList<>();
        List<?> raw = cfg().getList("us-stock-apis");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) list.add(m);
            }
        }
        return list;
    }

    public long getKlineCacheMs() {
        return cfg().getLong("kline-cache-ms", 300_000L);
    }

    // ── 外汇 ────────────────────────────────────────────────────────────────

    public boolean isForexEnabled() {
        return cfg().getBoolean("forex.enabled", true);
    }

    /** biquote REST 基地址，便于自建镜像或测试替换。 */
    public String getForexBaseUrl() {
        String value = cfg().getString("forex.base-url", "https://biquote.io");
        return value == null || value.isBlank() ? "https://biquote.io" : value.strip();
    }

    /** 活跃品种列表的缓存时长（毫秒），默认 60 秒。 */
    public long getForexSymbolCacheMs() {
        return Math.max(0L, cfg().getLong("forex.symbol-cache-ms", 60_000L));
    }

    /** 外汇行情页每页展示的品种数量。 */
    public int getForexSymbolsPerPage() {
        return Math.max(1, Math.min(45, cfg().getInt("forex.symbols-per-page", 45)));
    }

    // ── 期货 ────────────────────────────────────────────────────────────────

    public boolean isFuturesEnabled() {
        return cfg().getBoolean("futures.enabled", true);
    }

    /** 管理员显式声明的合约列表；为空时由 biquote 品种元数据自动派生。 */
    public List<Map<?, ?>> getFuturesContracts() {
        List<Map<?, ?>> list = new ArrayList<>();
        List<?> raw = cfg().getList("futures.contracts");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) list.add(m);
            }
        }
        return list;
    }

    /** 由 {@code initialMarginRate} 换算默认杠杆，默认 0.1 → 10 倍。 */
    public double getFuturesDefaultInitialMarginRate() {
        double rate = cfg().getDouble("futures.default-initial-margin-rate", 0.1);
        if (!Double.isFinite(rate) || rate <= 0.0) return 0.1;
        return Math.min(1.0, rate);
    }

    /** 期货盯市巡检周期（tick）。 */
    public long getFuturesSettleCheckTicks() {
        return Math.max(20L, cfg().getLong("futures.settle-check-seconds", 60L) * 20L);
    }

    // ── 图表 ────────────────────────────────────────────────────────────────

    /** 图表指令的每玩家冷却（毫秒），默认 3 秒，独立于 api-cooldown。 */
    public long getChartCooldownMs() {
        return Math.max(0L, cfg().getLong("chart.cooldown-ms", 3_000L));
    }

    public String getChartDefaultInterval() {
        String value = cfg().getString("chart.default-interval", "1h");
        return value == null || value.isBlank() ? "1h" : value.strip();
    }

    public int getChartDefaultLimit() {
        return Math.max(2, cfg().getInt("chart.default-limit", 20));
    }

    public int getChartMaxLimit() {
        return Math.max(getChartDefaultLimit(), cfg().getInt("chart.max-limit", 30));
    }
}
