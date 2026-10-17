package dev.onelili.mstock.leverage;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LeverageService} 里三处"停牌守卫"的<b>接线回归测试</b>（需求 #2，资金级）。
 *
 * <p><b>为什么不能写成普通单测</b>：{@code LeverageService} 的构造器要求一个非 null 的
 * {@code MineStock}，而离线无法构造它 —— {@code new MineStock()} 会抛
 * {@code IllegalStateException: JavaPlugin requires to be created by a valid classloader.}
 * （Paper 1.20.1 的 {@code JavaPlugin} 构造器强制要求
 * {@code ConfiguredPluginClassLoader.init(this)}，已用字节码核实）。
 * 因此这三条守卫落在"编排层无法离线覆盖"的区间里，
 * 而它们守的是需求 #2「停牌 = 价格冻结、不能交易、也不强平」在<b>资金层面</b>的落点。
 *
 * <p><b>守卫生效时会发生什么</b>：
 * <ul>
 *   <li>{@code sweepLiquidations} 少一处 {@code continue} ⇒ 用冻结价给停牌仓位估值，
 *       玩家在停牌期间<b>被强平</b>；</li>
 *   <li>{@code close} 少一处判断 ⇒ 停牌股仍可手动平仓；</li>
 *   <li>{@code closeQuantity} 少一处判断 ⇒ 部分平仓照样成交；</li>
 *   <li>{@code closeAll} 少一处判断 ⇒ 同一仓位经 {@code /mstock margin close} 被拒、
 *       经 {@code /mstock margin closeall} 却能成交 —— 换条指令就绕过了需求 #2。</li>
 * </ul>
 *
 * <p>这四处都是"删掉不会有编译错误、也不会有任何既有测试变红"的代码 ——
 * 正是本类存在的理由。结构断言是唯一能捕获"整行被删或被挪到取价之后"的手段。
 *
 * <p>本类<b>不</b>用 {@code assumeTrue} 跳过：源文件读不到时必须<b>失败</b>，
 * 否则就复刻了它要消灭的那种假绿。
 */
class LeverageServiceWiringTest {

    /** CWD 为项目根（{@code LangUtilFallbackTest} 读 resources 已确立该前提）。 */
    private static final Path SOURCE = Paths.get("src", "main", "java", "dev", "onelili",
            "mstock", "leverage", "LeverageService.java");

    private static final String SWEEP_SIGNATURE =
            "public int sweepLiquidations(SymbolPriceSource prices,";
    private static final String CLOSE_4ARG_SIGNATURE =
            "public CompletableFuture<TradeOutcome> close(UUID playerUuid, long positionId, "
                    + "double currentPrice, SymbolPriceSource prices)";
    private static final String CLOSE_QUANTITY_SIGNATURE =
            "public CompletableFuture<PartialClose> closeQuantity(UUID playerUuid, MarketType market,";
    private static final String CLOSE_ALL_SIGNATURE =
            "public CompletableFuture<TradeOutcome> closeAll(UUID playerUuid, SymbolPriceSource prices)";

    @Test
    void sourceIsReadable() {
        assertTrue(JavaSource.isReadable(SOURCE),
                "读不到 " + SOURCE.toAbsolutePath()
                        + "；本测试依赖 CWD = 项目根，跳过会掩盖停牌守卫回归，故此处硬失败");
    }

    /**
     * 强平巡检：停牌守卫必须在<b>取价之前</b>，且必须 {@code continue}。
     *
     * <p>顺序是这条守卫的全部意义：放在 {@code fetchPrice} 之后意味着"已经用冻结价
     * 算过估值了"，放在 {@code updateMarginUnrealized} 之后则已经把冻结价的盈亏写进了库。
     */
    @Test
    void sweepSkipsSuspendedPositionsBeforeFetchingPrice() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(SOURCE), SWEEP_SIGNATURE);
        assertNotNull(body, "没能定位 sweepLiquidations 的方法体");

        assertTrue(body.contains("prices.tradable(position.getMarket(), position.getSymbol())"),
                "sweepLiquidations 必须用 prices.tradable(...) 判定停牌");
        assertTrue(body.contains("continue;"),
                "停牌必须 continue 跳过，而不是继续走估值与强平");

        assertTrue(JavaSource.appearsBefore(body,
                        "prices.tradable(position.getMarket(), position.getSymbol())", "fetchPrice(prices,"),
                "停牌守卫必须排在 fetchPrice 之前 —— 否则已经用冻结价取过价了");
        assertTrue(JavaSource.appearsBefore(body,
                        "prices.tradable(position.getMarket(), position.getSymbol())",
                        "updateMarginUnrealized"),
                "停牌守卫必须排在 updateMarginUnrealized 之前 —— 否则冻结价的盈亏已写进库");
        assertTrue(JavaSource.appearsBefore(body,
                        "prices.tradable(position.getMarket(), position.getSymbol())",
                        "settleClose(position, price, true)"),
                "停牌守卫必须排在结算之前 —— 否则停牌期间仍会被强平（需求 #2）");
    }

    /**
     * 手动平仓（4 参重载）：停牌必须被拒，且拒绝发生在 {@code settleClose} 之前。
     *
     * <p>拒绝要给出可读理由：{@code simulation-suspended}，不能静默返回成功。
     */
    @Test
    void manualCloseRejectsSuspendedBeforeSettling() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(SOURCE), CLOSE_4ARG_SIGNATURE);
        assertNotNull(body, "没能定位 close(UUID,long,double,SymbolPriceSource) 的方法体");

        assertTrue(body.contains("prices.tradable(position.getMarket(), position.getSymbol())"),
                "close 的 4 参重载必须用 prices.tradable(...) 判定停牌");
        assertTrue(body.contains("simulation-suspended"),
                "停牌平仓必须返回 simulation-suspended 文案，不能静默成功或报无关错误");
        assertTrue(JavaSource.appearsBefore(body,
                        "prices.tradable(position.getMarket(), position.getSymbol())",
                        "settleClose(position, currentPrice)"),
                "停牌判断必须在 settleClose 之前 —— 否则仓位已经被删、钱已经付出去");
    }

    /**
     * 部分平仓：停牌必须在查询持仓<b>之前</b>就返回零结果。
     *
     * <p>{@code PartialClose} 是 4 组件 record，没有失败原因字段，故这里返回
     * {@code new PartialClose(0, 0, 0, List.of())}（语义："未平掉任何仓位"）；
     * 用户可见的明确拒绝发生在 {@code SimulationService.closeLeveraged} 的前置校验。
     */
    @Test
    void partialCloseRejectsSuspendedBeforeQueryingPositions() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(SOURCE), CLOSE_QUANTITY_SIGNATURE);
        assertNotNull(body, "没能定位 closeQuantity 的方法体");

        assertTrue(body.contains("prices.tradable(market, symbol)"),
                "closeQuantity 必须用 prices.tradable(...) 判定停牌");
        assertTrue(JavaSource.appearsBefore(body, "prices.tradable(market, symbol)",
                        "findMarginPositionsBySymbol"),
                "停牌判断必须在查询持仓之前 —— 否则会真的平掉一部分仓位");
    }

    /**
     * 全部平仓：停牌必须在取价<b>之前</b>被跳过。
     *
     * <p>这条守卫覆盖 {@code close}、{@code closeQuantity}、
     * {@code sweepLiquidations} 三处都有，唯独 {@code closeAll} 没有 —— 于是同一个停牌仓位
     * 经 {@code /mstock margin close} 被正确拒绝，经 {@code /mstock margin closeall} 却能成交。
     * 玩家只要换条指令就能绕过需求 #2，这是行为自相矛盾，也是停牌保护的实际缺口。
     */
    @Test
    void closeAllSkipsSuspendedPositionsBeforeFetchingPrice() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(SOURCE), CLOSE_ALL_SIGNATURE);
        assertNotNull(body, "没能定位 closeAll 的方法体");

        assertTrue(body.contains("prices.tradable(position.getMarket(), position.getSymbol())"),
                "closeAll 必须用 prices.tradable(...) 判定停牌，否则停牌股可经 closeall 绕过"
                        + " close 的拒绝（需求 #2）");
        assertTrue(body.contains("continue;"),
                "停牌必须 continue 跳过，而不是继续走估值与结算");

        assertTrue(JavaSource.appearsBefore(body,
                        "prices.tradable(position.getMarket(), position.getSymbol())", "fetchPrice(prices,"),
                "停牌守卫必须排在 fetchPrice 之前 —— 否则已经用冻结价取过价了");
        assertTrue(JavaSource.appearsBefore(body,
                        "prices.tradable(position.getMarket(), position.getSymbol())",
                        "settleClose(position, price)"),
                "停牌守卫必须排在结算之前 —— 否则停牌仓位已经被删、钱已经付出去");
    }

    /**
     * 全部平仓的结果必须区分"停牌被跳过"与"行情获取失败"。
     *
     * <p>若所有仓位都被停牌挡下却报 {@code margin-close-all-failed}
     * （文案："没有可平仓的仓位，或行情获取失败"），玩家会以为<b>稍后重试即可</b>，
     * 或以为自己没有仓位 —— 真相是这些仓位当前被禁止交易。
     * 部分跳过时也必须告知跳过数，否则玩家不知道有仓位没平掉。
     */
    @Test
    void closeAllReportsSuspendedSkipsDistinctlyFromFetchFailure() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(SOURCE), CLOSE_ALL_SIGNATURE);
        assertNotNull(body, "没能定位 closeAll 的方法体");

        assertTrue(body.contains("margin-close-all-suspended"),
                "全部被停牌挡下时必须返回 margin-close-all-suspended，"
                        + "不能与 margin-close-all-failed（行情失败）混为一谈");
        assertTrue(body.contains("margin-close-all-blocked"),
                "混合场景（部分停牌 + 其余取价失败）必须返回 margin-close-all-blocked，"
                        + "否则被停牌挡住的那几笔对玩家不可见");
        assertTrue(body.contains("margin-close-all-partial"),
                "部分被停牌跳过时必须返回 margin-close-all-partial 并带上跳过数");

        // 判据本身也要锁住：把 `suspended == positions.size()` 放宽成 `suspended > 0`
        // 会让"全部停牌"与"部分停牌+其余失败"两种情形报同一句话，玩家分不清该等停牌还是该重试。
        assertTrue(body.contains("suspended == positions.size()"),
                "「全部被停牌」的判据必须是 suspended == positions.size()，"
                        + "放宽成 suspended > 0 会让两种不同原因共用同一句话");

        assertTrue(JavaSource.appearsBefore(body, "suspended++", "settleClose(position, price)"),
                "跳过计数必须在 continue 之前累加，且位于结算之前");
    }

    /**
     * 三处守卫都必须容忍 {@code prices == null}（离线/未接线时按"可交易"处理）。
     *
     * <p>若写成裸调用，{@code prices} 为 null 时巡检会抛 NPE ——
     * 而巡检是周期任务，抛出的异常会被调度器当成任务失败，
     * 重复任务可能就此被取消，导致<b>全服强平停止</b>。
     */
    @Test
    void everyGuardToleratesNullPriceSource() throws IOException {
        List<String> lines = JavaSource.read(SOURCE);

        for (String[] each : new String[][]{
                {SWEEP_SIGNATURE, "sweepLiquidations"},
                {CLOSE_4ARG_SIGNATURE, "close(4参)"},
                {CLOSE_QUANTITY_SIGNATURE, "closeQuantity"},
                {CLOSE_ALL_SIGNATURE, "closeAll"}}) {
            String body = JavaSource.methodBody(lines, each[0]);
            assertNotNull(body, "没能定位 " + each[1] + " 的方法体");
            assertTrue(body.contains("prices != null"),
                    each[1] + " 的停牌守卫必须写成 prices != null && !prices.tradable(...)，"
                            + "否则 prices 为 null 时抛 NPE 会让周期巡检任务被调度器取消");
        }
    }
}
