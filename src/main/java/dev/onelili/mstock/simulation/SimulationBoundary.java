package dev.onelili.mstock.simulation;

import java.util.Locale;

/**
 * 模拟盘价格触及涨跌停带时的边界策略。
 *
 * <p>三种策略的取舍：
 * <ul>
 *   <li>{@link #HARD_CLAMP} —— 越界即截断到涨跌停价，最"像"真实盘，价格绝不落在带外；</li>
 *   <li>{@link #BOUNCE} —— 反射，价格从涨停"弹"回（越过对侧则夹到对侧）；</li>
 *   <li>{@link #SOFT_REGRESSION} —— 默认，越界部分减半，几何衰减逼近。</li>
 * </ul>
 *
 * <p><b>注意</b>：{@code SOFT_REGRESSION} 按公式 <code>price = limit + (price-limit)*0.5</code>
 * 计算时，本 tick 的价格<b>仍在带外</b>，只是越界幅度每 tick 减半；它能保证的是
 * "不翻越到相反一侧"，<b>不是</b>"不越界"。需要"绝不越界"的场合请用 {@link #HARD_CLAMP}。
 */
public enum SimulationBoundary {
    /** 越界即截断到涨跌停价。 */
    HARD_CLAMP,
    /** 反射：price = 2*limit - price（越过对侧则夹到对侧）。 */
    BOUNCE,
    /** 默认：越界部分减半，几何衰减逼近。 */
    SOFT_REGRESSION;

    /**
     * 解析配置 / 指令里的策略名，无法识别时返回 {@code null}（由调用方决定回落值）。
     *
     * <p><b>与 {@link #id()} 的往返保证</b>：本方法必须能解析 {@link #id()} 的返回值。
     * 即 {@code parse(x.id()) == x} 对任意常量成立 —— 因为 {@code id()} 会被写进
     * {@code config.yml} 与数据库列 {@code simulation_stocks.boundary}，读回时走的就是本方法。
     * 早期的别名表只认 {@code hard/clamp/soft/regress} 而不认 {@code hard_clamp/soft_regression}
     * （{@code id()} 的实际输出），会导致"写进去再读回来"静默退化为 {@link #SOFT_REGRESSION}，
     * 故此处同时接受枚举全称。
     *
     * @param raw 枚举全称 {@code hard_clamp}/{@code bounce}/{@code soft_regression}，
     *            或别名 {@code hard}/{@code clamp}、{@code bounce}/{@code reflect}、
     *            {@code soft}/{@code regress}（大小写不敏感，两端空白忽略）
     */
    public static SimulationBoundary parse(String raw) {
        if (raw == null) return null;
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "hard", "clamp", "hard_clamp" -> HARD_CLAMP;
            case "bounce", "reflect" -> BOUNCE;
            case "soft", "regress", "soft_regression" -> SOFT_REGRESSION;
            default -> null;
        };
    }

    /** 配置 / 指令里使用的稳定标识（小写枚举名）。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
