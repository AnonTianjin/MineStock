package dev.onelili.mstock.util;

import dev.onelili.mstock.model.KLinePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走势图渲染测试。
 *
 * <p>重点验证"复用原实现"：新重载只是在原有柱状图外包裹标题与摘要，
 * 原有 {@code render(List<KLinePoint>)} 的行数与内容不受影响。
 */
class KLineRendererTest {

    private static List<KLinePoint> bars(int count) {
        List<KLinePoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double base = 1.10 + i * 0.001;
            points.add(new KLinePoint(base, base + 0.002, base - 0.002, base + 0.001,
                    String.format("2026-10-%02d", i + 1)));
        }
        return points;
    }

    @Test
    void originalRendererOutputIsUnchanged() {
        // 6 行柱子 + X 轴 + 日期行 = 8 行
        List<String> lines = KLineRenderer.render(bars(10));

        assertEquals(8, lines.size());
        assertTrue(lines.get(6).startsWith("<dark_gray>└"));
    }

    @Test
    void titledRendererAddsHeaderAndSummaryAroundTheSameChart() {
        List<KLinePoint> points = bars(10);
        List<String> plain = KLineRenderer.render(points);
        List<String> titled = KLineRenderer.render("EURUSD", "1h", points);

        // 标题 + 原图 + 摘要
        assertEquals(plain.size() + 2, titled.size());
        assertTrue(titled.get(0).contains("EURUSD"));
        assertTrue(titled.get(0).contains("1h"));
        assertTrue(titled.get(0).contains("最近 10 根"));
        assertEquals(plain.get(0), titled.get(1));

        String summary = titled.get(titled.size() - 1);
        assertTrue(summary.contains("最新:"));
        assertTrue(summary.contains("%"));
    }

    @Test
    void summaryShowsPositiveChangeForRisingSeries() {
        List<KLinePoint> points = List.of(
                new KLinePoint(1.10, 1.11, 1.09, 1.10, "2026-10-01"),
                new KLinePoint(1.10, 1.12, 1.10, 1.11, "2026-10-02"));

        String summary = KLineRenderer.render("EURUSD", "1h", points)
                .get(KLineRenderer.render(points).size() + 1);

        // 1.10 → 1.11 为 +0.909%
        assertTrue(summary.contains("+0.91%"), "摘要应显示正涨跌: " + summary);
    }

    @Test
    void summaryShowsNegativeChangeForFallingSeries() {
        List<KLinePoint> points = List.of(
                new KLinePoint(1.20, 1.21, 1.19, 1.20, "2026-10-01"),
                new KLinePoint(1.20, 1.20, 1.17, 1.18, "2026-10-02"));

        String summary = KLineRenderer.render("EURUSD", "1h", points)
                .get(KLineRenderer.render(points).size() + 1);

        assertTrue(summary.contains("-1.67%"), "摘要应显示负涨跌: " + summary);
    }

    @Test
    void titledRendererRefusesToDrawInsufficientData() {
        // 少于 2 根无法画出趋势，返回空列表由调用方发错误消息（不输出空图表）。
        assertTrue(KLineRenderer.render("EURUSD", "1h", List.of()).isEmpty());
        assertTrue(KLineRenderer.render("EURUSD", "1h", null).isEmpty());
        assertTrue(KLineRenderer.render("EURUSD", "1h", bars(1)).isEmpty());
        assertFalse(KLineRenderer.render("EURUSD", "1h", bars(2)).isEmpty());
    }

    @Test
    void everyChartCellKeepsItsHoverPayload() {
        List<String> lines = KLineRenderer.render(bars(5));

        // 原实现的每列 hover 明细必须保留
        assertTrue(lines.get(0).contains("<hover:show_text:"));
        assertTrue(lines.get(0).contains("开: "));
    }
}
