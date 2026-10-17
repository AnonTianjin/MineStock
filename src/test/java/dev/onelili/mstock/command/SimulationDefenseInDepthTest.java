package dev.onelili.mstock.command;

import dev.onelili.mstock.testsupport.JavaSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟盘 ↔ 真实盘隔离（资金级）的<b>纵深防御接线回归测试</b>。
 *
 * <p><b>为什么单独有这个类</b>：{@code MStockCommand.executeBuy/executeSell}
 * 的两条守卫原本"删掉也没人发现"，补上回归测试（{@link SimulationRoutingTest}）后，
 * 沿着同一条链往下查又发现<b>三条同样零覆盖的守卫</b>。它们各自独立地守住同一个资金级不变量，
 * 任何一条被删都不会有编译错误、也不会有任何既有测试变红：
 *
 * <ol>
 *   <li><b>纵深防御 2</b>：{@code StockLeverageService.buy} 的方法级不变量 ——
 *       即使指令层的两条守卫都被绕过，模拟代码也不能落成 {@code market=EQUITY}
 *       的真实保证金仓位（含第三方经 {@code MineStockAPI} 的间接路径）。</li>
 *   <li><b>纵深防御 3</b>：{@code MineStock.symbolPrice} 里 {@code SIMULATION}
 *       分支必须排在 {@code EQUITY} 判定<b>之前</b> —— 否则模拟仓位的估值价会去问
 *       biquote，拿不到价 ⇒ 永不刷新盈亏、永不被强平、{@code /mstock margin} 显示全零。</li>
 *   <li><b>纵深防御 4</b>：{@code MineStock.priceSource().tradable} 必须对
 *       {@code SIMULATION} 真正查停牌态 —— 否则停牌股会被强平巡检用冻结价结算。
 *       这一条是需求 #2「停牌 = 价格冻结、不能交易、也不强平」在价格源侧的落点，
 *       与 {@code LeverageService} 的三处守卫（{@code LeverageServiceWiringTest}）
 *       是同一条链的两端。</li>
 * </ol>
 *
 * <p><b>为什么不写成普通单测</b>：{@code priceSource()} 与
 * {@code symbolPrice(...)} 是 {@code MineStock} 的实例方法，而 {@code new MineStock()}
 * 会抛 {@code IllegalStateException: JavaPlugin requires to be created by a valid
 * classloader.}（Paper 1.20.1 的 {@code JavaPlugin} 构造器强制要求由
 * {@code ConfiguredPluginClassLoader} 创建，已实测确认）。
 * 这些守卫的不变量都是<b>结构性</b>的（"必须在 X 之前""必须查 Y"），
 * 可以直接对源码断言 —— 而这正是唯一能捕获"整行被删或被挪位"的手段。
 *
 * <p>源码解析由 {@link JavaSource} 提供（与 {@code SimulationRoutingTest}、
 * {@code LeverageServiceWiringTest} 共用）。本类故意<b>不用</b> {@code assumeTrue}：
 * 源文件读不到时必须<b>失败</b>，否则就复刻了它要消灭的那种假绿。
 */
class SimulationDefenseInDepthTest {

    private static final Path STOCK_LEVERAGE_SERVICE =
            Paths.get("src", "main", "java", "dev", "onelili", "mstock", "stock",
                    "StockLeverageService.java");
    private static final Path MINE_STOCK =
            Paths.get("src", "main", "java", "dev", "onelili", "mstock", "MineStock.java");

    @Test
    void sourcesAreReadable() {
        assertTrue(JavaSource.isReadable(STOCK_LEVERAGE_SERVICE),
                "读不到 " + STOCK_LEVERAGE_SERVICE.toAbsolutePath()
                        + "；本测试依赖 CWD = 项目根，若为跳过它会掩盖 R1 回归，故此处硬失败");
        assertTrue(JavaSource.isReadable(MINE_STOCK),
                "读不到 " + MINE_STOCK.toAbsolutePath() + "；理由同上");
    }

    /**
     * 纵深防御 2：{@code StockLeverageService.buy} 必须在做任何事之前拒绝模拟代码。
     *
     * <p>这条不变量比指令层的两条守卫更靠内：{@code StockLeverageService.buy} 是
     * "股票加杠杆买入"的<b>唯一</b>实现，任何新调用方（未来的新指令、GUI 按钮、
     * 第三方经 {@code MineStockAPI} 的间接路径）都会经过它。它一旦失效，
     * 模拟代码就会带着 {@code MarketType.EQUITY} 落进真实 {@code margin_positions}。
     *
     * <p>断言的不只是"存在",还有<b>顺序</b>：{@code isSimulationCode} 判定必须排在
     * {@code leverageService.open(} 之前 —— 排在后面等于没拦。
     */
    @Test
    void stockLeverageServiceRejectsSimulationCodesBeforeOpening() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(STOCK_LEVERAGE_SERVICE),
                "public CompletableFuture<TradeOutcome> buy(java.util.UUID playerUuid, String code,");

        assertNotNull(body, "没能定位 StockLeverageService.buy 的方法体");
        assertTrue(body.contains("getSimulationService"),
                "StockLeverageService.buy 必须经 plugin.getSimulationService() 取模拟盘服务");
        assertTrue(body.contains("isSimulationCode"),
                "StockLeverageService.buy 必须检查代码归属，否则 SIM001 会以 market=EQUITY"
                        + " 落成真实保证金仓位");
        assertTrue(body.contains("trade-invalid-symbol"),
                "拒绝模拟代码时必须返回 trade-invalid-symbol，否则调用方无从得知拒绝原因");
        assertTrue(JavaSource.appearsBefore(body, "isSimulationCode", "leverageService.open("),
                "模拟代码判定必须排在 leverageService.open( 之前 —— 排在后面等于没拦");
    }

    /**
     * 纵深防御 3：{@code symbolPrice} 的 {@code SIMULATION} 分支必须先于 {@code EQUITY} 判定。
     *
     * <p>这个顺序是资金级的：{@code if (market != MarketType.EQUITY) return forexMid(symbol);}
     * 会把 {@code SIMULATION} 兜进外汇分支，模拟仓位从此拿到 biquote 的价（对
     * {@code SIM001} 必然失败）⇒ {@code fetchPrice} 返回 null ⇒
     * {@code sweepLiquidations} 每次 {@code continue} 跳过 ⇒
     * 保证金永久锁死，既平不掉也不会被强平。这正是本项目在真实股票 CFD 上
     * 已经踩过一次的同款缺陷。
     */
    @Test
    void simulationBranchPrecedesEquityBranchInSymbolPrice() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "public java.util.concurrent.CompletableFuture<Double> symbolPrice("
                        + "dev.onelili.mstock.model.MarketType market, String symbol)");

        assertNotNull(body, "没能定位 MineStock.symbolPrice(MarketType, String) 的方法体");
        assertTrue(body.contains("MarketType.SIMULATION"),
                "symbolPrice 必须有 SIMULATION 分支，否则模拟仓位估值会去问 biquote");
        assertTrue(body.contains("marketPrice"),
                "SIMULATION 分支必须走 service.marketPrice(...)（它已乘 price-ratio，"
                        + "与 EQUITY 分支口径一致）");
        assertTrue(JavaSource.appearsBefore(body,
                        "market == dev.onelili.mstock.model.MarketType.SIMULATION",
                        "market != dev.onelili.mstock.model.MarketType.EQUITY"),
                "SIMULATION 分支必须排在 `market != EQUITY → forexMid` 之前 ——"
                        + "排在后面会被外汇分支提前兜走，模拟仓位永不刷新盈亏、永不被强平");
    }

    /**
     * 纵深防御 4：{@code priceSource()} 的 {@code tradable} 必须对模拟盘真正查停牌态。
     *
     * <p>需求 #2 规定「停牌 = 价格冻结、不能交易、也不强平」。价格源这一端一旦
     * 恒返回 {@code true}，{@code LeverageService} 里的三处守卫（已由
     * {@code LeverageServiceWiringTest} 单独锁住）就全都拿不到停牌信息，
     * 强平巡检会用冻结价把玩家平掉 —— 这是资金级错误。
     *
     * <p>同时断言非 SIMULATION 市场<b>不受影响</b>：真实股票/外汇的停牌语义不同，
     * 不能被这个分支顺手拦下。
     */
    @Test
    void priceSourceTradableConsultsSimulationSuspension() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "public dev.onelili.mstock.leverage.SymbolPriceSource priceSource()");

        assertNotNull(body, "没能定位 MineStock.priceSource() 的方法体");
        assertTrue(body.contains("boolean tradable"),
                "priceSource() 必须覆写 tradable —— lambda 无法覆写 default 方法，"
                        + "故必须是具名实现（这是本方法存在的唯一理由）");
        assertTrue(body.contains("service.isTradable(symbol)"),
                "tradable 必须对 SIMULATION 查 service.isTradable(symbol)，"
                        + "否则停牌股会被强平巡检用冻结价结算（需求 #2）");
        assertTrue(body.contains("simulationService == null || service == null")
                        || body.contains("service == null || service.isTradable"),
                "service 未就绪时必须按「可交易」处理：否则启动竞态会让界面与巡检静默停摆");
        assertTrue(body.contains("market != dev.onelili.mstock.model.MarketType.SIMULATION"),
                "非 SIMULATION 市场必须直接返回 true，不能顺手拦下真实股票/外汇");
    }

    /**
     * 纵深防御 5：强平巡检拿到的价格源<b>必须是</b> {@code priceSource()}，
     * 不能是 {@code this::symbolPrice} 这样的 lambda。
     *
     * <p><b>为什么单独锁这一条</b>：这是绕过 {@code tradable} 覆写的真实缺陷。
     * {@code priceSource()} 的存在理由就是覆写 {@code tradable}，但
     * {@code sweepLiquidations(this::symbolPrice, ...)} 这个调用点<b>绕过了它</b> ——
     * 方法引用编译成的是 {@code SymbolPriceSource} 的函数式实现，
     * {@code tradable} 落回接口默认的 {@code return true}。
     *
     * <p>后果是行为<b>恰好反了</b>：手动平仓走 {@code plugin.priceSource()}
     * （被正确拒绝），而强平巡检走 lambda（不查停牌）—— 于是停牌的模拟股
     * 不但平不掉，还会被冻结价强制平仓。需求 #2 明确禁止。
     *
     * <p>这个缺陷能躲过纵深防御 4 的断言，正因为它验的是 {@code priceSource()}
     * 的<b>方法体</b>写得对，而这里错的是<b>调用点</b>选了另一个等价类型的对象。
     * 因此本断言直接锁调用点。
     */
    @Test
    void liquidationSweepReceivesTradableAwarePriceSource() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "private void startTradingTasks()");

        assertNotNull(body, "没能定位 MineStock.startTradingTasks() 的方法体");
        assertTrue(body.contains("sweepLiquidations("),
                "startTradingTasks 必须启动强平巡检");

        String firstArgument = firstArgumentOf(body, "sweepLiquidations(");
        assertNotNull(firstArgument, "没能取出 sweepLiquidations( 的第一个实参");
        assertTrue(firstArgument.contains("this.priceSource()"),
                "强平巡检的价格源必须是 this.priceSource()（它覆写了 tradable）；"
                        + "实际第一个实参是: " + firstArgument.strip());
        assertTrue(!firstArgument.contains("symbolPrice"),
                "强平巡检不能传 this::symbolPrice —— 方法引用无法覆写 default 方法 tradable，"
                        + "会让停牌股被冻结价强平（需求 #2）。"
                        + "这正是该方法引用绕过了 tradable 覆写");
    }

    /**
     * 纵深防御 6：{@code symbolPrice} 的 {@code SIMULATION} 分支<b>不得</b>因
     * {@code simulation.enabled: false} 而失败。
     *
     * <p><b>为什么这条也要锁</b>：做隔离修复（禁止新开仓）时很容易顺手把
     * {@code !service.isEnabled()} 一起写进这个分支 —— 那会制造一个<b>保证金永久锁死</b>：
     * <ol>
     *   <li>玩家在模拟盘开着的时候用杠杆建了仓（{@code market=SIMULATION} 已落库）；</li>
     *   <li>管理员关闭 {@code simulation.enabled} 并 {@code /mstock reload}；</li>
     *   <li>{@code symbolPrice(SIMULATION, …)} 返回失败 ⇒ {@code LeverageService.fetchPrice}
     *       捕获后返回 {@code null} ⇒ {@code sweepLiquidations} 每次 {@code continue}
     *       （不会强平）、{@code closeAll} 也 {@code continue}（平不掉）；</li>
     *   <li>该玩家的真实 ess 保证金<b>永久冻结</b>，谁都解不开 —— 只能手改库。</li>
     * </ol>
     *
     * <p>正确边界是：<b>关闭态只挡"开新仓"，不挡"给存量仓位估值"</b>。
     * 开新仓由 {@code supports()}/{@code fetch()}、指令层路由器、{@code tradeGate}
     * 三重挡住；本方法只服务已落库的持仓。
     */
    @Test
    void simulationValuationSurvivesDisabledStateToAvoidLockingMargin() throws IOException {
        String body = JavaSource.methodBody(JavaSource.read(MINE_STOCK),
                "public java.util.concurrent.CompletableFuture<Double> symbolPrice("
                        + "dev.onelili.mstock.model.MarketType market, String symbol)");
        assertNotNull(body, "没能定位 MineStock.symbolPrice(MarketType, String) 的方法体");

        String branch = simulationBranch(body);
        assertNotNull(branch, "没能截出 SIMULATION 分支的代码块");
        assertTrue(branch.contains("service == null"),
                "SIMULATION 分支必须只判 service 就绪（service == null），"
                        + "刻意不判 isEnabled —— 否则存量仓位在关闭态既平不掉也永不强平，"
                        + "玩家真实 ess 保证金永久锁死");
        assertFalse(branch.contains("isEnabled"),
                "SIMULATION 分支不得引用 isEnabled：关闭态掐掉估值会让 "
                        + "LeverageService.fetchPrice 返回 null，sweepLiquidations 与 closeAll "
                        + "双双 continue —— 保证金永久锁死。实际分支代码:\n" + branch.strip());
    }

    /**
     * 截出 {@code symbolPrice} 里 {@code SIMULATION} 那一支的代码块
     * （从 {@code if (market == …SIMULATION)} 到下一个 {@code if (market != …EQUITY)} 之前）。
     *
     * <p>只截这一支而不看整个方法体：后面 EQUITY 分支里出现 {@code isEnabled} 之类的词
     * 都不该让本断言误判。
     */
    private static String simulationBranch(String body) {
        int start = body.indexOf("market == dev.onelili.mstock.model.MarketType.SIMULATION");
        if (start < 0) return null;
        int end = body.indexOf("market != dev.onelili.mstock.model.MarketType.EQUITY", start);
        if (end < 0) return null;
        return body.substring(start, end);
    }

    /**
     * 取出 {@code call} 之后第一层括号内、深度 0 处的<b>第一个实参</b>文本。
     *
     * <p>只取第一个实参而不是整段剩余文本：否则断言会被方法里后面出现的无关代码蒙混过关。
     * 括号与方括号按字符计数配平，因此实参里嵌套的 lambda / 泛型 / 方法调用都能正确跨越。
     *
     * @return 第一个实参（已 strip）；定位失败返回 {@code null}
     */
    private static String firstArgumentOf(String body, String call) {
        int at = body.indexOf(call);
        if (at < 0) return null;

        int depth = 0;
        int start = at + call.length();
        for (int i = start; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                if (depth == 0) return body.substring(start, i);
                depth--;
            } else if (ch == ',' && depth == 0) {
                return body.substring(start, i);
            }
        }
        return null;
    }
}
