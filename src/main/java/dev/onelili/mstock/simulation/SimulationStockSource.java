package dev.onelili.mstock.simulation;

import dev.onelili.mstock.model.StockInfo;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.stockio.StockSource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 模拟盘行情源：把 {@link SimulationService} 接到 {@code StockApiService}。
 *
 * <p><b>必须插在 {@code sources} 的 index 0</b>：{@code StockApiService} 的遍历语义是
 * "首个 {@code supports} 者胜出"。{@code SIM001}（3 字母 + 3 数字）与现有三源的正则
 * （{@code ^\d{6}$} / {@code ^\d{1,5}$} / {@code ^[A-Za-z]{1,10}$}）均不匹配，本无冲突，
 * 但 index 0 是设计明确要求的插入点。
 *
 * <p><b>价格不含 price-ratio</b>：与 {@code ChinaStockApi} 等约定一致，ratio 由消费方施加。
 */
public final class SimulationStockSource implements StockSource {

    /** 模拟代码格式：3 个字母 + 3 位数字。 */
    public static final Pattern CODE = Pattern.compile("^[A-Z]{3}\\d{3}$");

    private final SimulationService service;

    /** 只持有 service，不持有 plugin。 */
    public SimulationStockSource(SimulationService service) {
        this.service = service;
    }

    /**
     * <b>关闭态必须恒为 {@code false}</b>（{@code supports} 必须恒为 {@code false}）。
     *
     * <p>只查 {@code registry} 是不够的：{@link SimulationService#reconfigure} 会把既有股票
     * <b>逐只搬迁</b>进新注册表而<b>不清空</b>，于是管理员把 {@code simulation.enabled} 改成
     * {@code false} 再执行 {@code /mstock reload} 之后，注册表里仍然有 {@code SIM001} ——
     * 若此处不判 {@code isEnabled()}，{@code api.isSupported("SIM001")} 就仍为 {@code true}，
     * 指令层的路由器又因为"未启用"而放行，最终 {@code /mstock buy SIM001 10} 会以真实股票
     * 身份写入 {@code holdings} 表并扣真实 Vault 余额。
     */
    @Override
    public boolean supports(String code) {
        if (code == null) return false;
        if (!service.isEnabled()) return false;
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        return CODE.matcher(normalized).matches() && service.findStock(normalized).isPresent();
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        // 关闭态一律失败（纵深防御）：即使调用方没有先问 supports，也拿不到模拟价
        // 去写真实持仓表 / 真实保证金仓位。
        SimulationStock stock =
                service.isEnabled() ? service.findStock(normalize(code)).orElse(null) : null;
        if (stock == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Unsupported simulation code: " + code));
        }
        // 价格取 currentPrice 且不含 price-ratio（与 ChinaStockApi 等约定一致，ratio 由消费方施加）：
        // currentPrice 已含人工覆盖、涨跌停与绝对上下限，与详情页展示的价格同源。
        double price = SimulationPriceEngine.safePrice(stock.getCurrentPrice());
        return CompletableFuture.completedFuture(new StockInfo(stock.getCode(), stock.getName(),
                price, stock.changeAmount(), stock.changePercent()));
    }

    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        // 与 supports / fetch 同样的关闭态门控（纵深防御）。
        SimulationStock stock =
                service.isEnabled() ? service.findStock(normalize(code)).orElse(null) : null;
        if (stock == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Unsupported simulation code: " + code));
        }
        // days 夹取 [1, 3650]：与 SimulationService.kline 的第二道夹取一致（纵深防御）
        return service.kline(stock.getCode(), Math.max(1, Math.min(3650, days)));
    }

    /**
     * 带周期的 K 线：模拟盘是唯一真正按周期聚合的源。
     *
     * <p>{@code limit} 是"最多几根"，夹取 [2, 3650] —— 下界取 2 而不是 1，因为
     * {@code ChatChartRenderer.MIN_BARS == 2}，只要 1 根就一定渲染失败并回落 biquote，
     * 而 biquote 根本不认识 {@code SIM001}，最终变成一句 {@code chart-error}。
     */
    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, String interval, int limit) {
        SimulationStock stock =
                service.isEnabled() ? service.findStock(normalize(code)).orElse(null) : null;
        if (stock == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Unsupported simulation code: " + code));
        }
        return service.kline(stock.getCode(), interval, Math.max(2, Math.min(3650, limit)));
    }

    /** 空实现：不释放 {@link SimulationService}（它的生命周期由 {@code MineStock} 管理）。 */
    @Override
    public void close() {
    }

    /** 代码归一化：去空白 + 大写。 */
    private static String normalize(String code) {
        return code == null ? null : code.strip().toUpperCase(Locale.ROOT);
    }
}
