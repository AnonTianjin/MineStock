package dev.onelili.mstock.simulation;

import dev.onelili.mstock.database.DatabaseManager;
import dev.onelili.mstock.leverage.SymbolPriceSource;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.MarketType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可交易性（停牌 / 禁售）契约测试。
 *
 * <p><b>本类能真测编排层</b>：{@link SimulationService} 的线程模型是
 * "有 {@code plugin} 就走 {@code LifecycleTaskManager}，没有就地执行"，
 * 因此 {@code new SimulationService(null, h2Database, null)} 可在离线 JUnit 里驱动
 * <b>真实的 {@code tick()} / {@code buy} / {@code sell} / {@code isTradable}</b>。
 *
 * <p><b>唯一的替身测试</b>：{@code sweepSkipsSuspendedPositions} ——
 * {@code LeverageService.sweepLiquidations} 需要 {@code plugin.getTaskManager()} /
 * {@code getLogger()} / {@code getRepository()}，离线无法构造，故用<b>与该方法同构的替身循环</b>
 * 验证 {@code tradable == false ⇒ 不调用 onLiquidated} 契约。
 * 对 {@code LeverageService.java:328} 那条真实插桩的验证交给端到端冒烟，
 * <b>本类不声称覆盖了它</b>。
 */
class SimulationTradabilityTest {

    private DatabaseManager database;
    private SimulationService service;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        database = new DatabaseManager(Logger.getAnonymousLogger()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl);
            }
        };
        createTables(database);
        // plugin == null ⇒ tick / 管理入口 / 查询全部就地执行，无需 Bukkit
        service = new SimulationService(null, database, null);
    }

    private static SimulationStock stock(String code, double initPrice, long seed) {
        return new SimulationStock(code, "模拟股 " + code, "科技", initPrice, seed);
    }

    /**
     * 确定性上涨的股票：把随机项全部关掉、只留正漂移 {@code mu}。
     *
     * <p>于是 {@code next = base * exp(mu * dt)} 在 {@code dt > 0} 时<b>严格大于</b> {@code base}
     * —— "价格有没有动"的断言不再依赖随机数运气（{@code sigma} 驱动的随机游走有极小概率
     * 让某一步恰好回到原值，会让测试偶发变红）。
     */
    private static SimulationStock driftingStock(String code, double initPrice, long seed) {
        SimulationStock stock = stock(code, initPrice, seed);
        stock.setMu(0.5);
        stock.setSigma(0.0);
        stock.setTheta(0.0);
        stock.setJumpLambda(0.0);
        stock.setJumpMagnitude(0.0);
        stock.setAmplitude(1.0);
        return stock;
    }

    // ── 判据层（真实 SimulationService）─────────────────────────────────────

    /** 出厂状态可交易。 */
    @Test
    void defaultStockIsTradable() {
        SimulationStock s = stock("SIM001", 10.0, 1L);
        assertTrue(s.isTradable());
        assertFalse(s.isSuspended());

        service.registryView().put(s);
        assertTrue(service.isTradable("SIM001"));
        assertTrue(service.isSimulationCode("SIM001"));
        assertTrue(service.isSimulationCode(" sim001 "), "代码匹配大小写 / 空白容忍");
        assertFalse(service.isSimulationCode("600519"), "真实 A 股代码不命中模拟正则");
        assertFalse(service.isSimulationCode("SIM999"), "正则命中但未注册 ⇒ false");
    }

    /** 停牌 ⇒ 不可交易（即使 {@code tradable == true}）。 */
    @Test
    void suspendedStockIsNotTradable() {
        SimulationStock s = stock("SIM002", 25.5, 2L);
        s.setSuspended(true);
        assertTrue(s.isTradable(), "tradable 开关本身未被改动");

        service.registryView().put(s);
        assertFalse(service.isTradable("SIM002"), "停牌必须否决 tradable");
    }

    /** {@code tradable} 是禁售开关：挡交易，<b>不</b>冻结价格。 */
    @Test
    void tradableFalseBlocksTradingWithoutFreezingPrice() {
        SimulationStock s = driftingStock("SIM003", 88.0, 3L);
        s.setTradable(false);
        service.registryView().put(s);

        assertFalse(service.isTradable("SIM003"));
        assertFalse(SimulationService.shouldSkipPrice(s), "禁售不得冻结价格");
        assertTrue(SimulationService.isActive(s));

        // 真实的 tick：禁售股价格仍被推进
        double before = s.getAutoPrice();
        tickAfterDelay();
        assertNotEquals(before, s.getAutoPrice(), 0.0, "禁售股的价格仍必须推进");
    }

    /** {@code paused} 冻结价格，但<b>仍可交易</b>（暂停推进 ≠ 不可交易）。 */
    @Test
    void pausedFreezesPriceButKeepsTradability() {
        SimulationStock s = stock("SIM004", 5.2, 4L);
        s.setPaused(true);
        service.registryView().put(s);

        assertTrue(service.isTradable("SIM004"), "暂停推进不等于禁止交易");
        assertTrue(SimulationService.shouldSkipPrice(s), "暂停必须跳过价格推进");
        assertFalse(SimulationService.isActive(s), "暂停的股票不参与联动因子抽取");
    }

    /** 未注册代码 → {@code false}，不抛异常（含 {@code null}）。 */
    @Test
    void unknownCodeIsNeverTradable() {
        assertFalse(service.isTradable("SIM999"));
        assertFalse(service.isTradable(null));
        assertFalse(service.isTradable("600519"));
        assertFalse(service.isTradable("   "));

        service.registryView().put(stock("SIM001", 10.0, 1L));
        assertTrue(service.isTradable("sim001"), "查找大小写不敏感");
        assertFalse(service.isTradable("SIM002"));

        // 注册表为 null 时同样返回 false 而非 NPE
        assertFalse(SimulationService.tradable((SimulationStockRegistry) null, "SIM001"));
        assertFalse(SimulationService.tradable((SimulationStock) null));
    }

    // ── 真实 tick 的冻结语义 ────────────────────────────────────────────────

    /**
     * 停牌股在<b>真实 {@code tick()} 的第 ③ 步</b>被整体跳过：{@code currentPrice}、
     * {@code autoPrice}、{@code updatedAt} 三者都按位不变；同一 tick 里可交易股正常推进。
     */
    @Test
    void suspensionFreezesCurrentPriceOnTheNextTick() {
        SimulationStock frozen = driftingStock("SIM001", 50.0, 11L);
        frozen.setSuspended(true);
        SimulationStock moving = driftingStock("SIM002", 50.0, 12L);
        service.registryView().put(frozen);
        service.registryView().put(moving);

        double frozenAuto = frozen.getAutoPrice();
        double frozenCurrent = frozen.getCurrentPrice();
        long frozenUpdatedAt = frozen.getUpdatedAt();
        double movingBefore = moving.getAutoPrice();

        tickAfterDelay();

        // 停牌股：整条价格管线被跳过
        assertEquals(frozenAuto, frozen.getAutoPrice(), 0.0, "停牌期间 autoPrice 必须按位不变");
        assertEquals(frozenCurrent, frozen.getCurrentPrice(), 0.0, "停牌期间 currentPrice 必须按位不变");
        assertEquals(frozenUpdatedAt, frozen.getUpdatedAt(), "停牌期间 updatedAt 不该被刷新");
        // 同一 tick 的可交易股必须正常推进（证明"冻结"来自 skip 分支，而非整个 tick 空转）
        assertNotEquals(movingBefore, moving.getAutoPrice(), 0.0,
                "同一 tick 里可交易股必须正常推进");
    }

    /** 暂停股同样在真实 tick 中被冻结；复牌/恢复后可逆。 */
    @Test
    void pauseFreezesPriceAndIsReversible() {
        SimulationStock paused = driftingStock("SIM001", 50.0, 21L);
        paused.setPaused(true);
        service.registryView().put(paused);

        double before = paused.getCurrentPrice();
        tickAfterDelay();
        assertEquals(before, paused.getCurrentPrice(), 0.0, "暂停期间价格必须冻结");
        assertEquals(before, paused.getAutoPrice(), 0.0);

        // 恢复后价格重新推进（冻结是可逆状态，不是单向门）
        assertTrue(service.resume("SIM001").join().success());
        assertFalse(SimulationService.shouldSkipPrice(paused), "resume 后必须重新参与推进");
        tickAfterDelay();
        assertNotEquals(before, paused.getCurrentPrice(), 0.0, "恢复后价格必须重新推进");
    }

    /** 停牌期间 {@code resume} <b>不</b>复活 {@code suspended}（治安开关 ≠ 价格指令）。 */
    @Test
    void resumeDoesNotReviveSuspensionOrTradability() {
        SimulationStock s = stock("SIM001", 30.0, 31L);
        s.setSuspended(true);
        s.setTradable(false);
        service.registryView().put(s);

        assertTrue(service.resume("SIM001").join().success());

        assertTrue(s.isSuspended(), "resume 不得复活 suspended");
        assertFalse(s.isTradable(), "resume 不得复活 tradable");
        assertFalse(service.isTradable("SIM001"));
    }

    // ── 买卖入口的前置校验顺序（真实 buy/sell）───────────────────────────────

    /**
     * {@code buy}/{@code sell} 的 ①②③ 分支顺序：未知代码 → 未启用 → 停牌 → 数量。
     *
     * <p>{@code plugin == null} 时现金路径必然止步于 {@code vault-not-found}，
     * 因此"校验失败必须发生在取价 / 扣款<b>之前</b>"这条契约可以离线断言
     * （若顺序写错，会拿到 {@code vault-not-found} 而不是预期的拒绝原因）。
     */
    @Test
    void tradeGateRejectsBeforeTouchingPriceOrEconomy() {
        assertEquals("simulation-unknown-code",
                service.buy(UUID.randomUUID(), "SIM999", 1, 1).join().langKey());
        assertEquals("simulation-unknown-code",
                service.sell(UUID.randomUUID(), "SIM999", 1).join().langKey());

        SimulationStock s = stock("SIM001", 20.0, 41L);
        s.setSuspended(true);
        service.registryView().put(s);
        assertEquals("simulation-suspended",
                service.buy(UUID.randomUUID(), "SIM001", 1, 1).join().langKey());
        assertEquals("simulation-suspended",
                service.sell(UUID.randomUUID(), "SIM001", 1).join().langKey());

        // 停牌优先于数量校验（分支顺序不可调换）
        assertEquals("simulation-suspended",
                service.buy(UUID.randomUUID(), "SIM001", 0, 1).join().langKey());

        s.setSuspended(false);
        // 恢复可交易后：数量非法先被拒，且不触达经济系统
        assertEquals("trade-invalid-quantity",
                service.buy(UUID.randomUUID(), "SIM001", 0, 1).join().langKey());
        assertEquals("trade-invalid-quantity",
                service.buy(UUID.randomUUID(), "SIM001", -5, 1).join().langKey());
        assertEquals("trade-invalid-quantity",
                service.sell(UUID.randomUUID(), "SIM001", 0).join().langKey());

        // 参数全合法时才进入资金路径：离线无 Economy ⇒ vault-not-found（而非崩溃）
        assertEquals("vault-not-found",
                service.buy(UUID.randomUUID(), "SIM001", 1, 1).join().langKey());
        // 杠杆路径离线无 LeverageService ⇒ db-error
        assertEquals("db-error",
                service.buy(UUID.randomUUID(), "SIM001", 1, 5).join().langKey());
    }

    /** 禁售（{@code tradable == false}）同样在买卖入口被拦，理由与停牌共用同一语言键。 */
    @Test
    void tradableFalseAlsoRejectsTrades() {
        SimulationStock s = stock("SIM001", 20.0, 51L);
        s.setTradable(false);
        service.registryView().put(s);

        assertEquals("simulation-suspended",
                service.buy(UUID.randomUUID(), "SIM001", 1, 1).join().langKey());
        assertEquals("simulation-suspended",
                service.sell(UUID.randomUUID(), "SIM001", 1).join().langKey());
    }

    // ── SymbolPriceSource 契约层（真实接口，非替身）─────────────────────────

    /**
     * {@code tradable} 是 {@code default} 方法 ⇒ lambda 仍只实现 {@code price}。
     *
     * <p><b>编译期 + 运行期双保险</b>：若把 {@code tradable} 改成抽象方法，本行立刻编译失败
     * （{@code MineStock.priceSource()} 与 {@code ForexService.priceSource()} 的 lambda 同样会挂），
     * 这就是"不破坏 {@code @FunctionalInterface}"的机械保障。
     */
    @Test
    void tradableDefaultMethodKeepsFunctionalInterfaceUsable() {
        SymbolPriceSource source = (market, symbol) -> CompletableFuture.completedFuture(1.0);
        assertTrue(source.tradable(MarketType.FOREX, "EURUSD"));
        assertEquals(1.0, source.price(MarketType.FOREX, "EURUSD").join(), 0.0);
    }

    /** 未覆写时，<b>每个</b>市场都默认可交易（防止未来给 default 加上副作用）。 */
    @Test
    void defaultTradableIsTrueForEveryExistingMarket() {
        SymbolPriceSource source = SymbolPriceSource.unavailable("down");
        for (MarketType market : MarketType.values()) {
            assertTrue(source.tradable(market, "ANY"), market + " 的默认 tradable 必须为 true");
        }
        // 覆写后才可能为 false
        SymbolPriceSource frozen = new SymbolPriceSource() {
            @Override
            public CompletableFuture<Double> price(MarketType market, String symbol) {
                return CompletableFuture.completedFuture(10.0);
            }

            @Override
            public boolean tradable(MarketType market, String symbol) {
                return market != MarketType.SIMULATION;
            }
        };
        assertFalse(frozen.tradable(MarketType.SIMULATION, "SIM001"));
        assertTrue(frozen.tradable(MarketType.EQUITY, "600519"));
    }

    /**
     * <b>替身契约测试（非真实集成）</b>：与 {@code LeverageService.sweepLiquidations}
     * （{@code LeverageService.java:314-366}）同构的循环。
     *
     * <p>契约：<b>{@code tradable == false ⇒ continue，绝不调用 onLiquidated}</b>。
     * 替身循环把真实插桩行（{@code :328}）逐字复刻为
     * {@code if (prices != null && !prices.tradable(...)) continue;}，
     * 断言停牌仓位的强平回调<b>一次都没有</b>被调用，同时可交易仓位仍被正常强平。
     * 真实 {@code LeverageService} 的这条插桩由端到端冒烟覆盖。
     */
    @Test
    void sweepSkipsSuspendedPositions() {
        // 停牌：价格（1.0）已跌破强平线，但 tradable 为 false
        FakePosition suspended = new FakePosition(MarketType.SIMULATION, "SIM001", true, 1.0);
        // 可交易：同样跌破强平线，但 tradable 为 true
        FakePosition tradable = new FakePosition(MarketType.SIMULATION, "SIM002", false, 1.0);
        List<FakePosition> positions = List.of(suspended, tradable);

        SymbolPriceSource prices = new SymbolPriceSource() {
            @Override
            public CompletableFuture<Double> price(MarketType market, String symbol) {
                return CompletableFuture.completedFuture(1.0);
            }

            @Override
            public boolean tradable(MarketType market, String symbol) {
                // 与 SimulationService.isTradable 同义：停牌 ⇒ 不可交易
                for (FakePosition position : positions) {
                    if (position.symbol().equals(symbol)) return !position.suspended();
                }
                return true;
            }
        };

        List<FakePosition> liquidated = new ArrayList<>();
        AtomicInteger evaluated = new AtomicInteger();
        for (FakePosition position : positions) {
            // ← 与 LeverageService.sweepLiquidations:328 的插桩逐字同构，且必须在取价之前
            if (prices != null && !prices.tradable(position.market(), position.symbol())) {
                continue;
            }
            Double price = prices.price(position.market(), position.symbol()).join();
            evaluated.incrementAndGet();
            if (price != null && price <= position.liquidationPrice()) {
                liquidated.add(position);
            }
        }

        assertEquals(List.of(tradable), liquidated, "停牌仓位绝不能被强平；可交易仓位必须仍被强平");
        assertEquals(1, evaluated.get(), "停牌仓位连估值都不该做（插桩必须在取价之前）");

        // 反向验证：去掉守卫（模拟"插桩写错位置 / 漏掉插桩"）就会误强平停牌仓位
        List<FakePosition> withoutGuard = new ArrayList<>();
        for (FakePosition position : positions) {
            Double price = prices.price(position.market(), position.symbol()).join();
            if (price != null && price <= position.liquidationPrice()) withoutGuard.add(position);
        }
        assertTrue(withoutGuard.contains(suspended), "缺少 tradable 守卫时会误强平停牌仓位");
        assertNotEquals(withoutGuard, liquidated);
    }

    // ── SimulationStockSource（真实服务）────────────────────────────────────

    /** 未注册代码必须拒绝（不返回伪造价格）；已注册时价格是 {@code currentPrice} 且不含 ratio。 */
    @Test
    void stockSourceRejectsUnknownAndServesRegistered() {
        SimulationStockSource source = service.stockSource();

        assertFalse(source.supports("SIM001"));
        assertThrows(Exception.class, () -> source.fetch("SIM001").join());
        assertThrows(Exception.class, () -> source.fetchKLine("SIM001", 30).join());
        assertThrows(Exception.class, () -> source.fetch(null).join());
        // 未注册的 kline / rawPrice / marketPrice 同样是失败 future，而不是空列表或 0
        assertThrows(Exception.class, () -> service.kline("SIM001", 30).join());
        assertThrows(Exception.class, () -> service.rawPrice("SIM001").join());
        assertThrows(Exception.class, () -> service.marketPrice("SIM001").join());

        SimulationStock registered = stock("SIM001", 10.0, 61L);
        registered.setCurrentPrice(11.25);
        service.registryView().put(registered);

        assertTrue(source.supports("SIM001"));
        assertTrue(source.supports(" sim001 "), "supports 大小写 / 空白容忍");
        assertFalse(source.supports("600519"));
        assertFalse(source.supports(null));

        var info = source.fetch("SIM001").join();
        assertEquals("SIM001", info.getCode());
        assertEquals("模拟股 SIM001", info.getName());
        assertEquals(11.25, info.getPrice(), 1e-12, "行情源返回的价格不含 price-ratio");
        assertEquals(11.25 - 10.0, info.getChangeAmount(), 1e-12);

        // currentPrice 非法时仍给出 ≥ 0.01 的有限值（兜底必须穿透到行情源）
        registered.setCurrentPrice(Double.NaN);
        double guarded = source.fetch("SIM001").join().getPrice();
        assertTrue(Double.isFinite(guarded) && guarded >= 0.01);
        assertEquals(0.01, service.rawPrice("SIM001").join(), 0.0);

        // 空缓冲开 K 线也必须给出 ≥ MIN_BARS 根，而不是空列表
        List<KLinePoint> points = service.kline("SIM001", 1).join();
        assertTrue(points.size() >= 2, "空缓冲必须由 withFallback 补齐到可渲染根数");
        assertTrue(source.fetchKLine("SIM001", 30).join().size() >= 2);
    }

    /** {@code bootstrapNow()} 真实落库并补齐默认宇宙，随后全部代码可用。 */
    @Test
    void bootstrapNowFillsTheUniverseFromRealDdl() throws SQLException {
        service.bootstrapNow();

        List<String> codes = service.allCodes();
        assertEquals(10, codes.size(), "auto-fill-count 默认 10");
        assertEquals(List.of("SIM001", "SIM002", "SIM003", "SIM004", "SIM005",
                "SIM006", "SIM007", "SIM008", "SIM009", "SIM010"), codes);
        assertEquals(10, service.stocks().size());
        assertTrue(service.isSimulationCode("SIM001"));
        assertTrue(service.isTradable("SIM001"));
        assertTrue(service.stockSource().supports("SIM001"));

        // 真的写进了库（走真实 DDL 的 simulation_stocks）
        assertEquals(10, new SimulationRepository(database, Logger.getAnonymousLogger())
                .findAllStocks().size());

        // 幂等：再 bootstrap 一次不会重复插入
        service.bootstrapNow();
        assertEquals(10, service.allCodes().size());
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────────

    /**
     * 睡 30ms 让 {@code tick()} 的 {@code dt} 足够大（{@code dt = min(60, elapsed/1000)}），
     * 再推进一档。调用方已把股票波动参数写死，因此"价格是否变化"不依赖随机运气。
     */
    private void tickAfterDelay() {
        try {
            Thread.sleep(30L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tick 前的等待被中断", e);
        }
        service.tick();
    }

    /** 强平巡检的最小替身行（只保留插桩判据需要的字段）。 */
    private record FakePosition(MarketType market, String symbol, boolean suspended,
                                double liquidationPrice) {
    }

    /**
     * 反射调用包级的 {@code DatabaseManager#createTables()}，跑<b>真实 DDL</b>
     * （范式与 {@code SimulationRepositoryTest} 一致：不复制 DDL、不改 {@code DatabaseManager}）。
     */
    private static void createTables(DatabaseManager database) throws SQLException {
        try {
            Method method = DatabaseManager.class.getDeclaredMethod("createTables");
            method.setAccessible(true);
            method.invoke(database);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new SQLException("无法调用 DatabaseManager#createTables()", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) throw sql;
            throw new SQLException("createTables() 执行失败", cause);
        }
    }
}
