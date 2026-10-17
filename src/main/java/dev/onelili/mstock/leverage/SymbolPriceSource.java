package dev.onelili.mstock.leverage;

import dev.onelili.mstock.model.MarketType;

import java.util.concurrent.CompletableFuture;

/**
 * 按品种取实时价格的最小接口。
 *
 * <p>用它把"行情从哪来"与"保证金怎么算"解耦：外汇走 biquote，股票走股票行情源，
 * 单元测试可以直接注入固定价格。
 *
 * <p><b>必须按市场路由</b>：股票代码与外汇代码没有可靠的命名分界
 * （例如 {@code AAPL} 既是美股代码，也可能同时是 biquote 的股票 CFD 代码），
 * 只看代码无法判断该问哪个数据源，因此调用方必须携带市场类型。
 */
@FunctionalInterface
public interface SymbolPriceSource {

    /**
     * 返回该品种的当前价格（外汇 / CFD 为 {@code mid}，股票为含 price-ratio 的现价）。
     *
     * @param market 持仓所属市场，决定路由到哪个数据源
     */
    CompletableFuture<Double> price(MarketType market, String symbol);

    /**
     * 该品种此刻是否可交易（停牌 / 禁售时为 {@code false}）。默认恒可交易。
     *
     * <p><b>为什么是 {@code default} 而不是抽象方法</b>：本接口是
     * {@code @FunctionalInterface}，写成抽象方法会让 {@code MineStock.priceSource()}、
     * {@code ForexService.priceSource()} 与 {@code unavailable(...)} 的 lambda 全部编译失败。
     * {@code default} 方法不占用"唯一的抽象方法"名额。
     *
     * <p>强平巡检据此跳过停牌的模拟股 —— 停牌期间价格冻结，若冻结价已跌破强平线，
     * 不跳过就会持续强平玩家。
     */
    default boolean tradable(MarketType market, String symbol) {
        return true;
    }

    /** 数据源不可用时的占位实现。 */
    static SymbolPriceSource unavailable(String reason) {
        return (symbol, market) ->
                CompletableFuture.failedFuture(new IllegalStateException(reason));
    }
}
