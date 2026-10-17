package dev.onelili.mstock.simulation;

import dev.onelili.mstock.model.KLinePoint;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模拟盘的 1 分钟 OHLC 环形缓冲。
 *
 * <p>只保存最近 {@code capacity} 根 bar；已闭合（分钟已翻页）的 bar 由
 * {@link #drainClosed()} 取出并交给 {@link SimulationRepository} 落库，
 * 因此缓冲内存与运行时长无关。
 *
 * <p>记入的是 {@link SimulationStock#getCurrentPrice()}（<b>含</b>人工覆盖、涨跌停与上下限
 * 之后的值），因为图表必须与详情页展示的价格一致。
 */
public final class SimulationKLineBuffer {

    /** 默认容量（根）。 */
    public static final int DEFAULT_CAPACITY = 512;

    /** 一根 bar 的毫秒宽度（1 分钟）。 */
    private static final long BUCKET_MS = 60_000L;

    /** 逐根 1 分钟 bar 的日期格式：{@code MM-dd HH:mm}。 */
    private static final DateTimeFormatter MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    /** 按日聚合的日期格式：{@code yyyy-MM-dd}。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** {@code ChartService} 可渲染的最少根数（{@code ChatChartRenderer.MIN_BARS == 2}）。 */
    private static final int MIN_BARS = 2;

    private final int capacity;

    /** 环形缓冲：最早在前；{@code size() <= capacity}。 */
    private final Deque<SimulationBar> bars = new ArrayDeque<>();

    /** 当前未闭合 bar 的桶起点；{@code < 0} 表示尚无未闭合 bar。 */
    private long openBucketStart = -1L;

    /** 当前未闭合 bar 的累加器。 */
    private double openOpen;
    private double openHigh;
    private double openLow;
    private double openClose;

    /**
     * @param capacity 容量；{@code < 1} 时使用 {@link #DEFAULT_CAPACITY}
     */
    public SimulationKLineBuffer(int capacity) {
        this.capacity = capacity < 1 ? DEFAULT_CAPACITY : capacity;
    }

    /**
     * 累加当前分钟 OHLC；时间跨分钟时自动闭合上一根。
     *
     * <p>{@code price} 非有限时忽略本次记录（避免 NaN 污染 K 线）。
     */
    public synchronized void record(long epochMillis, double price) {
        if (!Double.isFinite(price)) {
            return;
        }
        long bucket = bucketStart(epochMillis);
        if (openBucketStart < 0) {
            // 首根：直接开一个新桶
            openBucket(bucket, price);
            return;
        }
        if (bucket == openBucketStart) {
            // 同桶累加
            openHigh = Math.max(openHigh, price);
            openLow = Math.min(openLow, price);
            openClose = price;
            return;
        }
        if (bucket > openBucketStart) {
            // 分钟翻页：先闭合上一根（可能跨多分钟，中间的空桶不补）
            appendClosed();
            openBucket(bucket, price);
            return;
        }
        // bucket < openBucketStart：乱序/时钟回拨的样本并入当前桶，不新建
        openHigh = Math.max(openHigh, price);
        openLow = Math.min(openLow, price);
        openClose = price;
    }

    /**
     * 取出并移除所有已闭合（分钟已翻页）的 bar，最早在前；当前未闭合的留在缓冲里。
     *
     * <p>本方法本身不判定"是否已翻页"（缓冲不知道上一个 tick 的时间），
     * 因此语义为：把缓冲里除当前未闭合 bar 以外的部分全部取出。当且仅当
     * {@link #record} 已观察到分钟翻页并闭合了至少一根时，返回非空列表。
     */
    public synchronized List<SimulationBar> drainClosed() {
        if (bars.isEmpty()) {
            return List.of();
        }
        List<SimulationBar> closed = new ArrayList<>(bars);
        bars.clear();
        return List.copyOf(closed);
    }

    /** 快照（含未闭合的当前 bar），最早在前，长度 {@code <= capacity}。 */
    public synchronized List<SimulationBar> snapshot() {
        List<SimulationBar> result = new ArrayList<>(bars.size() + 1);
        result.addAll(bars);
        if (openBucketStart >= 0) {
            result.add(currentBar());
        }
        return List.copyOf(result);
    }

    /** 清空缓冲（{@code reset} 用；不删数据库历史）。 */
    public synchronized void clear() {
        bars.clear();
        openBucketStart = -1L;
        openOpen = 0.0;
        openHigh = 0.0;
        openLow = 0.0;
        openClose = 0.0;
    }

    /** 当前缓冲的 bar 根数。 */
    public synchronized int size() {
        return bars.size() + (openBucketStart >= 0 ? 1 : 0);
    }

    /** 桶起点：{@code epochMillis - epochMillis % 60_000L}。 */
    private static long bucketStart(long epochMillis) {
        return epochMillis - Math.floorMod(epochMillis, BUCKET_MS);
    }

    /** 开启一个新桶。调用方需保证已把上一根闭合入队。 */
    private void openBucket(long bucket, double price) {
        this.openBucketStart = bucket;
        this.openOpen = price;
        this.openHigh = price;
        this.openLow = price;
        this.openClose = price;
    }

    /** 把当前未闭合 bar 收进环形缓冲，并按容量淘汰最旧的一根。 */
    private void appendClosed() {
        if (openBucketStart < 0) {
            return;
        }
        bars.addLast(currentBar());
        while (bars.size() > capacity) {
            bars.pollFirst();
        }
        openBucketStart = -1L;
    }

    /** 当前未闭合 bar 的不可变快照。 */
    private SimulationBar currentBar() {
        return new SimulationBar(openBucketStart, openOpen, openHigh, openLow, openClose);
    }

    /**
     * 纯函数：把 1 分钟 bar 转成 K 线点列表。
     *
     * <p>{@code days <= 1} 或"按日聚合后不足 2 根" → 逐根 1 分钟 bar，{@code date = "MM-dd HH:mm"}；
     * 否则按 {@link java.time.LocalDate} 聚合（首开 / 最高 / 最低 / 末收），{@code date = "yyyy-MM-dd"}，
     * 只保留最后 {@code days} 组。
     */
    public static List<KLinePoint> toKLinePoints(List<SimulationBar> bars, int days) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        if (days <= 1) {
            return minutePoints(bars);
        }
        List<KLinePoint> daily = dailyPoints(bars, days);
        // "按日聚合后不足 2 根" ⇒ 退回逐根 1 分钟 bar（保证图上看得到东西）
        if (daily.size() < MIN_BARS) {
            return minutePoints(bars);
        }
        return daily;
    }

    /** 逐根 1 分钟 bar，{@code date = "MM-dd HH:mm"}。 */
    private static List<KLinePoint> minutePoints(List<SimulationBar> bars) {
        List<KLinePoint> points = new ArrayList<>(bars.size());
        for (SimulationBar bar : bars) {
            if (bar == null) {
                continue;
            }
            points.add(new KLinePoint(bar.open(), bar.high(), bar.low(), bar.close(), format(bar.bucketStart())));
        }
        return List.copyOf(points);
    }

    /** 按日聚合（首开 / 最高 / 最低 / 末收），只保留最后 {@code days} 组。 */
    private static List<KLinePoint> dailyPoints(List<SimulationBar> bars, int days) {
        Map<LocalDate, KLinePoint> byDay = new LinkedHashMap<>();
        for (SimulationBar bar : bars) {
            if (bar == null) {
                continue;
            }
            LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(bar.bucketStart()), ZoneId.systemDefault());
            KLinePoint existing = byDay.get(day);
            if (existing == null) {
                byDay.put(day, new KLinePoint(bar.open(), bar.high(), bar.low(), bar.close(),
                        DAY_FORMAT.format(day)));
            } else {
                byDay.put(day, new KLinePoint(existing.getOpen(),
                        Math.max(existing.getHigh(), bar.high()),
                        Math.min(existing.getLow(), bar.low()),
                        bar.close(),
                        existing.getDate()));
            }
        }
        List<KLinePoint> all = new ArrayList<>(byDay.values());
        if (all.size() <= days) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(all.size() - days, all.size()));
    }

    /**
     * 周期 → 桶宽（毫秒）。未知 / {@code null} 一律按 1 分钟。
     *
     * <p>取值集合与 {@code ForexProvider.INTERVALS}（{@code 1m 5m 15m 30m 1h 4h 1d}）一致：
     * 聊天图表的周期选择器给什么，这里就必须认什么，否则玩家换了周期图却不换。
     */
    public static long intervalMillis(String interval) {
        if (interval == null) {
            return BUCKET_MS;
        }
        return switch (interval.strip().toLowerCase(Locale.ROOT)) {
            case "5m" -> 5L * BUCKET_MS;
            case "15m" -> 15L * BUCKET_MS;
            case "30m" -> 30L * BUCKET_MS;
            case "1h" -> 60L * BUCKET_MS;
            case "4h" -> 240L * BUCKET_MS;
            case "1d" -> 1440L * BUCKET_MS;
            default -> BUCKET_MS;
        };
    }

    /**
     * 纯函数：按<b>请求的周期</b>把 1 分钟 bar 聚合成 K 线点。
     *
     * <p>旧签名 {@link #toKLinePoints(List, int)} 只有一个 {@code days} 参数，它同时充当
     * "窗口天数"和"聚合粒度"两个语义 —— 于是 {@code ChartService} 传
     * {@code days = max(limit, 30)} 时，无论玩家选 1m 还是 1d，只要历史跨了 2 天以上
     * 就一律画成日线。这正是"要的是分钟级 K 线，实际画成了日线"的根因。
     * 本重载把粒度交还给 {@code interval}，{@code limit} 只表示"最多要几根"。
     *
     * <p>聚合后不足 {@link #MIN_BARS} 根时（例如刚启用、只攒了几分钟历史）退回 1 分钟粒度，
     * 保证图上至少画得出东西（与旧实现相同的兜底承诺）。
     */
    public static List<KLinePoint> toKLinePoints(List<SimulationBar> bars, String interval, int limit) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        String tf = normalizeInterval(interval);
        List<KLinePoint> points = tail(aggregate(bars, tf), limit);
        if (points.size() < MIN_BARS && !"1m".equals(tf)) {
            List<KLinePoint> minute = tail(minutePoints(bars), limit);
            if (minute.size() >= MIN_BARS) {
                return minute;
            }
        }
        return points;
    }

    /** 周期归一化：{@code null} / 空 / 未知 → {@code 1m}。 */
    private static String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank()) {
            return "1m";
        }
        String tf = interval.strip().toLowerCase(Locale.ROOT);
        return switch (tf) {
            case "1m", "5m", "15m", "30m", "1h", "4h", "1d" -> tf;
            default -> "1m";
        };
    }

    /**
     * 分组键：同一周期同一桶的 bar 归为一根 K 线。
     *
     * <p>小时及以下的周期按 epoch 对齐（服务器时区偏移为整小时时与本地对齐等价）；
     * {@code 4h} 与 {@code 1d} 必须按<b>本地</b>日历分组 —— 用 epoch 除 4 小时会得到
     * UTC 的 00:00 分界，在 +08:00 显示成 08:00，日线则会跨在当天早上八点断开。
     */
    private static long groupOf(long bucketStart, String interval) {
        var local = Instant.ofEpochMilli(bucketStart).atZone(ZoneId.systemDefault());
        return switch (interval) {
            case "5m" -> bucketStart / (5L * BUCKET_MS);
            case "15m" -> bucketStart / (15L * BUCKET_MS);
            case "30m" -> bucketStart / (30L * BUCKET_MS);
            case "1h" -> local.toEpochSecond() / 3600L;
            case "4h" -> local.toLocalDate().toEpochDay() * 6L + local.getHour() / 4L;
            case "1d" -> local.toLocalDate().toEpochDay();
            default -> bucketStart;
        };
    }

    /** 按 {@code interval} 聚合（首开 / 最高 / 最低 / 末收）；输入需按桶起点升序。 */
    private static List<KLinePoint> aggregate(List<SimulationBar> bars, String interval) {
        Map<Long, KLinePoint> groups = new LinkedHashMap<>();
        for (SimulationBar bar : bars) {
            if (bar == null) {
                continue;
            }
            long key = groupOf(bar.bucketStart(), interval);
            KLinePoint existing = groups.get(key);
            if (existing == null) {
                groups.put(key, new KLinePoint(bar.open(), bar.high(), bar.low(), bar.close(),
                        label(bar.bucketStart(), interval)));
            } else {
                groups.put(key, new KLinePoint(existing.getOpen(),
                        Math.max(existing.getHigh(), bar.high()),
                        Math.min(existing.getLow(), bar.low()),
                        bar.close(),
                        existing.getDate()));
            }
        }
        return List.copyOf(groups.values());
    }

    /** 时间标签：日线用 {@code yyyy-MM-dd}，其余用 {@code MM-dd HH:mm}。 */
    private static String label(long bucketStart, String interval) {
        if ("1d".equals(interval)) {
            return DAY_FORMAT.format(
                    LocalDate.ofInstant(Instant.ofEpochMilli(bucketStart), ZoneId.systemDefault()));
        }
        return format(bucketStart);
    }

    /** 只保留最后 {@code limit} 根；{@code limit <= 0} 时按 1 根处理。 */
    private static List<KLinePoint> tail(List<KLinePoint> points, int limit) {
        int keep = Math.max(1, limit);
        if (points.size() <= keep) {
            return points;
        }
        return List.copyOf(points.subList(points.size() - keep, points.size()));
    }

    /** 桶起点 → {@code MM-dd HH:mm}。 */
    private static String format(long bucketStart) {
        return MINUTE_FORMAT.format(Instant.ofEpochMilli(bucketStart).atZone(ZoneId.systemDefault()));
    }

    /**
     * 纯函数：把 K 线点补齐到 {@code ChartService} 可渲染的最少根数。
     *
     * <p>{@code ChartService.fetchBars} 在 {@code points.size() < ChatChartRenderer.MIN_BARS(== 2)}
     * 时会回退 biquote 并最终报 {@code chart-error}。模拟盘刚启用时缓冲为空，因此当可用 bar
     * 少于 2 根时，用 {@code prevClose} 合成一根"上一分钟"的平盘 bar，保证至少 2 根。
     */
    public static List<KLinePoint> withFallback(List<SimulationBar> bars, int days,
                                                String code, double prevClose, long nowMillis) {
        List<SimulationBar> source = bars == null ? List.of() : bars;
        List<KLinePoint> points = toKLinePoints(source, days);
        if (points.size() >= MIN_BARS) {
            return points;
        }
        // 用 prevClose 合成"上一分钟"的平盘 bar，补到至少 MIN_BARS 根
        // （空缓冲时需要两根：now-2min 与 now-1min）；prevClose 非法时退化为 0.01，绝不产出非有限值
        double anchor = (Double.isFinite(prevClose) && prevClose > 0) ? prevClose : 0.01;
        long lastBucket = bucketStart(nowMillis) - BUCKET_MS;
        int synthetic = MIN_BARS - source.size();

        List<SimulationBar> patched = new ArrayList<>(source.size() + synthetic);
        for (int i = synthetic; i >= 1; i--) {
            patched.add(SimulationBar.flat(lastBucket - (i - 1) * BUCKET_MS, anchor));
        }
        patched.addAll(source);
        return toKLinePoints(patched, days);
    }

    /**
     * 纯函数：{@link #withFallback(List, int, String, double, long)} 的周期感知版本。
     *
     * <p>补齐的判断必须放在<b>聚合之后</b>：先按请求周期聚合、再看根数够不够，
     * 否则"补两根 1 分钟平盘 bar 再聚合成 1h"会得到一根，等于没补。
     */
    public static List<KLinePoint> withFallback(List<SimulationBar> bars, String interval, int limit,
                                                String code, double prevClose, long nowMillis) {
        List<SimulationBar> source = bars == null ? List.of() : bars;
        List<KLinePoint> points = toKLinePoints(source, interval, limit);
        if (points.size() >= MIN_BARS) {
            return points;
        }
        // 用 prevClose 合成"上一分钟"的平盘 bar，补到至少 MIN_BARS 根；
        // prevClose 非法时退化为 0.01，绝不产出非有限值
        double anchor = (Double.isFinite(prevClose) && prevClose > 0) ? prevClose : 0.01;
        long lastBucket = bucketStart(nowMillis) - BUCKET_MS;
        int synthetic = Math.max(0, MIN_BARS - source.size());

        List<SimulationBar> patched = new ArrayList<>(source.size() + synthetic);
        for (int i = synthetic; i >= 1; i--) {
            patched.add(SimulationBar.flat(lastBucket - (i - 1) * BUCKET_MS, anchor));
        }
        patched.addAll(source);
        return toKLinePoints(patched, interval, limit);
    }
}
