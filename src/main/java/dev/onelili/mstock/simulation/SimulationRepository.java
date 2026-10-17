package dev.onelili.mstock.simulation;

import dev.onelili.mstock.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 模拟盘的 H2 持久化。
 *
 * <p>实现约束（与 {@code TradingRepository} 同范式）：全部方法 {@code throws SQLException}；
 * {@code try (Connection conn = db.getConnection(); PreparedStatement ps = ...)}；
 * 多行 SQL 用 text block；{@code ON DUPLICATE KEY UPDATE} 做 upsert；
 * {@code boolean} 返回值表示"影响行数 &gt; 0"；不吞异常。
 *
 * <p><b>注意</b>：H2 的 {@code ON DUPLICATE KEY UPDATE} 在 update 分支上
 * {@code executeUpdate()} 返回 <b>2</b>（插入分支返回 1），判定成功一律用 {@code > 0}。
 *
 * <p><b>布尔列</b>：5 个布尔列在 H2 2.2.224 + {@code MODE=MySQL} 下落地类型就是真 BOOLEAN
 * （实测证据见 {@code SIMULATION_R5_EVIDENCE.md}），因此统一走
 * {@link java.sql.ResultSet#getBoolean(String)} / {@link PreparedStatement#setBoolean(int, boolean)}，
 * 不做 {@code != 0} 之类的自研解析。
 */
public class SimulationRepository {

    /** 价格类字段兜底值：非有限（NaN / ±Inf）或非正值一律落 {@code 0.01}。 */
    private static final double SAFE_PRICE = 0.01;

    /** 涨跌停百分数下界，保证带宽严格为正（与 {@link SimulationStock} 的同类常量同值）。 */
    private static final double MIN_LIMIT_PERCENT = 0.0001;

    /** 涨跌停百分数上界（百分数口径 ⇒ {@code 10.0} = ±10%）。 */
    private static final double MAX_LIMIT_PERCENT = 10.0;

    /**
     * 股票 upsert 语句。
     *
     * <p>共 56 个占位符：前 29 个是 INSERT 的列值，后 27 个是 {@code ON DUPLICATE KEY UPDATE}
     * 的目标值。除主键 {@code code} 与只写一次的 {@code created_at} 外，每个可变列都<b>显式点名</b>
     * —— H2 在 update 分支只改子句里点名的列。
     */
    private static final String STOCK_UPSERT_SQL = """
            INSERT INTO simulation_stocks
                (code, name, sector, init_price, prev_close, limit_percent, current_price,
                 auto_price, suspended, tradable, paused, amplitude, preset, boundary,
                 mu, sigma, theta, jump_lambda, jump_magnitude, seed,
                 min_price, max_price, anchor, manual_price, manual_target_pct,
                 slope_per_ms, slope_until, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name = ?, sector = ?, init_price = ?, prev_close = ?, limit_percent = ?,
                current_price = ?, auto_price = ?, suspended = ?, tradable = ?, paused = ?,
                amplitude = ?, preset = ?, boundary = ?, mu = ?, sigma = ?, theta = ?,
                jump_lambda = ?, jump_magnitude = ?, seed = ?, min_price = ?, max_price = ?,
                anchor = ?, manual_price = ?, manual_target_pct = ?, slope_per_ms = ?,
                slope_until = ?, updated_at = ?
            """;

    private static final String KLINE_UPSERT_SQL = """
            INSERT INTO simulation_klines (code, bucket_start, open, high, low, close)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE open = ?, high = ?, low = ?, close = ?
            """;

    private final DatabaseManager db;
    private final Logger logger;

    public SimulationRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    // ── 股票池 ──────────────────────────────────────────────────────────────

    /** 全部模拟股，{@code ORDER BY code}。 */
    public List<SimulationStock> findAllStocks() throws SQLException {
        String sql = "SELECT * FROM simulation_stocks ORDER BY code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<SimulationStock> stocks = new ArrayList<>();
            while (rs.next()) {
                stocks.add(mapStock(rs));
            }
            return stocks;
        }
    }

    /** 新增或覆盖一只股票（{@code INSERT ... ON DUPLICATE KEY UPDATE}）。 */
    public void upsertStock(SimulationStock stock) throws SQLException {
        if (stock == null) return;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(STOCK_UPSERT_SQL)) {
            bindStock(ps, stock, System.currentTimeMillis());
            // update 分支返回 2、insert 分支返回 1 —— 这里不关心返回值，绝不写 == 1
            ps.executeUpdate();
        }
    }

    /** 单连接批量 upsert，供 {@code stop()} 落库。 */
    public void upsertStocks(List<SimulationStock> stocks) throws SQLException {
        if (stocks == null || stocks.isEmpty()) return;
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(STOCK_UPSERT_SQL)) {
            for (SimulationStock stock : stocks) {
                if (stock == null) continue;
                bindStock(ps, stock, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 删除一只股票；返回是否真的删除了。 */
    public boolean deleteStock(String code) throws SQLException {
        String sql = "DELETE FROM simulation_stocks WHERE code = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            return ps.executeUpdate() > 0;
        }
    }

    // ── 模拟现金持仓 ────────────────────────────────────────────────────────

    public SimulationHolding findHolding(UUID playerUuid, String code) throws SQLException {
        String sql = "SELECT * FROM simulation_holdings WHERE player_uuid = ? AND code = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapHolding(rs) : null;
            }
        }
    }

    /** 该玩家的全部模拟现金持仓，{@code ORDER BY code}。 */
    public List<SimulationHolding> findHoldings(UUID playerUuid) throws SQLException {
        String sql = "SELECT * FROM simulation_holdings WHERE player_uuid = ? ORDER BY code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<SimulationHolding> holdings = new ArrayList<>();
                while (rs.next()) {
                    holdings.add(mapHolding(rs));
                }
                return holdings;
            }
        }
    }

    /** 与 {@code HoldingRepository.upsertBuy} 同构的加权平均成本 upsert。 */
    public void upsertHoldingBuy(UUID playerUuid, String code, int addAmount, double buyPrice)
            throws SQLException {
        String sql = """
                INSERT INTO simulation_holdings
                    (player_uuid, code, amount, avg_cost, last_price, last_fetched)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    avg_cost = (avg_cost * amount + ? * ?) / (amount + ?),
                    amount = amount + ?,
                    last_price = ?,
                    last_fetched = ?
                """;
        long now = System.currentTimeMillis();
        double price = price(buyPrice);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, code);
            ps.setInt(3, addAmount);
            ps.setDouble(4, price);
            ps.setDouble(5, price);
            ps.setLong(6, now);
            // UPDATE 参数
            ps.setDouble(7, price);
            ps.setInt(8, addAmount);
            ps.setInt(9, addAmount);
            ps.setInt(10, addAmount);
            ps.setDouble(11, price);
            ps.setLong(12, now);
            ps.executeUpdate();
        }
    }

    /**
     * 卖出扣减：单条 {@code UPDATE ... WHERE amount >= ?}；{@code > 0} 表示成功；
     * {@code amount} 归零时在同一事务里 {@code DELETE} 该行。
     *
     * <p>持仓不足用<b>返回值</b>表达（{@code false}），不抛异常 ——
     * 且与 {@code HoldingRepository.reduceSell} 的"抛异常"语义刻意区分开。
     */
    public boolean reduceHoldingSell(UUID playerUuid, String code, int sellAmount, double sellPrice)
            throws SQLException {
        String updateSql = """
                UPDATE simulation_holdings
                SET amount = amount - ?, last_price = ?, last_fetched = ?
                WHERE player_uuid = ? AND code = ? AND amount >= ?
                """;
        String deleteSql =
                "DELETE FROM simulation_holdings WHERE player_uuid = ? AND code = ? AND amount = 0";
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement update = conn.prepareStatement(updateSql);
                 PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                update.setInt(1, sellAmount);
                update.setDouble(2, price(sellPrice));
                update.setLong(3, now);
                update.setString(4, playerUuid.toString());
                update.setString(5, code);
                update.setInt(6, sellAmount);
                if (update.executeUpdate() == 0) {
                    // 持仓不足：回滚并返回 false，持仓保持原值
                    conn.rollback();
                    return false;
                }

                delete.setString(1, playerUuid.toString());
                delete.setString(2, code);
                delete.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException e) {
                if (!conn.getAutoCommit()) conn.rollback();
                throw e;
            }
        }
    }

    public void deleteHolding(UUID playerUuid, String code) throws SQLException {
        String sql = "DELETE FROM simulation_holdings WHERE player_uuid = ? AND code = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    // ── 联动 ────────────────────────────────────────────────────────────────

    public List<SimulationLink> findLinks() throws SQLException {
        String sql = "SELECT code_a, code_b, correlation FROM simulation_links ORDER BY code_a, code_b";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<SimulationLink> links = new ArrayList<>();
            while (rs.next()) {
                // 读回同样过 SimulationLink.of：幂等地归一化顺序并按 [-0.999, 0.999] 夹取
                links.add(SimulationLink.of(rs.getString("code_a"), rs.getString("code_b"),
                        rs.getDouble("correlation")));
            }
            return links;
        }
    }

    /**
     * upsert 一条联动。
     *
     * <p>顺序归一化（{@code codeA < codeB}）与相关系数夹取都由 {@link SimulationLink#of} 完成，
     * 保证 {@code (A,B)} 与 {@code (B,A)} 落成同一行；自环（{@code A == B}）与空代码直接忽略。
     */
    public void upsertLink(String codeA, String codeB, double correlation) throws SQLException {
        SimulationLink link = SimulationLink.of(codeA, codeB, correlation);
        if (link.codeA() == null || link.codeB() == null) return;
        if (link.codeA().equals(link.codeB())) return;
        String sql = """
                INSERT INTO simulation_links (code_a, code_b, correlation)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE correlation = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, link.codeA());
            ps.setString(2, link.codeB());
            ps.setDouble(3, link.correlation());
            ps.setDouble(4, link.correlation());
            ps.executeUpdate();
        }
    }

    public boolean deleteLink(String codeA, String codeB) throws SQLException {
        SimulationLink link = SimulationLink.of(codeA, codeB, 0.0);
        if (link.codeA() == null || link.codeB() == null) return false;
        String sql = "DELETE FROM simulation_links WHERE code_a = ? AND code_b = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, link.codeA());
            ps.setString(2, link.codeB());
            return ps.executeUpdate() > 0;
        }
    }

    /** 删除某只股票参与的全部联动。 */
    public void deleteLinksFor(String code) throws SQLException {
        String sql = "DELETE FROM simulation_links WHERE code_a = ? OR code_b = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    // ── 定时 ────────────────────────────────────────────────────────────────

    /** 全部定时操作，{@code ORDER BY id}。 */
    public List<SimulationSchedule> findSchedules() throws SQLException {
        String sql = "SELECT * FROM simulation_schedules ORDER BY id";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<SimulationSchedule> schedules = new ArrayList<>();
            while (rs.next()) {
                schedules.add(mapSchedule(rs));
            }
            return schedules;
        }
    }

    /** 回填自增主键（{@code Statement.RETURN_GENERATED_KEYS}）。 */
    public long insertSchedule(String code, String timeOfDay, double changePercent,
                               boolean repeatDaily) throws SQLException {
        String sql = """
                INSERT INTO simulation_schedules
                    (code, time_of_day, change_percent, repeat_daily, enabled, last_fired_date)
                VALUES (?, ?, ?, ?, ?, '')
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.setString(2, timeOfDay);
            ps.setDouble(3, changePercent);
            ps.setBoolean(4, repeatDaily);
            // 新插入的定时默认启用（H2 + MODE=MySQL 下 BOOLEAN 是真布尔，统一用 setBoolean）
            ps.setBoolean(5, true);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public boolean setScheduleEnabled(long id, boolean enabled) throws SQLException {
        String sql = "UPDATE simulation_schedules SET enabled = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** 记录触发日期；{@code disable} 为 {@code true} 时同时置 {@code enabled = false}（一次性）。 */
    public boolean markScheduleFired(long id, String firedDate, boolean disable) throws SQLException {
        // disable 分支才点名 enabled 列：H2 在 update 分支只改子句里点名的列，
        // 因此"每日重复"的 schedule 调用本方法时 enabled 保持不变。
        String sql = disable
                ? "UPDATE simulation_schedules SET last_fired_date = ?, enabled = ? WHERE id = ?"
                : "UPDATE simulation_schedules SET last_fired_date = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, firedDate);
            if (disable) {
                ps.setBoolean(index++, false);
            }
            ps.setLong(index, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteSchedule(long id) throws SQLException {
        String sql = "DELETE FROM simulation_schedules WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    // ── K 线 ────────────────────────────────────────────────────────────────

    public void upsertKLine(String code, SimulationBar bar) throws SQLException {
        if (bar == null) return;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(KLINE_UPSERT_SQL)) {
            bindKLine(ps, code, bar);
            ps.executeUpdate();
        }
    }

    public void upsertKLines(String code, List<SimulationBar> bars) throws SQLException {
        if (bars == null || bars.isEmpty()) return;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(KLINE_UPSERT_SQL)) {
            for (SimulationBar bar : bars) {
                if (bar == null) continue;
                bindKLine(ps, code, bar);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 读取指定桶起点（含）之后的全部 bar，{@code ORDER BY bucket_start}。 */
    public List<SimulationBar> findKlines(String code, long fromBucketStartInclusive) throws SQLException {
        String sql = """
                SELECT code, bucket_start, open, high, low, close
                FROM simulation_klines
                WHERE code = ? AND bucket_start >= ?
                ORDER BY bucket_start
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setLong(2, fromBucketStartInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                List<SimulationBar> bars = new ArrayList<>();
                while (rs.next()) {
                    bars.add(new SimulationBar(rs.getLong("bucket_start"), rs.getDouble("open"),
                            rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close")));
                }
                return bars;
            }
        }
    }

    // ── 行映射与参数绑定 ─────────────────────────────────────────────────────

    /**
     * 股票行映射。
     *
     * <p>先把库里的 {@code preset/boundary/limit_percent/上下限} 交给完整构造器（构造器会按预设
     * 套用一组波动参数），再逐字段覆盖成库里的真实值 —— 这样管理员手设的
     * {@code sigma/theta/jump} 系列 与 {@code amplitude} 不会被预设值抵消，{@code manual_price} 的 {@code NULL}
     * 也如实映射为 {@code null}。
     */
    private static SimulationStock mapStock(ResultSet rs) throws SQLException {
        String code = rs.getString("code");
        String name = rs.getString("name");
        String sector = rs.getString("sector");
        double initPrice = price(rs.getDouble("init_price"));
        long seed = rs.getLong("seed");
        double limitPercent = limitPercent(rs.getDouble("limit_percent"));
        SimulationVolatilityPreset preset = parsePreset(rs.getString("preset"));
        SimulationBoundary boundary = parseBoundary(rs.getString("boundary"));

        SimulationStock stock = new SimulationStock(code, name, sector, initPrice, seed,
                limitPercent, preset, boundary,
                price(rs.getDouble("min_price")), price(rs.getDouble("max_price")));

        stock.setPrevClose(price(rs.getDouble("prev_close")));
        stock.setCurrentPrice(price(rs.getDouble("current_price")));
        stock.setAutoPrice(price(rs.getDouble("auto_price")));
        stock.setAnchor(price(rs.getDouble("anchor")));
        stock.setMu(finite(rs.getDouble("mu"), 0.0));
        stock.setSigma(finite(rs.getDouble("sigma"), 0.0));
        stock.setTheta(finite(rs.getDouble("theta"), 0.0));
        stock.setJumpLambda(finite(rs.getDouble("jump_lambda"), 0.0));
        stock.setJumpMagnitude(finite(rs.getDouble("jump_magnitude"), 0.0));
        stock.setAmplitude(finite(rs.getDouble("amplitude"), 0.0));
        stock.setSuspended(rs.getBoolean("suspended"));
        stock.setTradable(rs.getBoolean("tradable"));
        stock.setPaused(rs.getBoolean("paused"));
        // 两个覆盖字段互斥（见 SimulationStock 的互斥写约定）：库里至多一个非 NULL，
        // 依次调用即可精确还原，且不会出现"先设 price 再被 target 清掉"的意外。
        stock.setManualPrice(readNullableDouble(rs, "manual_price"));
        stock.setManualTargetPercent(readNullableDouble(rs, "manual_target_pct"));
        stock.setSlopePerMs(finite(rs.getDouble("slope_per_ms"), 0.0));
        stock.setSlopeUntil(rs.getLong("slope_until"));
        stock.setUpdatedAt(rs.getLong("updated_at"));
        return stock;
    }

    private static SimulationHolding mapHolding(ResultSet rs) throws SQLException {
        return new SimulationHolding(parseUuid(rs.getString("player_uuid")), rs.getString("code"),
                rs.getInt("amount"), rs.getDouble("avg_cost"), rs.getDouble("last_price"),
                rs.getLong("last_fetched"));
    }

    private static SimulationSchedule mapSchedule(ResultSet rs) throws SQLException {
        String lastFiredDate = rs.getString("last_fired_date");
        return new SimulationSchedule(rs.getLong("id"), rs.getString("code"),
                rs.getString("time_of_day"), rs.getDouble("change_percent"),
                rs.getBoolean("repeat_daily"), rs.getBoolean("enabled"),
                lastFiredDate == null ? "" : lastFiredDate);
    }

    /**
     * 绑定 {@link #STOCK_UPSERT_SQL} 的全部 56 个参数。
     *
     * <p>顺序必须与 SQL 文本逐项一致：① 前 29 个 = INSERT 的列值（含 {@code created_at}）；
     * ② 后 27 个 = {@code ON DUPLICATE KEY UPDATE} 的目标值（不含 {@code code}/{@code created_at}）。
     */
    private static void bindStock(PreparedStatement ps, SimulationStock stock, long now)
            throws SQLException {
        String code = stock.getCode();
        String name = stock.getName();
        String sector = stock.getSector();
        double initPrice = price(stock.getInitPrice());
        double prevClose = price(stock.getPrevClose());
        double limitPercent = limitPercent(stock.getLimitPercent());
        double currentPrice = price(stock.getCurrentPrice());
        double autoPrice = price(stock.getAutoPrice());
        boolean suspended = stock.isSuspended();
        boolean tradable = stock.isTradable();
        boolean paused = stock.isPaused();
        double amplitude = finite(stock.getAmplitude(), 0.0);
        String preset = stock.getPreset() == null
                ? SimulationVolatilityPreset.NORMAL.id() : stock.getPreset().id();
        String boundary = stock.getBoundary() == null
                ? SimulationBoundary.SOFT_REGRESSION.id() : stock.getBoundary().id();
        double mu = finite(stock.getMu(), 0.0);
        double sigma = finite(stock.getSigma(), 0.0);
        double theta = finite(stock.getTheta(), 0.0);
        double jumpLambda = finite(stock.getJumpLambda(), 0.0);
        double jumpMagnitude = finite(stock.getJumpMagnitude(), 0.0);
        long seed = stock.getSeed();
        double minPrice = price(stock.getMinPrice());
        double maxPrice = price(stock.getMaxPrice());
        double anchor = price(stock.getAnchor());
        Double manualPrice = stock.getManualPrice();
        Double manualTargetPercent = stock.getManualTargetPercent();
        double slopePerMs = finite(stock.getSlopePerMs(), 0.0);
        long slopeUntil = stock.getSlopeUntil();
        long updatedAt = stock.getUpdatedAt() > 0 ? stock.getUpdatedAt() : now;

        int i = 1;
        // ① INSERT 分支（29 列）
        ps.setString(i++, code);
        ps.setString(i++, name);
        ps.setString(i++, sector);
        ps.setDouble(i++, initPrice);
        ps.setDouble(i++, prevClose);
        ps.setDouble(i++, limitPercent);
        ps.setDouble(i++, currentPrice);
        ps.setDouble(i++, autoPrice);
        ps.setBoolean(i++, suspended);
        ps.setBoolean(i++, tradable);
        ps.setBoolean(i++, paused);
        ps.setDouble(i++, amplitude);
        ps.setString(i++, preset);
        ps.setString(i++, boundary);
        ps.setDouble(i++, mu);
        ps.setDouble(i++, sigma);
        ps.setDouble(i++, theta);
        ps.setDouble(i++, jumpLambda);
        ps.setDouble(i++, jumpMagnitude);
        ps.setLong(i++, seed);
        ps.setDouble(i++, minPrice);
        ps.setDouble(i++, maxPrice);
        ps.setDouble(i++, anchor);
        setNullableDouble(ps, i++, manualPrice);
        setNullableDouble(ps, i++, manualTargetPercent);
        ps.setDouble(i++, slopePerMs);
        ps.setLong(i++, slopeUntil);
        ps.setLong(i, now);                     // created_at：只在插入时写
        i++;
        ps.setLong(i, updatedAt);               // updated_at
        i++;

        // ② ON DUPLICATE KEY UPDATE 分支（27 列，顺序与 SQL 子句逐项一致）
        ps.setString(i++, name);
        ps.setString(i++, sector);
        ps.setDouble(i++, initPrice);
        ps.setDouble(i++, prevClose);
        ps.setDouble(i++, limitPercent);
        ps.setDouble(i++, currentPrice);
        ps.setDouble(i++, autoPrice);
        ps.setBoolean(i++, suspended);
        ps.setBoolean(i++, tradable);
        ps.setBoolean(i++, paused);
        ps.setDouble(i++, amplitude);
        ps.setString(i++, preset);
        ps.setString(i++, boundary);
        ps.setDouble(i++, mu);
        ps.setDouble(i++, sigma);
        ps.setDouble(i++, theta);
        ps.setDouble(i++, jumpLambda);
        ps.setDouble(i++, jumpMagnitude);
        ps.setLong(i++, seed);
        ps.setDouble(i++, minPrice);
        ps.setDouble(i++, maxPrice);
        ps.setDouble(i++, anchor);
        setNullableDouble(ps, i++, manualPrice);
        setNullableDouble(ps, i++, manualTargetPercent);
        ps.setDouble(i++, slopePerMs);
        ps.setLong(i++, slopeUntil);
        ps.setLong(i, updatedAt);               // updated_at：created_at 刻意不点名 ⇒ 保留原值
    }

    /** 绑定 {@link #KLINE_UPSERT_SQL} 的 10 个参数（6 个插入值 + 4 个更新值）。 */
    private static void bindKLine(PreparedStatement ps, String code, SimulationBar bar)
            throws SQLException {
        double open = price(bar.open());
        double high = price(bar.high());
        double low = price(bar.low());
        double close = price(bar.close());
        ps.setString(1, code);
        ps.setLong(2, bar.bucketStart());
        ps.setDouble(3, open);
        ps.setDouble(4, high);
        ps.setDouble(5, low);
        ps.setDouble(6, close);
        ps.setDouble(7, open);
        ps.setDouble(8, high);
        ps.setDouble(9, low);
        ps.setDouble(10, close);
    }

    /** {@code NULL} 与非有限值分开处理：读回 {@code NULL} ⇒ 无人工覆盖。 */
    private static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, price(value));
        }
    }

    private static Double readNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** 价格兜底：非有限或 {@code < 0.01} ⇒ {@code 0.01}。 */
    private static double price(double value) {
        return (Double.isFinite(value) && value >= SAFE_PRICE) ? value : SAFE_PRICE;
    }

    /** 非价格类双精度兜底：非有限 ⇒ {@code fallback}（避免把 NaN 写进 DOUBLE 列）。 */
    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    /** 涨跌停百分数夹取：非有限 ⇒ 下界，保证带宽严格为正。 */
    private static double limitPercent(double value) {
        if (!Double.isFinite(value)) return MIN_LIMIT_PERCENT;
        return Math.max(MIN_LIMIT_PERCENT, Math.min(MAX_LIMIT_PERCENT, value));
    }

    private static SimulationVolatilityPreset parsePreset(String raw) {
        SimulationVolatilityPreset parsed = SimulationVolatilityPreset.parse(raw);
        return parsed == null ? SimulationVolatilityPreset.NORMAL : parsed;
    }

    /**
     * 边界策略反序列化。
     *
     * <p>{@link SimulationBoundary#parse} 现已能解析 {@link SimulationBoundary#id()} 的全称形式
     * （{@code hard_clamp} / {@code soft_regression}），故 {@code upsert → findAllStocks}
     * 的往返已是恒等。此处保留"先 parse、再按枚举名兜底"的两段式，作为防御：
     * 未来若新增枚举常量而忘记同步别名表，仍能按枚举名读回，不会静默退化为默认值。
     */
    private static SimulationBoundary parseBoundary(String raw) {
        SimulationBoundary parsed = SimulationBoundary.parse(raw);
        if (parsed != null) return parsed;
        if (raw == null) return SimulationBoundary.SOFT_REGRESSION;
        try {
            return SimulationBoundary.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SimulationBoundary.SOFT_REGRESSION;
        }
    }

    /** UUID 反序列化：非法值抛 {@link IllegalStateException}（与 {@code TradingRepository} 同语义）。 */
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Invalid player uuid: " + raw, e);
        }
    }
}
