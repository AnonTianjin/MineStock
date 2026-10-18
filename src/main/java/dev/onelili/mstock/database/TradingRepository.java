package dev.onelili.mstock.database;

import dev.onelili.mstock.model.FuturesPosition;
import dev.onelili.mstock.model.MarginPosition;
import dev.onelili.mstock.model.MarketType;
import dev.onelili.mstock.model.TradeDirection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 杠杆 / 期货数据的持久化层。
 *
 * <p>与 {@link HoldingRepository} 平行存在：现货持仓表有
 * {@code UNIQUE (player_uuid, stock_code)}，会把同一 symbol 的多笔保证金仓位合并，
 * 因此保证金仓位必须独立建表，才能保证"每笔仓位保留开仓时的杠杆与保证金"。
 */
public class TradingRepository {

    private final DatabaseManager db;
    private final Logger logger;

    public TradingRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    // ── 个人杠杆 ──────────────────────────────────────────────────────────────

    public Integer findLeverage(UUID playerUuid) throws SQLException {
        String sql = "SELECT leverage FROM player_leverage WHERE player_uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("leverage") : null;
            }
        }
    }

    public Map<UUID, Integer> findAllLeverages() throws SQLException {
        String sql = "SELECT player_uuid, leverage FROM player_leverage";
        Map<UUID, Integer> result = new LinkedHashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    result.put(UUID.fromString(rs.getString("player_uuid")), rs.getInt("leverage"));
                } catch (IllegalArgumentException ignored) {
                    // 忽略历史脏数据
                }
            }
        }
        return result;
    }

    public void saveLeverage(UUID playerUuid, int leverage) throws SQLException {
        String sql = """
                INSERT INTO player_leverage (player_uuid, leverage, updated_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE leverage = ?, updated_at = ?
                """;
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, leverage);
            ps.setLong(3, now);
            ps.setInt(4, leverage);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    // ── 股票杠杆 ──────────────────────────────────────────────────────────────

    public Integer findStockLeverage(UUID playerUuid) throws SQLException {
        String sql = "SELECT leverage FROM player_stock_leverage WHERE player_uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("leverage") : null;
            }
        }
    }

    public void saveStockLeverage(UUID playerUuid, int leverage) throws SQLException {
        String sql = """
                INSERT INTO player_stock_leverage (player_uuid, leverage, updated_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE leverage = ?, updated_at = ?
                """;
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, leverage);
            ps.setLong(3, now);
            ps.setInt(4, leverage);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    /** 读取该玩家所有股票杠杆设置，用于开服时懒加载之外的批量预热。 */
    public Map<UUID, Integer> findAllStockLeverages() throws SQLException {
        String sql = "SELECT player_uuid, leverage FROM player_stock_leverage";
        Map<UUID, Integer> result = new LinkedHashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    result.put(UUID.fromString(rs.getString("player_uuid")), rs.getInt("leverage"));
                } catch (IllegalArgumentException ignored) {
                    // 忽略历史脏数据
                }
            }
        }
        return result;
    }

    // ── 保证金持仓 ────────────────────────────────────────────────────────────

    public long insertMarginPosition(MarginPosition position) throws SQLException {
        String sql = """
                INSERT INTO margin_positions
                    (player_uuid, market, symbol, direction, quantity, entry_price,
                     leverage, margin, liquidation_price, unrealized_pnl, opened_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long now = position.getOpenedAt() > 0 ? position.getOpenedAt() : System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, position.getPlayerUuid().toString());
            ps.setString(2, position.getMarket().name());
            ps.setString(3, position.getSymbol());
            ps.setString(4, position.getDirection().name());
            ps.setDouble(5, position.getQuantity());
            ps.setDouble(6, position.getEntryPrice());
            ps.setInt(7, position.getLeverage());
            ps.setDouble(8, position.getMargin());
            ps.setDouble(9, position.getLiquidationPrice());
            ps.setDouble(10, position.getUnrealizedPnl());
            ps.setLong(11, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public List<MarginPosition> findMarginPositions(UUID playerUuid) throws SQLException {
        String sql = "SELECT * FROM margin_positions WHERE player_uuid = ? ORDER BY id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<MarginPosition> list = new ArrayList<>();
                while (rs.next()) list.add(mapMargin(rs));
                return list;
            }
        }
    }

    /**
     * 按市场过滤该玩家的保证金持仓。
     *
     * @param market 目标市场；{@code null} 表示不过滤（等价于 {@link #findMarginPositions}）
     */
    public List<MarginPosition> findMarginPositions(UUID playerUuid, MarketType market)
            throws SQLException {
        if (market == null) return findMarginPositions(playerUuid);
        String sql = "SELECT * FROM margin_positions WHERE player_uuid = ? AND market = ? ORDER BY id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, market.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<MarginPosition> list = new ArrayList<>();
                while (rs.next()) list.add(mapMargin(rs));
                return list;
            }
        }
    }

    /**
     * 该玩家在指定品种上的杠杆持仓（按开仓先后排序），用于卖出时按顺序平仓。
     */
    public List<MarginPosition> findMarginPositionsBySymbol(UUID playerUuid, MarketType market,
                                                            String symbol) throws SQLException {
        return findMarginPositionsBySymbol(playerUuid, market, symbol, null);
    }

    /**
     * 该玩家在指定品种、指定方向上的杠杆持仓（按开仓先后排序）。
     *
     * <p>平仓必须带上方向：同一品种可以同时存在多单和空单，只按品种取会把两个方向
     * 一起平掉，玩家看到的「平掉 200 股」就会变成「多空各平了一部分」。
     *
     * @param direction 目标方向；{@code null} 表示不过滤（多空都取）
     */
    public List<MarginPosition> findMarginPositionsBySymbol(UUID playerUuid, MarketType market,
                                                            String symbol, TradeDirection direction)
            throws SQLException {
        String sql = """
                SELECT * FROM margin_positions
                WHERE player_uuid = ? AND market = ? AND symbol = ?
                ORDER BY id
                """;
        if (direction == null) {
            return queryMarginPositions(sql, playerUuid, market, symbol);
        }
        // COALESCE 与 parseDirection 的回退口径保持一致：空方向按多头算。
        sql = """
                SELECT * FROM margin_positions
                WHERE player_uuid = ? AND market = ? AND symbol = ?
                  AND COALESCE(direction, 'LONG') = ?
                ORDER BY id
                """;
        return queryMarginPositions(sql, playerUuid, market, symbol, direction.name());
    }

    private List<MarginPosition> queryMarginPositions(String sql, UUID playerUuid, MarketType market,
                                                      String symbol, String... extra)
            throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, market.name());
            ps.setString(3, symbol);
            for (int i = 0; i < extra.length; i++) {
                ps.setString(4 + i, extra[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<MarginPosition> list = new ArrayList<>();
                while (rs.next()) list.add(mapMargin(rs));
                return list;
            }
        }
    }

    /** 该玩家在指定品种上的杠杆持仓总数量。 */
    public double sumMarginQuantity(UUID playerUuid, MarketType market, String symbol)
            throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(quantity), 0) AS total
                FROM margin_positions
                WHERE player_uuid = ? AND market = ? AND symbol = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, market.name());
            ps.setString(3, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble("total") : 0.0;
            }
        }
    }

    public MarginPosition findMarginPosition(UUID playerUuid, long id) throws SQLException {
        String sql = "SELECT * FROM margin_positions WHERE player_uuid = ? AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapMargin(rs) : null;
            }
        }
    }

    public List<MarginPosition> findAllOpenMarginPositions() throws SQLException {
        String sql = "SELECT * FROM margin_positions";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<MarginPosition> list = new ArrayList<>();
            while (rs.next()) list.add(mapMargin(rs));
            return list;
        }
    }

    /**
     * 批量更新未实现盈亏（强平巡检收尾用）。
     *
     * <p>单连接单事务一次提交：巡检每轮对所有仓位都要回写估值，
     * 逐行借还连接 + 逐行提交会与交易请求争抢只有 2 个连接的池
     * （1000 个仓位 = 1000 次提交）。已消失的 id（本轮被强平）更新为空操作。
     */
    public void updateMarginUnrealizedBatch(Map<Long, Double> pnlById) throws SQLException {
        if (pnlById.isEmpty()) return;
        String sql = "UPDATE margin_positions SET unrealized_pnl = ? WHERE id = ?";
        try (Connection conn = db.getConnection()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Map.Entry<Long, Double> entry : pnlById.entrySet()) {
                    ps.setDouble(1, entry.getValue());
                    ps.setLong(2, entry.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
        }
    }

    /**
     * 补交保证金后的仓位更新：保证金增加，杠杆与强平价相应下调。
     *
     * <p>补交保证金等价于给浮亏仓位去杠杆：名义价值不变、保证金变多，
     * 有效杠杆 = 名义价值 / 新保证金（向下取整，最低 1），强平价随之重算。
     *
     * @return {@code false} 表示该行不存在（已被并发平掉）
     */
    public boolean topUpMargin(long id, double margin, int leverage,
                               double liquidationPrice) throws SQLException {
        String sql = """
                UPDATE margin_positions
                SET margin = ?, leverage = ?, liquidation_price = ?
                WHERE id = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, margin);
            ps.setInt(2, leverage);
            ps.setDouble(3, liquidationPrice);
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 部分平仓后的仓位收缩：按<b>本次平仓量</b>原子扣减数量与保证金。
     *
     * <p>强平价与杠杆<b>不变</b> —— 它们由开仓时的入场价与杠杆决定，
     * 减仓不改变这两个量。
     *
     * <p>写法是 {@code quantity = quantity - ?} 加 {@code AND quantity >= ?} 闸门，
     * 而不是把调用方算好的绝对值写回：两个并发部分平仓各带走自己那份，
     * 谁也覆盖不了谁（全平的 {@link #deleteMarginPosition} 幂等闸门的对应实现）。
     *
     * @param closeQuantity  本次平掉的数量（必须 &gt; 0）
     * @param releasedMargin 本次释放的保证金
     * @param unrealizedPnl  写回的未实现盈亏快照（巡检会定期重算，仅展示用）
     * @return {@code false} 表示该行不存在、已被并发整平，或剩余数量不足以扣减
     *         本次数量（同样是被并发平掉的表现）。调用方必须跳过该仓位且<b>不入账</b>。
     */
    public boolean reduceMarginPosition(long id, double closeQuantity, double releasedMargin,
                                        double unrealizedPnl) throws SQLException {
        String sql = """
                UPDATE margin_positions
                SET quantity = quantity - ?, margin = margin - ?, unrealized_pnl = ?
                WHERE id = ? AND quantity >= ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, closeQuantity);
            ps.setDouble(2, releasedMargin);
            ps.setDouble(3, unrealizedPnl);
            ps.setLong(4, id);
            ps.setDouble(5, closeQuantity);
            return ps.executeUpdate() > 0;
        }
    }

    /** 删除仓位；返回 {@code false} 表示该行不存在（已被并发平掉）。 */
    public boolean deleteMarginPosition(long id) throws SQLException {
        String sql = "DELETE FROM margin_positions WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAllMarginPositions(UUID playerUuid) throws SQLException {
        String sql = "DELETE FROM margin_positions WHERE player_uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    // ── 期货持仓 ──────────────────────────────────────────────────────────────

    public long insertFuturesPosition(FuturesPosition position) throws SQLException {
        String sql = """
                INSERT INTO futures_positions
                    (player_uuid, symbol, direction, quantity, entry_price, contract_size,
                     initial_margin_rate, margin, expiry_date, last_settle_price,
                     realized_pnl, opened_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long now = position.getOpenedAt() > 0 ? position.getOpenedAt() : System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, position.getPlayerUuid().toString());
            ps.setString(2, position.getSymbol());
            ps.setString(3, position.getDirection().name());
            ps.setDouble(4, position.getQuantity());
            ps.setDouble(5, position.getEntryPrice());
            ps.setDouble(6, position.getContractSize());
            ps.setDouble(7, position.getInitialMarginRate());
            ps.setDouble(8, position.getMargin());
            ps.setString(9, position.getExpiryDate());
            ps.setDouble(10, position.getLastSettlePrice());
            ps.setDouble(11, position.getRealizedPnl());
            ps.setLong(12, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public List<FuturesPosition> findFuturesPositions(UUID playerUuid) throws SQLException {
        String sql = "SELECT * FROM futures_positions WHERE player_uuid = ? ORDER BY id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<FuturesPosition> list = new ArrayList<>();
                while (rs.next()) list.add(mapFutures(rs));
                return list;
            }
        }
    }

    public FuturesPosition findFuturesPosition(UUID playerUuid, long id) throws SQLException {
        String sql = "SELECT * FROM futures_positions WHERE player_uuid = ? AND id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapFutures(rs) : null;
            }
        }
    }

    public List<FuturesPosition> findAllOpenFuturesPositions() throws SQLException {
        String sql = "SELECT * FROM futures_positions";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<FuturesPosition> list = new ArrayList<>();
            while (rs.next()) list.add(mapFutures(rs));
            return list;
        }
    }

    public void updateFuturesSettlement(long id, double lastSettlePrice, double realizedPnl)
            throws SQLException {
        String sql = "UPDATE futures_positions SET last_settle_price = ?, realized_pnl = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, lastSettlePrice);
            ps.setDouble(2, realizedPnl);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public boolean deleteFuturesPosition(long id) throws SQLException {
        String sql = "DELETE FROM futures_positions WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAllFuturesPositions(UUID playerUuid) throws SQLException {
        String sql = "DELETE FROM futures_positions WHERE player_uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    // ── 行映射 ────────────────────────────────────────────────────────────────

    private MarginPosition mapMargin(ResultSet rs) throws SQLException {
        return new MarginPosition(
                rs.getLong("id"),
                parseUuid(rs.getString("player_uuid")),
                MarketType.parse(rs.getString("market")),
                rs.getString("symbol"),
                parseDirection(rs.getString("direction")),
                rs.getDouble("quantity"),
                rs.getDouble("entry_price"),
                rs.getInt("leverage"),
                rs.getDouble("margin"),
                rs.getDouble("liquidation_price"),
                rs.getDouble("unrealized_pnl"),
                rs.getLong("opened_at")
        );
    }

    private FuturesPosition mapFutures(ResultSet rs) throws SQLException {
        return new FuturesPosition(
                rs.getLong("id"),
                parseUuid(rs.getString("player_uuid")),
                rs.getString("symbol"),
                parseDirection(rs.getString("direction")),
                rs.getDouble("quantity"),
                rs.getDouble("entry_price"),
                rs.getDouble("contract_size"),
                rs.getDouble("initial_margin_rate"),
                rs.getDouble("margin"),
                rs.getString("expiry_date"),
                rs.getDouble("last_settle_price"),
                rs.getDouble("realized_pnl"),
                rs.getLong("opened_at")
        );
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("非法的玩家 UUID: " + raw, e);
        }
    }

    /** 方向字段损坏时回退为多头，避免整个持仓列表读取失败。 */
    private TradeDirection parseDirection(String raw) {
        TradeDirection direction = TradeDirection.parse(raw);
        if (direction == null) {
            logger.warning("[MineStock] 未知的仓位方向，按多头处理: " + raw);
            return TradeDirection.LONG;
        }
        return direction;
    }
}
