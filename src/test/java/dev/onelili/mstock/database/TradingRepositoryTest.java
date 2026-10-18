package dev.onelili.mstock.database;

import dev.onelili.mstock.model.FuturesPosition;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 杠杆 / 期货持久化测试。
 *
 * <p>沿用 {@code HoldingRepositoryTest} 的手法：覆写 {@code DatabaseManager#getConnection}
 * 指向内存库，避免依赖 Bukkit。
 */
class TradingRepositoryTest {

    private TradingRepository repository;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DatabaseManager database = new DatabaseManager(Logger.getAnonymousLogger()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl);
            }
        };
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE player_leverage (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        leverage    INT NOT NULL,
                        updated_at  BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE margin_positions (
                        id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid       VARCHAR(36) NOT NULL,
                        market            VARCHAR(16) NOT NULL,
                        symbol            VARCHAR(24) NOT NULL,
                        direction         VARCHAR(8)  NOT NULL,
                        quantity          DOUBLE NOT NULL,
                        entry_price       DOUBLE NOT NULL,
                        leverage          INT NOT NULL,
                        margin            DOUBLE NOT NULL,
                        liquidation_price DOUBLE NOT NULL,
                        unrealized_pnl    DOUBLE NOT NULL,
                        opened_at         BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE futures_positions (
                        id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid          VARCHAR(36) NOT NULL,
                        symbol               VARCHAR(24) NOT NULL,
                        direction            VARCHAR(8)  NOT NULL,
                        quantity             DOUBLE NOT NULL,
                        entry_price          DOUBLE NOT NULL,
                        contract_size        DOUBLE NOT NULL,
                        initial_margin_rate  DOUBLE NOT NULL,
                        margin               DOUBLE NOT NULL,
                        expiry_date          VARCHAR(10) NOT NULL,
                        last_settle_price    DOUBLE NOT NULL,
                        realized_pnl         DOUBLE NOT NULL,
                        opened_at            BIGINT NOT NULL
                    )
                    """);
        }
        repository = new TradingRepository(database, Logger.getAnonymousLogger());
        playerUuid = UUID.randomUUID();
    }

    @Test
    void savesAndReadsPlayerLeverage() throws SQLException {
        assertNull(repository.findLeverage(playerUuid));

        repository.saveLeverage(playerUuid, 25);
        assertEquals(25, repository.findLeverage(playerUuid));

        // 二次写入走 ON DUPLICATE KEY UPDATE，不产生新行
        repository.saveLeverage(playerUuid, 50);
        assertEquals(50, repository.findLeverage(playerUuid));
        assertEquals(1, repository.findAllLeverages().size());
    }

    @Test
    void keepsMultipleMarginPositionsForTheSameSymbolWithTheirOwnLeverage()
            throws SQLException {
        // 关键回归：现货持仓表的 UNIQUE(player_uuid, stock_code) 会合并同品种仓位，
        // 保证金仓位必须各占一行，才能保留开仓时的杠杆与保证金。
        long first = repository.insertMarginPosition(margin(1.10, 1000, 10, 110.0));
        long second = repository.insertMarginPosition(margin(1.10, 1000, 50, 22.0));

        List<MarginPosition> positions = repository.findMarginPositions(playerUuid);
        assertEquals(2, positions.size());

        MarginPosition low = repository.findMarginPosition(playerUuid, first);
        MarginPosition high = repository.findMarginPosition(playerUuid, second);
        assertEquals(10, low.getLeverage());
        assertEquals(110.0, low.getMargin(), 1e-9);
        assertEquals(50, high.getLeverage());
        assertEquals(22.0, high.getMargin(), 1e-9);
    }

    @Test
    void deletesMarginPositionExactlyOnce() throws SQLException {
        long id = repository.insertMarginPosition(margin(1.10, 1000, 10, 110.0));

        assertTrue(repository.deleteMarginPosition(id));
        // 第二次删除说明已被并发平掉，服务层据此避免重复出金
        assertFalse(repository.deleteMarginPosition(id));
        assertNull(repository.findMarginPosition(playerUuid, id));
    }

    @Test
    void updatesMarginUnrealizedPnl() throws SQLException {
        long id = repository.insertMarginPosition(margin(1.10, 1000, 10, 110.0));

        repository.updateMarginUnrealizedBatch(Map.of(id, -42.5));

        assertEquals(-42.5, repository.findMarginPosition(playerUuid, id)
                .getUnrealizedPnl(), 1e-9);
    }

    @Test
    void roundTripsFuturesPositionIncludingSettlement() throws SQLException {
        FuturesPosition position = new FuturesPosition(-1L, playerUuid, "EURUSD",
                TradeDirection.SHORT, 2.0, 1.10, 100000, 0.1, 22000.0,
                "2026-12-31", 1.10, 0.0, System.currentTimeMillis());

        long id = repository.insertFuturesPosition(position);

        FuturesPosition stored = repository.findFuturesPosition(playerUuid, id);
        assertEquals("EURUSD", stored.getSymbol());
        assertEquals(TradeDirection.SHORT, stored.getDirection());
        assertEquals(2.0, stored.getQuantity(), 1e-9);
        assertEquals(100000.0, stored.getContractSize(), 1e-9);
        assertEquals(0.1, stored.getInitialMarginRate(), 1e-9);
        assertEquals(22000.0, stored.getMargin(), 1e-9);
        assertEquals("2026-12-31", stored.getExpiryDate());
        assertEquals(10, stored.getLeverage());

        repository.updateFuturesSettlement(id, 1.09, 2000.0);
        FuturesPosition settled = repository.findFuturesPosition(playerUuid, id);
        assertEquals(1.09, settled.getLastSettlePrice(), 1e-9);
        assertEquals(2000.0, settled.getRealizedPnl(), 1e-9);

        assertTrue(repository.deleteFuturesPosition(id));
        assertFalse(repository.deleteFuturesPosition(id));
    }

    @Test
    void findsOnlyTheRequestedPlayersPositions() throws SQLException {
        repository.insertMarginPosition(margin(1.10, 1000, 10, 110.0));
        UUID other = UUID.randomUUID();
        repository.insertMarginPosition(new MarginPosition(-1L, other, MarketType.FOREX, "EURUSD",
                TradeDirection.LONG, 1000, 1.10, 10, 110.0, 0.0, 0.0,
                System.currentTimeMillis()));

        // 跨玩家隔离：巡检会读全表，但按玩家查询必须只返回自己的仓位。
        assertEquals(1, repository.findMarginPositions(playerUuid).size());
        assertEquals(1, repository.findMarginPositions(other).size());
        assertEquals(2, repository.findAllOpenMarginPositions().size());
    }

    @Test
    void deletesAllPositionsForOnePlayerOnly() throws SQLException {
        repository.insertMarginPosition(margin(1.10, 1000, 10, 110.0));
        repository.insertFuturesPosition(new FuturesPosition(-1L, playerUuid, "EURUSD",
                TradeDirection.LONG, 1.0, 1.10, 100000, 0.1, 11000.0,
                "2026-12-31", 1.10, 0.0, System.currentTimeMillis()));
        repository.saveLeverage(playerUuid, 20);

        repository.deleteAllMarginPositions(playerUuid);
        repository.deleteAllFuturesPositions(playerUuid);

        assertTrue(repository.findMarginPositions(playerUuid).isEmpty());
        assertTrue(repository.findFuturesPositions(playerUuid).isEmpty());
        // 杠杆设置属于玩家偏好，不随仓位清除而丢失
        assertEquals(20, repository.findLeverage(playerUuid));
    }

    private MarginPosition margin(double entry, double quantity, int leverage, double margin) {
        return new MarginPosition(-1L, playerUuid, MarketType.FOREX, "EURUSD",
                TradeDirection.LONG, quantity, entry, leverage, margin, 0.0, 0.0,
                System.currentTimeMillis());
    }
}
