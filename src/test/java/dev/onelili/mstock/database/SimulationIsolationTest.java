package dev.onelili.mstock.database;

import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import dev.onelili.mstock.simulation.SimulationHolding;
import dev.onelili.mstock.simulation.SimulationRepository;
import dev.onelili.mstock.simulation.SimulationStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟盘 ↔ 真实盘的<b>数据隔离与建表契约</b>。
 *
 * <p><b>为什么必须单独测</b>：{@code SimulationStockSource} 被插在
 * {@code StockApiService.sources} 的 <b>index 0</b>，因此 {@code api.isSupported("SIM001")}
 * 返回 {@code true}。任何未拦截的入口都会把模拟代码当成真实股票 —— 写真实 {@code holdings}
 * 表、扣 Vault 余额，或以 {@code market=EQUITY} 落真实 {@code margin_positions}。
 *
 * <p><b>本测试的关键手法</b>：直接调用生产用的包级 {@link DatabaseManager#createTables()}
 * 建表，而不是在测试里另抄一份 DDL。这样：
 * <ul>
 *   <li>5 张 {@code simulation_*} 新表的 DDL 被<b>真实执行</b>（含 5 个 {@code BOOLEAN} 列
 *       —— R5 的核心风险，建表失败会导致插件 {@code onEnable} 直接 disable）；</li>
 *   <li>测试与生产不会漂移："测试建的表对、生产的表错"这类问题在这里就会暴露。</li>
 * </ul>
 *
 * <p><b>本测试的覆盖边界（勿误读）</b>：这里验证的是<b>数据层</b>隔离 ——
 * 即使模拟单被错误地当成真实股票处理，两张表也不会互相污染。
 * 它<b>不</b>验证"拦截本身存在"：{@code MStockCommand.executeBuy} 第一行的守卫、
 * {@code StockLeverageService.buy} 的方法级不变量、Vault 扣款路径都不在本类范围内。
 *
 * <p>拦截层的接线回归由 {@code command/SimulationRoutingTest} 负责（它直接对源码断言
 * "守卫必须是方法体第一条语句"）。
 * 两者加起来才构成完整的隔离守护。
 */
class SimulationIsolationTest {

    private static final String CODE = "SIM001";

    private DatabaseManager database;
    private HoldingRepository holdings;
    private TradingRepository trading;
    private SimulationRepository simulation;
    private UUID player;

    @BeforeEach
    void setUp() throws SQLException {
        // 与 DatabaseManager.init 同样显式加载：本类可被单独执行（-Dtest=SimulationIsolationTest），
        // 那时其他测试类尚未触发过 H2 驱动的注册。
        org.h2.Driver.load();
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        database = new DatabaseManager(Logger.getAnonymousLogger()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl);
            }
        };
        // 走生产的建表逻辑：任何一张表建不出来（含 simulation_* 的 BOOLEAN 列）这里就会炸
        database.createTables();
        player = UUID.randomUUID();
        holdings = new HoldingRepository(database, Logger.getAnonymousLogger());
        trading = new TradingRepository(database, Logger.getAnonymousLogger());
        simulation = new SimulationRepository(database, Logger.getAnonymousLogger());
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
        // DatabaseManager.close() 会按插件类加载器注销 org.h2.Driver（生产环境要防止
        // 插件卸载后驱动泄漏）。测试里随之失去全局注册，会让**后续所有**用
        // DriverManager.getConnection("jdbc:h2:...") 的测试类报 "No suitable driver found"。
        // 沿用 DatabaseManagerLifecycleTest 的既有手法：重新 load 回去。
        org.h2.Driver.load();
    }

    // ------------------------------------------------------------ 建表契约（R5）

    /** 5 张 {@code simulation_*} 表必须都能建成并可用（覆盖 R5 的 BOOLEAN 风险）。 */
    @Test
    void productionDdlCreatesEverySimulationTable() throws SQLException {
        for (String table : List.of("simulation_stocks", "simulation_holdings",
                "simulation_links", "simulation_schedules", "simulation_klines")) {
            try (Connection connection = database.getConnection();
                 var ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                 var rs = ps.executeQuery()) {
                assertTrue(rs.next(), table + " 应可查询");
                assertEquals(0, rs.getInt(1), table + " 初始应为空表");
            }
        }
    }

    /** 既有 5 张表必须依然存在（追加 DDL 不能改坏老表）。 */
    @Test
    void productionDdlKeepsEveryPreexistingTable() throws SQLException {
        for (String table : List.of("holdings", "player_leverage", "player_stock_leverage",
                "margin_positions", "futures_positions")) {
            try (Connection connection = database.getConnection();
                 var ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                 var rs = ps.executeQuery()) {
                assertTrue(rs.next(), table + " 应仍然存在");
            }
        }
    }

    /**
     * {@code simulation_stocks} 的 BOOLEAN 列必须能写能读（R5 实测的固化）。
     *
     * <p>{@code suspended} / {@code tradable} / {@code paused} 三个布尔值的往返是本测试重点：
     * 仓库既有 5 张表<b>无任何 BOOLEAN 列先例</b>，若 H2 的 {@code MODE=MySQL} 不支持，
     * 建表会失败并让整个插件 {@code onEnable} 直接 disable。
     */
    @Test
    void simulationStocksBooleanColumnsRoundTrip() throws SQLException {
        SimulationStock stock = new SimulationStock(CODE, "测试股", "科技", 100.0, 12345L);
        stock.setSuspended(true);
        stock.setTradable(false);
        stock.setPaused(true);
        simulation.upsertStock(stock);

        List<SimulationStock> loaded = simulation.findAllStocks();
        assertEquals(1, loaded.size(), "应写入并读回一只模拟股");
        SimulationStock round = loaded.get(0);
        assertEquals(CODE, round.getCode());
        assertEquals("测试股", round.getName());
        assertEquals("科技", round.getSector());
        assertEquals(100.0, round.getCurrentPrice(), 1e-9);
        assertTrue(round.isSuspended(), "suspended 必须往返为 true");
        assertFalse(round.isTradable(), "tradable 必须往返为 false");
        assertTrue(round.isPaused(), "paused 必须往返为 true");
    }

    // ---------------------------------------------------------------- 表隔离

    /** 模拟买入只动 {@code simulation_holdings}，真实 {@code holdings} 必须一行不长。 */
    @Test
    void simulationBuyNeverTouchesRealHoldingsTable() throws SQLException {
        simulation.upsertHoldingBuy(player, CODE, 10, 100.0);

        SimulationHolding bought = simulation.findHolding(player, CODE);
        assertNotNull(bought, "模拟持仓应写入 simulation_holdings");
        assertEquals(10, bought.getAmount());
        assertEquals(100.0, bought.getAvgCost(), 1e-9);

        assertNull(holdings.findByPlayerAndCode(player, CODE),
                "模拟代码绝不能出现在真实 holdings 表 —— 这是 R1 的核心不变量");
        assertTrue(holdings.findAllByPlayer(player).isEmpty(), "真实持仓列表必须完全为空");
    }

    /** 反向：真实持仓不会污染模拟盘的读取。 */
    @Test
    void realHoldingNeverAppearsInSimulationPortfolio() throws SQLException {
        holdings.upsertBuy(player, "600519", 5, 1700.0);

        assertNotNull(holdings.findByPlayerAndCode(player, "600519"));
        assertNull(simulation.findHolding(player, "600519"), "真实股票代码不能出现在模拟持仓里");
        assertTrue(simulation.findHoldings(player).isEmpty());
    }

    /** 同一玩家可同时持有真实与模拟的<b>同代码</b>标的，两张表互不覆盖。 */
    @Test
    void realAndSimulationHoldingsCoexistIndependently() throws SQLException {
        holdings.upsertBuy(player, CODE, 3, 50.0);
        simulation.upsertHoldingBuy(player, CODE, 7, 100.0);

        assertEquals(3, holdings.findByPlayerAndCode(player, CODE).getAmount(),
                "真实持仓数量不受模拟盘影响");
        assertEquals(7, simulation.findHolding(player, CODE).getAmount(),
                "模拟持仓数量不受真实盘影响");
    }

    /** 模拟卖出只减 {@code simulation_holdings}，真实持仓原封不动。 */
    @Test
    void simulationSellOnlyReducesSimulationHoldings() throws SQLException {
        holdings.upsertBuy(player, CODE, 4, 50.0);
        simulation.upsertHoldingBuy(player, CODE, 10, 100.0);

        assertTrue(simulation.reduceHoldingSell(player, CODE, 6, 110.0));

        assertEquals(4, simulation.findHolding(player, CODE).getAmount());
        assertEquals(4, holdings.findByPlayerAndCode(player, CODE).getAmount(),
                "真实持仓数量必须逐字不变");
    }

    // ------------------------------------------------------- MarketType 往返

    /**
     * {@code MarketType.SIMULATION} 必须能无损往返数据库。
     *
     * <p>{@code TradingRepository.mapMargin} 用 {@code MarketType.parse(...)} 反序列化，
     * 而 {@code parse} 对<b>未知值静默回落 FOREX</b>。缺枚举值 ⇒ 模拟仓位被当成外汇仓位，
     * 拿去问 biquote 报价、触发错误的强平。
     */
    @Test
    void simulationMarketTypeSurvivesDatabaseRoundTrip() throws SQLException {
        long id = trading.insertMarginPosition(new MarginPosition(
                0L, player, MarketType.SIMULATION, CODE, TradeDirection.LONG,
                10.0, 100.0, 5, 200.0, 80.0, 0.0, System.currentTimeMillis()));

        assertTrue(id > 0, "应返回自增主键");

        List<MarginPosition> positions = trading.findMarginPositions(player, MarketType.SIMULATION);
        assertEquals(1, positions.size(), "按 SIMULATION 过滤应查到该仓位");
        MarginPosition loaded = positions.get(0);
        assertEquals(MarketType.SIMULATION, loaded.getMarket(),
                "market 必须往返为 SIMULATION，不能被 parse 静默改成 FOREX");
        assertNotEquals(MarketType.FOREX, loaded.getMarket());
        assertEquals(CODE, loaded.getSymbol());
        assertEquals(5, loaded.getLeverage());
        assertEquals(200.0, loaded.getMargin(), 1e-9);
        assertEquals(TradeDirection.LONG, loaded.getDirection());
    }

    /** 按市场过滤必须<b>分得开</b>：SIMULATION 与 EQUITY 仓位互不混淆。 */
    @Test
    void simulationAndEquityPositionsFilterSeparately() throws SQLException {
        trading.insertMarginPosition(new MarginPosition(
                0L, player, MarketType.SIMULATION, CODE, TradeDirection.LONG,
                10.0, 100.0, 5, 200.0, 80.0, 0.0, System.currentTimeMillis()));
        trading.insertMarginPosition(new MarginPosition(
                0L, player, MarketType.EQUITY, "AAPL", TradeDirection.LONG,
                2.0, 200.0, 3, 133.0, 150.0, 0.0, System.currentTimeMillis()));

        assertEquals(1, trading.findMarginPositions(player, MarketType.SIMULATION).size());
        assertEquals(1, trading.findMarginPositions(player, MarketType.EQUITY).size());
        assertEquals(CODE,
                trading.findMarginPositions(player, MarketType.SIMULATION).get(0).getSymbol());
        assertEquals("AAPL",
                trading.findMarginPositions(player, MarketType.EQUITY).get(0).getSymbol());
        assertEquals(2, trading.findAllOpenMarginPositions().size(),
                "全部开放仓位应包含两者，但各自按市场可分辨");
    }

    /** 强平巡检取全部开放仓位时，模拟仓位也必须在内（否则永不结算）。 */
    @Test
    void simulationPositionsAreVisibleToLiquidationSweep() throws SQLException {
        trading.insertMarginPosition(new MarginPosition(
                0L, player, MarketType.SIMULATION, CODE, TradeDirection.LONG,
                10.0, 100.0, 5, 200.0, 80.0, 0.0, System.currentTimeMillis()));

        List<MarginPosition> all = trading.findAllOpenMarginPositions();
        assertEquals(1, all.size(), "强平巡检必须能看见模拟杠杆仓位");
        assertEquals(MarketType.SIMULATION, all.get(0).getMarket());
    }

    /** 模拟仓位可以被正常删除（结算路径依赖它做幂等锁）。 */
    @Test
    void simulationPositionCanBeDeletedForSettlement() throws SQLException {
        long id = trading.insertMarginPosition(new MarginPosition(
                0L, player, MarketType.SIMULATION, CODE, TradeDirection.LONG,
                10.0, 100.0, 5, 200.0, 80.0, 0.0, System.currentTimeMillis()));

        assertTrue(trading.deleteMarginPosition(id), "首次删除应成功");
        assertFalse(trading.deleteMarginPosition(id), "重复删除必须返回 false（并发结算的幂等锁）");
        assertTrue(trading.findMarginPositions(player, MarketType.SIMULATION).isEmpty());
    }

    /** 模拟盘不进推荐/告示牌：{@code parse} 的回落分支仍只命中未知值，不命中 SIMULATION。 */
    @Test
    void unknownMarketStillFallsBackToForexButSimulationDoesNot() {
        assertEquals(MarketType.FOREX, MarketType.parse("NOT_A_MARKET"),
                "未知值的回落行为是既有的，本测试固化它以免被顺手改掉");
        assertEquals(MarketType.SIMULATION, MarketType.parse("SIMULATION"),
                "SIMULATION 是已知值，绝不能走回落分支");
    }
}
