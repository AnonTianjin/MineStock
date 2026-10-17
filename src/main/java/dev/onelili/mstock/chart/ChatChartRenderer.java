package dev.onelili.mstock.chart;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.util.KLineRenderer;

import java.util.List;

/**
 * 把 OHLC 数据渲染成聊天框走势图。
 *
 * <p><b>复用说明</b>：绘制逻辑完全来自仓库原有的
 * {@link KLineRenderer}（柱状图 + 逐列 hover OHLC + 日期轴）。
 * 本类只负责"输入校验 + 调用渲染 + 返回消息行"，不重复实现任何绘图代码。
 *
 * <p>数据源已扩展到外汇 / 期货：任何能转成 {@link KLinePoint} 的 OHLC 都能绘制。
 */
public class ChatChartRenderer {

    /** 至少需要 2 根才能画出趋势。 */
    public static final int MIN_BARS = 2;

    /**
     * 渲染聊天框走势图。
     *
     * @param symbol   品种代码
     * @param interval K 线周期标签
     * @param bars     最早在前的 OHLC 序列
     * @return 每条对应一行聊天消息；数据不足时返回空列表，由调用方发简短错误消息
     */
    public List<String> render(String symbol, String interval, List<KLinePoint> bars) {
        if (bars == null || bars.size() < MIN_BARS) return List.of();
        return KLineRenderer.render(symbol, interval, bars);
    }

    /** 数据是否足够渲染。 */
    public boolean hasEnoughData(List<KLinePoint> bars) {
        return bars != null && bars.size() >= MIN_BARS;
    }
}
