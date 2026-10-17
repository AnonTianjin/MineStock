package dev.onelili.mstock.simulation;

import dev.onelili.mstock.model.KLinePoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimulationKLineBuffer} 的周期聚合测试。
 *
 * <p>这些用例锁的是一个具体回归：旧签名 {@code toKLinePoints(bars, days)} 只有一个
 * {@code days} 参数，同时充当"窗口天数"和"聚合粒度"。{@code ChartService.fetchBars}
 * 传 {@code days = Math.max(limit, 30)}，于是只要历史跨了 2 个自然日以上，
 * 无论玩家请求 1m 还是 1d，都会走进日线分支 —— 玩家看到的就是"要分钟级 K 线，
 * 画出来却是日线（只有 1~2 根柱子）"。
 *
 * <p>本测试全部是纯函数：不启动 Bukkit，不碰数据库，不依赖系统时区偏移量
 * （日期用 {@code LocalDate.atStartOfDay} 推导，夏令时也不会错位）。
 */
class SimulationKLineIntervalTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final long MINUTE_MS = 60_000L;
    private static final double DELTA = 1e-9;

    /** 3 个自然日，每天前 30 分钟各一根 1 分钟 bar（共 90 根，价格逐根 +0.1）。 */
    private static List<SimulationBar> threeDaysOfMinutes() {
        List<SimulationBar> bars = new ArrayList<>();
        double price = 10.0;
        for (int day = 0; day < 3; day++) {
            long dayStart = LocalDate.of(2026, 1, 5)
                    .plusDays(day)
                    .atStartOfDay(ZONE)
                    .toInstant()
                    .toEpochMilli();
            for (int minute = 0; minute < 30; minute++) {
                bars.add(bar(dayStart + minute * MINUTE_MS, price));
                price += 0.1;
            }
        }
        return List.copyOf(bars);
    }

    /** 一根 bar：开 = 传入价，高/低 = ±0.5，收 = +0.05 —— 四个字段互不相同，聚合写错就会露馅。 */
    private static SimulationBar bar(long bucketStart, double price) {
        return new SimulationBar(bucketStart, price, price + 0.5, price - 0.5, price + 0.05);
    }

    // ── 周期 → 毫秒 ──────────────────────────────────────────────────────────

    /** 聊天图表给得出的 7 个周期都必须有确切的毫秒值。 */
    @Test
    void intervalMillisCoversEverySupportedPeriod() {
        assertEquals(MINUTE_MS, SimulationKLineBuffer.intervalMillis("1m"));
        assertEquals(5 * MINUTE_MS, SimulationKLineBuffer.intervalMillis("5m"));
        assertEquals(15 * MINUTE_MS, SimulationKLineBuffer.intervalMillis("15m"));
        assertEquals(30 * MINUTE_MS, SimulationKLineBuffer.intervalMillis("30m"));
        assertEquals(60 * MINUTE_MS, SimulationKLineBuffer.intervalMillis("1h"));
        assertEquals(240 * MINUTE_MS, SimulationKLineBuffer.intervalMillis("4h"));
        assertEquals(1440 * MINUTE_MS, SimulationKLineBuffer.intervalMillis("1d"));
    }

    /** 未知 / 空 / null 一律退回 1 分钟，不抛异常（配置里写错周期不该炸掉行情图）。 */
    @Test
    void unknownIntervalsFallBackToOneMinute() {
        assertEquals(MINUTE_MS, SimulationKLineBuffer.intervalMillis(null));
        assertEquals(MINUTE_MS, SimulationKLineBuffer.intervalMillis(""));
        assertEquals(MINUTE_MS, SimulationKLineBuffer.intervalMillis("   "));
        assertEquals(MINUTE_MS, SimulationKLineBuffer.intervalMillis("3m"));
        assertEquals(MINUTE_MS, SimulationKLineBuffer.intervalMillis("2d"));
        assertEquals(MINUTE_MS, SimulationKLineBuffer.intervalMillis("nonsense"));
        // 大小写与首尾空格不该改变语义
        assertEquals(60 * MINUTE_MS, SimulationKLineBuffer.intervalMillis(" 1H "));
    }

    // ── 回归：周期决定粒度，跨多少天都不改变粒度 ─────────────────────────────

    /** 核心回归：跨 3 个自然日请求 1m，必须拿到 90 根分钟柱，而不是 3 根日柱。 */
    @Test
    void minuteRequestKeepsMinuteBarsEvenAcrossSeveralDays() {
        List<SimulationBar> bars = threeDaysOfMinutes();

        List<KLinePoint> points = SimulationKLineBuffer.toKLinePoints(bars, "1m", 500);

        assertEquals(bars.size(), points.size(), "1m 请求必须逐分钟返回，不能被压成日线");
        assertNotEquals(3, points.size(), "压成日线正是本次修复要消除的回归");
        for (KLinePoint point : points) {
            assertTrue(point.getDate().contains(":"),
                    "分钟柱标签应为 MM-dd HH:mm，实得 " + point.getDate());
        }
        assertEquals(bars.get(0).open(), points.get(0).getOpen(), DELTA);
        assertEquals(bars.get(bars.size() - 1).close(), points.get(points.size() - 1).getClose(), DELTA);
    }

    /** 请求 1d 时按本地日历日聚合：3 天 3 根，标签为 ISO 日期且不含时间。 */
    @Test
    void dailyRequestAggregatesByLocalCalendarDay() {
        List<SimulationBar> bars = threeDaysOfMinutes();

        List<KLinePoint> points = SimulationKLineBuffer.toKLinePoints(bars, "1d", 500);

        assertEquals(3, points.size(), "3 个自然日应收敛成 3 根日线");
        for (KLinePoint point : points) {
            assertTrue(point.getDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                    "日线标签应为 yyyy-MM-dd，实得 " + point.getDate());
        }
        assertEquals(bars.get(0).open(), points.get(0).getOpen(), DELTA);
        assertEquals(bars.get(bars.size() - 1).close(), points.get(2).getClose(), DELTA);
    }

    /** 本用例里每天只喂 30 分钟，所以 1h 与 4h 都是"每天一根"。 */
    @Test
    void coarserPeriodsCollapseSparseDaysCorrectly() {
        List<SimulationBar> bars = threeDaysOfMinutes();

        assertEquals(3, SimulationKLineBuffer.toKLinePoints(bars, "1h", 500).size());
        assertEquals(3, SimulationKLineBuffer.toKLinePoints(bars, "4h", 500).size());
        assertEquals(3, SimulationKLineBuffer.toKLinePoints(bars, "30m", 500).size());
        // 每天 30 分钟按 15m 聚合 = 每天 2 根，共 6 根。
        // 能精确到 2 是因为所有真实时区偏移都是 15 分钟的整数倍，本地午夜必然落在 15m 桶边界上。
        assertEquals(6, SimulationKLineBuffer.toKLinePoints(bars, "15m", 500).size());
    }

    // ── limit 只表示"最多几根" ───────────────────────────────────────────────

    /** limit 截的是聚合之后的结果，且保留最近的若干根。 */
    @Test
    void limitKeepsOnlyTheMostRecentBars() {
        List<SimulationBar> bars = threeDaysOfMinutes();

        List<KLinePoint> points = SimulationKLineBuffer.toKLinePoints(bars, "1m", 7);

        assertEquals(7, points.size());
        assertEquals(bars.get(bars.size() - 1).close(), points.get(6).getClose(), DELTA);
        assertEquals(bars.get(bars.size() - 1).open(), points.get(6).getOpen(), DELTA);
    }

    // ── OHLC 合并规则 ────────────────────────────────────────────────────────

    /** 首开、最高取 max、最低取 min、末收 —— 字段错位是聚合实现最容易犯的错。 */
    @Test
    void aggregationPreservesOpenHighLowClose() {
        List<SimulationBar> bars = threeDaysOfMinutes();

        List<KLinePoint> points = SimulationKLineBuffer.toKLinePoints(bars, "30m", 500);

        assertEquals(3, points.size());
        // 第一天 30 根：价 10.0 → 12.9
        KLinePoint first = points.get(0);
        assertEquals(10.0, first.getOpen(), DELTA, "开盘取该组第一根的开");
        assertEquals(13.4, first.getHigh(), DELTA, "最高取该组 max(high)");
        assertEquals(9.5, first.getLow(), DELTA, "最低取该组 min(low)");
        assertEquals(12.95, first.getClose(), DELTA, "收盘取该组最后一根的收");
    }

    /** 刚启用模拟盘只有几分钟历史时，日线聚合不足 2 根必须退回分钟，否则图上画不出东西。 */
    @Test
    void sparseHistoryFallsBackToMinutes() {
        long dayStart = LocalDate.of(2026, 3, 2).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<SimulationBar> few = new ArrayList<>();
        for (int minute = 0; minute < 5; minute++) {
            few.add(bar(dayStart + minute * MINUTE_MS, 20.0 + minute));
        }

        List<KLinePoint> points = SimulationKLineBuffer.toKLinePoints(List.copyOf(few), "1d", 500);

        assertEquals(5, points.size(), "日线只攒出 1 根时退回分钟粒度");
        assertTrue(points.get(0).getDate().contains(":"), "退回后应是分钟标签");
    }
}
