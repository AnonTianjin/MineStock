package dev.onelili.mstock.simulation;

import java.time.LocalTime;

/**
 * 模拟盘的定时价格冲击。
 *
 * @param id            主键
 * @param code          目标股票代码
 * @param timeOfDay     触发时刻，格式 {@code "HH:mm"}（24 小时制）
 * @param changePercent 冲击幅度（百分数，乘法叠加）
 * @param repeatDaily   {@code true} 每日重复；{@code false} 触发一次后自动禁用
 * @param enabled       是否启用
 * @param lastFiredDate 上次触发的日期（{@code yyyy-MM-dd}，未触发过为 {@code ""}）
 */
public record SimulationSchedule(long id, String code, String timeOfDay, double changePercent,
                                 boolean repeatDaily, boolean enabled, String lastFiredDate) {

    /** {@code "HH:mm"} 的长度：2 位小时 + 冒号 + 2 位分钟。 */
    private static final int TIME_LENGTH = 5;

    /**
     * 精确到分钟的匹配：{@code timeOfDay} 与 {@code now} 的小时 + 分钟相等即视为命中。
     *
     * <p>只匹配<b>当前分钟</b>，不补算服务器离线期间错过的时刻。
     *
     * <p><b>严格解析</b>：必须是恰好 5 字符的 {@code "HH:mm"}，小时 {@code 00-23}、分钟
     * {@code 00-59} 且各占 2 位（{@code "9:30"} 与 {@code "24:00"} 一律非法）——
     * 不借助 {@link LocalTime#parse}，因为它的宽容度随 JDK 版本变化，而该格式必须稳定。
     *
     * @param timeOfDay {@code "HH:mm"}；格式非法一律返回 {@code false}
     */
    public static boolean matches(String timeOfDay, LocalTime now) {
        if (timeOfDay == null || now == null) return false;
        String raw = timeOfDay.strip();
        if (raw.length() != TIME_LENGTH || raw.charAt(2) != ':') return false;

        int hour = twoDigits(raw, 0);
        int minute = twoDigits(raw, 3);
        if (hour < 0 || minute < 0) return false;
        if (hour > 23 || minute > 59) return false;

        return now.getHour() == hour && now.getMinute() == minute;
    }

    /** 读取 2 位十进制数；任一位非数字返回 {@code -1}。 */
    private static int twoDigits(String raw, int offset) {
        int high = Character.digit(raw.charAt(offset), 10);
        int low = Character.digit(raw.charAt(offset + 1), 10);
        if (high < 0 || low < 0) return -1;
        return high * 10 + low;
    }
}
