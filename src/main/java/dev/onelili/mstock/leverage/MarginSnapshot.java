package dev.onelili.mstock.leverage;

import dev.onelili.mstock.model.MarginPosition;

import java.util.List;

/** 玩家保证金总览，供 {@code /mstock margin} 与 LeverageGui 顶行共用。 */
public record MarginSnapshot(List<MarginPosition> positions,
                             double totalMargin,
                             double totalUnrealizedPnl) {

    public static MarginSnapshot of(List<MarginPosition> positions) {
        double margin = 0.0;
        double pnl = 0.0;
        for (MarginPosition position : positions) {
            margin += position.getMargin();
            pnl += position.getUnrealizedPnl();
        }
        return new MarginSnapshot(List.copyOf(positions), margin, pnl);
    }

    public int positionCount() {
        return positions.size();
    }

    /**
     * 总保证金率 {@code (margin + pnl) / margin}。
     *
     * <p>无持仓时返回 {@code 1.0}，表示健康。
     */
    public double marginRatio() {
        if (totalMargin <= 0) return 1.0;
        return (totalMargin + totalUnrealizedPnl) / totalMargin;
    }
}
