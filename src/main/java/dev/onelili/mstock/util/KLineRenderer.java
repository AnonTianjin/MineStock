package dev.onelili.mstock.util;

import dev.onelili.mstock.model.KLinePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple block bar renderer: each cell is either a solid █ (bar color) or a gray █ (empty placeholder).
 *
 * Body  — bar rises from the bottom to the close row using █.
 * Empty — rows above the bar use a dark-gray █ as placeholder.
 *
 * Axes: │ on left (↑ on top row), └─…─→ on bottom. No Y-axis price labels.
 * Color: UP (#FF5555) when close >= previous close, DOWN (#55FF55) otherwise.
 * Hover: every cell carries OHLC detail for that column.
 */
public class KLineRenderer {

    private static final int HEIGHT     = 6;
    private static final int MAX_POINTS = 30;
private static final String UP       = "<color:#FF5555>";
    private static final String DOWN     = "<color:#55FF55>";
    private static final String DG       = "<dark_gray>";
    private static final String GRAY_BAR = "<dark_gray>█</dark_gray>";

    public static List<String> render(List<KLinePoint> points) {
        if (points == null || points.isEmpty()) return List.of();

        List<KLinePoint> sampled = sample(points, MAX_POINTS);
        int n = sampled.size();
        double globalMax = sampled.stream()
                .mapToDouble(p -> Math.max(p.getHigh(), p.getClose())).max().orElse(1);
        double globalMin = sampled.stream()
                .mapToDouble(p -> Math.min(p.getLow(), p.getClose())).min().orElse(0);
        double range = globalMax - globalMin;
        if (range == 0) range = 1;

        int[] slotClose = new int[n];
        for (int i = 0; i < n; i++) {
            slotClose[i] = toSlot(sampled.get(i).getClose(), globalMin, range);
        }

        boolean[] isUp = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                isUp[i] = n == 1 || sampled.get(0).getClose() >= sampled.get(1).getClose();
            } else {
                isUp[i] = sampled.get(i).getClose() >= sampled.get(i - 1).getClose();
            }
        }

        String[] hovers = new String[n];
        for (int i = 0; i < n; i++) {
            hovers[i] = buildHover(sampled.get(i));
        }

        List<String> lines = new ArrayList<>(HEIGHT + 2);

        for (int row = HEIGHT - 1; row >= 0; row--) {
            StringBuilder sb = new StringBuilder();
            sb.append(DG).append(row == HEIGHT - 1 ? '↑' : '│');

            String currentColor = null;

            for (int i = 0; i < n; i++) {
                boolean filled = row <= slotClose[i];
                if (filled) {
                    String color = isUp[i] ? UP : DOWN;
                    if (!color.equals(currentColor)) {
                        sb.append(color);
                        currentColor = color;
                    }
                    sb.append(hovers[i]).append('█').append("</hover>");
                } else {
                    if (currentColor != null) currentColor = null;
                    sb.append(hovers[i]).append(GRAY_BAR).append("</hover>");
                }
            }
            lines.add(sb.toString());
        }

        // X axis.
        lines.add(DG + "└" + "─".repeat(n) + "→");

        String startLabel = formatDate(sampled.getFirst().getDate());
        String endLabel   = formatDate(sampled.getLast().getDate());
        lines.add("<gray>" + startLabel + "</gray>"
                + "<dark_gray> 至 </dark_gray>"
                + "<gray>" + endLabel + "</gray>"
                + "<dark_gray> 周期 </dark_gray>"
                + "<white>" + n + "</white>"
                + "<dark_gray> 天</dark_gray>");

        return lines;
    }

    private static int toSlot(double price, double globalMin, double range) {
        int slot = (int) Math.round((price - globalMin) / range * (HEIGHT - 1));
        return Math.max(0, Math.min(HEIGHT - 1, slot));
    }

    private static String formatDate(String date) {
        if (date != null && date.length() == 10 && date.charAt(4) == '-') {
            // "2025-05-29" → "5月29日"
            int month = Integer.parseInt(date.substring(5, 7));
            int day   = Integer.parseInt(date.substring(8, 10));
            return month + "月" + day + "日";
        }
        return date != null ? date : "";
    }

    private static String buildHover(KLinePoint p) {
        return "<hover:show_text:'<gray>" + p.getDate()
                + "<newline><white>开: " + fmt(p.getOpen())
                + "  高: " + fmt(p.getHigh())
                + "<newline>低: " + fmt(p.getLow())
                + "  收: " + fmt(p.getClose())
                + "</white></gray>'>";
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static List<KLinePoint> sample(List<KLinePoint> points, int maxCount) {
        if (points.size() <= maxCount) return points;
        List<KLinePoint> result = new ArrayList<>(maxCount);
        double step = (double) (points.size() - 1) / (maxCount - 1);
        for (int i = 0; i < maxCount; i++) {
            result.add(points.get((int) Math.round(i * step)));
        }
        return result;
    }

    // ── 带标题的调用方式 ─────────────────────────────────────────────────────
    // 原 render(List<KLinePoint>) 的行为与输出完全不变；下面这个重载只是把标题行与
    // 最新价摘要包在外面，供 /mstock chart 与 /mstock forex chart 使用。
    // 数据源已扩展到外汇 / 期货 —— 只要 OHLC 能转成 KLinePoint，绘制逻辑完全复用。

    /**
     * 渲染带标题的走势图。
     *
     * @param symbol   品种代码，如 {@code EURUSD}
     * @param interval K 线周期标签，如 {@code 1h}
     * @param points   最早在前的 OHLC 序列
     * @return 每条对应一行聊天消息；数据不足时返回空列表（由调用方发错误提示）
     */
    public static List<String> render(String symbol, String interval, List<KLinePoint> points) {
        if (points == null || points.size() < 2) return List.of();

        List<String> lines = new ArrayList<>(HEIGHT + 4);

        String safeSymbol = symbol == null ? "?" : symbol.toUpperCase(java.util.Locale.ROOT);
        String safeInterval = interval == null || interval.isBlank() ? "1h" : interval;
        lines.add("<gold>▍</gold><white>" + safeSymbol + "</white> <dark_gray>"
                + safeInterval + " 走势图 (最近 " + points.size() + " 根)</dark_gray>");

        lines.addAll(render(points));
        lines.add(buildSummary(points));
        return lines;
    }

    /** 最新价与相对前一根的涨跌，涨 {@code §a}/红、跌 {@code §c}/绿，与原图配色一致。 */
    private static String buildSummary(List<KLinePoint> points) {
        KLinePoint last = points.get(points.size() - 1);
        KLinePoint previous = points.get(points.size() - 2);
        double reference = previous.getClose();
        double change = reference != 0
                ? (last.getClose() - reference) / reference * 100.0
                : 0.0;
        boolean up = change >= 0;
        String color = up ? UP : DOWN;
        return DG + "最新: </dark_gray><white>" + fmt(last.getClose()) + "</white> "
                + color + (up ? "+" : "") + fmt(change) + "%" + colorTagClose();
    }

    private static String colorTagClose() {
        return "</color>";
    }
}
