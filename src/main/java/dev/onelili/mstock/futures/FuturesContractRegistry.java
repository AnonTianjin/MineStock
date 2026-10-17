package dev.onelili.mstock.futures;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.model.FuturesContract;
import dev.onelili.mstock.model.MarketSymbol;

/**
 * 期货合约目录。
 *
 * <p>来源优先级：
 * <ol>
 *   <li>{@code config.yml → futures.contracts}：管理员显式声明的合约，完全可控</li>
 *   <li>从 biquote 品种元数据自动派生：{@code tickSize} / {@code contractSize} 取
 *       {@code /api/symbols}，{@code expiryDate} 取下一个季末，{@code initialMarginRate}
 *       取 {@code futures.default-initial-margin-rate}</li>
 *   <li>biquote 不可达时退回内置合约表</li>
 * </ol>
 */
public class FuturesContractRegistry {

    /** biquote 不可用时的兜底合约：覆盖外汇、贵金属、加密与指数。 */
    private static final List<String> FALLBACK_SYMBOLS =
            List.of("EURUSD", "GBPUSD", "USDJPY", "XAUUSD", "XAGUSD", "BTCUSD", "ETHUSD",
                    "US500", "DXY", "USOIL");

    /**
     * 从配置读取合约。
     *
     * @param entries     配置中的合约条目
     * @param defaultRate {@code initialMarginRate} 缺省值
     * @return 以 symbol 为键的有序映射；配置为空时返回空映射（由调用方决定回退策略）
     */
    public static Map<String, FuturesContract> fromConfig(List<Map<?, ?>> entries,
                                                          double defaultRate) {
        Map<String, FuturesContract> result = new LinkedHashMap<>();
        if (entries == null) return result;
        for (Map<?, ?> entry : entries) {
            String symbol = text(entry.get("symbol"));
            if (symbol == null) continue;
            String upper = symbol.toUpperCase(Locale.ROOT);
            String expiry = text(entry.get("expiry-date"));
            if (expiry == null) expiry = text(entry.get("expiryDate"));
            if (expiry == null) expiry = nextQuarterEnd(LocalDate.now()).toString();
            result.put(upper, new FuturesContract(
                    upper,
                    expiry,
                    number(entry.get("contract-size"), number(entry.get("contractSize"), 1.0)),
                    number(entry.get("tick-size"), number(entry.get("tickSize"), 0.01)),
                    number(entry.get("initial-margin-rate"),
                            number(entry.get("initialMarginRate"), defaultRate))
            ));
        }
        return result;
    }

    /**
     * 从 biquote 品种元数据派生合约。
     *
     * <p>只保留外汇 / 商品 / 指数 / 加密，股票 CFD 不进期货目录。
     */
    public static Map<String, FuturesContract> fromSymbols(List<MarketSymbol> symbols,
                                                           double rate,
                                                           LocalDate today) {
        Map<String, FuturesContract> result = new LinkedHashMap<>();
        if (symbols == null) return result;
        String expiry = nextQuarterEnd(today).toString();
        for (MarketSymbol symbol : symbols) {
            if (symbol == null) continue;
            switch (symbol.getMarket()) {
                case FOREX, COMMODITY, INDEX, CRYPTO -> {
                    result.put(symbol.getName().toUpperCase(Locale.ROOT), new FuturesContract(
                            symbol.getName().toUpperCase(Locale.ROOT),
                            expiry,
                            symbol.getContractSize() > 0 ? symbol.getContractSize() : 1.0,
                            symbol.getTickSize() > 0 ? symbol.getTickSize() : 0.01,
                            rate
                    ));
                }
                default -> {
                    // 股票 CFD 不属于期货目录
                }
            }
        }
        return result;
    }

    /** biquote 不可达时的兜底合约表。 */
    public static Map<String, FuturesContract> fallback(double rate, LocalDate today) {
        Map<String, FuturesContract> result = new LinkedHashMap<>();
        String expiry = nextQuarterEnd(today).toString();
        Map<String, Double> contractSizes = Map.of(
                "XAUUSD", 100.0, "XAGUSD", 5000.0, "BTCUSD", 1.0, "ETHUSD", 1.0,
                "US500", 1.0, "DXY", 1000.0, "USOIL", 1000.0);
        for (String symbol : FALLBACK_SYMBOLS) {
            result.put(symbol, new FuturesContract(
                    symbol, expiry, contractSizes.getOrDefault(symbol, 100000.0), 0.01, rate));
        }
        return result;
    }

    /**
     * 下一个季末（3/6/9/12 月最后一日），<b>严格晚于</b> {@code today}。
     *
     * <p>必须是严格晚于：{@code FuturesContract#isExpired} 认为"到期日 == 今天"即已到期，
     * 若允许返回当天，在季末当天派生出的合约会一出生就不可交易。
     */
    public static LocalDate nextQuarterEnd(LocalDate today) {
        int[] quarterEndMonths = {3, 6, 9, 12};
        for (int month : quarterEndMonths) {
            LocalDate candidate = LocalDate.of(today.getYear(), month, 1)
                    .with(TemporalAdjusters.lastDayOfMonth());
            if (candidate.isAfter(today)) return candidate;
        }
        return LocalDate.of(today.getYear() + 1, 3, 1)
                .with(TemporalAdjusters.lastDayOfMonth());
    }

    /** 过滤出未到期合约。 */
    public static List<FuturesContract> activeOnly(Map<String, FuturesContract> contracts,
                                                    LocalDate today) {
        List<FuturesContract> result = new ArrayList<>();
        for (FuturesContract contract : contracts.values()) {
            if (!contract.isExpired(today)) result.add(contract);
        }
        return result;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) && d > 0 ? d : fallback;
        }
        if (value == null) return fallback;
        try {
            double d = Double.parseDouble(String.valueOf(value).strip());
            return Double.isFinite(d) && d > 0 ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
