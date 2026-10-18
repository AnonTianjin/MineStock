package dev.onelili.mstock.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.onelili.mstock.config.DatabaseConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class DatabaseManager {
    /** 当前 schema 版本：BACKFILL_COLUMNS 变更（新增列 / 索引）或迁移调整时递增。 */
    private static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 老库补列清单：表名 →（列名小写 → ALTER 用的列定义）。
     *
     * <p>升级安装时若旧表缺列（老版本建的库没有新功能的列），按此清单
     * {@code ALTER TABLE ... ADD COLUMN} 自动补齐，避免该表所有读写抛
     * {@code Column not found}。NOT NULL 列一律带 DEFAULT，保证对已有行也安全；
     * 列是否存在用"无行查询"的 {@link ResultSetMetaData} 判定（见
     * {@link #existingColumns(Connection, String)}），不依赖方言语法，
     * 也不依赖各驱动元数据方法的大小写行为，H2 / MySQL 通用。
     *
     * <p>维护约定：新增业务列时，同步更新 {@link #createTables()} 的建表 DDL 与本清单，
     * 并把 {@link #CURRENT_SCHEMA_VERSION} 递增 1 —— 所有低于新版本的库会重跑一次
     * （幂等的）补列巡检；需要数据转换的变更再另行写显式迁移。
     */
    private static final Map<String, Map<String, String>> BACKFILL_COLUMNS = buildBackfillColumns();

    /** 需要补建的索引：{表名, 索引名, 列列表}。不写进建表 DDL，统一由元数据判存后创建。 */
    private static final String[][] BACKFILL_INDEXES = {
            {"margin_positions", "idx_margin_positions_player", "player_uuid"},
            {"margin_positions", "idx_margin_positions_symbol", "market, symbol"},
            {"futures_positions", "idx_futures_positions_player", "player_uuid"},
            {"futures_positions", "idx_futures_positions_symbol", "symbol"},
    };

    private HikariDataSource dataSource;
    private final Logger logger;

    public DatabaseManager(Logger logger) {
        this.logger = logger;
    }

    public void init(DatabaseConfig cfg, File pluginFolder) throws SQLException {
        HikariConfig hikari = new HikariConfig();
        String type = cfg.getType().toLowerCase();

        if ("mysql".equals(type)) {
            hikari.setJdbcUrl("jdbc:mysql://" + cfg.getMysqlHost() + ":" + cfg.getMysqlPort()
                    + "/" + cfg.getMysqlDatabase() + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            hikari.setUsername(cfg.getMysqlUsername());
            hikari.setPassword(cfg.getMysqlPassword());
            hikari.setMaximumPoolSize(cfg.getMysqlPoolSize());
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            logger.info("[MineStock] 使用 MySQL 数据库");
        } else {
            File dataDir = new File(pluginFolder, "data");
            dataDir.mkdirs();
            String h2Path = cfg.getH2File();
            org.h2.Driver.load();
            hikari.setJdbcUrl("jdbc:h2:file:./" + h2Path + ";MODE=MySQL;AUTO_SERVER=TRUE");
            hikari.setMaximumPoolSize(2);
            hikari.setDriverClassName("org.h2.Driver");
            logger.info("[MineStock] 使用 H2 本地数据库");
        }

        hikari.setPoolName("MineStock-Pool");
        hikari.setConnectionTimeout(15000);
        hikari.setIdleTimeout(300000);
        hikari.setMaxLifetime(600000);

        initDataSource(hikari);
    }

    void initDataSource(HikariConfig hikari) throws SQLException {
        if (dataSource != null) close();
        dataSource = new HikariDataSource(hikari);
        try {
            createTables();
        } catch (SQLException e) {
            close();
            throw e;
        }
    }

    /**
     * 建表（幂等），随后执行结构迁移（见 {@link #migrateSchema(Connection)}）。
     *
     * <p><b>包级可见</b>而非 {@code private}：让测试能直接跑真实 DDL，
     * 避免"测试建的表对、生产的表错"的漂移（{@code DatabaseManagerLifecycleTest}
     * 已用同样手法调包级的 {@link #initDataSource}）。
     */
    void createTables() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS holdings (
                        id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid  VARCHAR(36) NOT NULL,
                        stock_code   VARCHAR(20) NOT NULL,
                        amount       INT NOT NULL,
                        avg_cost     DOUBLE NOT NULL,
                        last_price   DOUBLE NOT NULL,
                        last_fetched BIGINT NOT NULL,
                        UNIQUE (player_uuid, stock_code)
                    )
                    """);

            // 每个玩家一行：个人杠杆倍数
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_leverage (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        leverage    INT NOT NULL,
                        updated_at  BIGINT NOT NULL
                    )
                    """);

            // 股票杠杆与外汇杠杆分开存储：两者上限不同，玩家可以只调其中一个。
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_stock_leverage (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        leverage    INT NOT NULL,
                        updated_at  BIGINT NOT NULL
                    )
                    """);

            // 保证金持仓：同一 symbol 允许多行，各自保留开仓时的 leverage / margin。
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS margin_positions (
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

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS futures_positions (
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

            // ─────────────────────────── 模拟盘 ───────────────────────────
            // 模拟股状态。manual_price / manual_target_pct 允许 NULL（表示"无人工覆盖"）。
            // 布尔列用 BOOLEAN：已在本仓库的 H2 2.2.224 + MODE=MySQL 上实测通过
            // （DataBaseMetaData 报 DATA_TYPE=16 / TYPE_NAME=BOOLEAN，setBoolean/getBoolean 正常往返）。
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS simulation_stocks (
                        code                VARCHAR(16) PRIMARY KEY,
                        name                VARCHAR(64) NOT NULL,
                        sector              VARCHAR(32) NOT NULL,
                        init_price          DOUBLE NOT NULL,
                        prev_close          DOUBLE NOT NULL,
                        limit_percent       DOUBLE NOT NULL,
                        current_price       DOUBLE NOT NULL,
                        auto_price          DOUBLE NOT NULL,
                        suspended           BOOLEAN NOT NULL,
                        tradable            BOOLEAN NOT NULL,
                        paused              BOOLEAN NOT NULL,
                        amplitude           DOUBLE NOT NULL,
                        preset              VARCHAR(16) NOT NULL,
                        boundary            VARCHAR(24) NOT NULL,
                        mu                  DOUBLE NOT NULL,
                        sigma               DOUBLE NOT NULL,
                        theta               DOUBLE NOT NULL,
                        jump_lambda         DOUBLE NOT NULL,
                        jump_magnitude      DOUBLE NOT NULL,
                        seed                BIGINT NOT NULL,
                        min_price           DOUBLE NOT NULL,
                        max_price           DOUBLE NOT NULL,
                        anchor              DOUBLE NOT NULL,
                        manual_price        DOUBLE NULL,
                        manual_target_pct   DOUBLE NULL,
                        slope_per_ms        DOUBLE NOT NULL,
                        slope_until         BIGINT NOT NULL,
                        created_at          BIGINT NOT NULL,
                        updated_at          BIGINT NOT NULL
                    )
                    """);

            // 模拟现金持仓：与 holdings 隔离，且按设计只有 UNIQUE 约束、无自增主键。
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS simulation_holdings (
                        player_uuid  VARCHAR(36) NOT NULL,
                        code         VARCHAR(16) NOT NULL,
                        amount       INT NOT NULL,
                        avg_cost     DOUBLE NOT NULL,
                        last_price   DOUBLE NOT NULL,
                        last_fetched BIGINT NOT NULL,
                        UNIQUE (player_uuid, code)
                    )
                    """);

            // 联动：code_a < code_b 由写入方归一化，UNIQUE 才能真正去重。
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS simulation_links (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        code_a      VARCHAR(16) NOT NULL,
                        code_b      VARCHAR(16) NOT NULL,
                        correlation DOUBLE NOT NULL,
                        UNIQUE (code_a, code_b)
                    )
                    """);

            // 定时价格冲击：repeat_daily = false 时触发一次后置 enabled = false。
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS simulation_schedules (
                        id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                        code             VARCHAR(16) NOT NULL,
                        time_of_day      VARCHAR(5) NOT NULL,
                        change_percent   DOUBLE NOT NULL,
                        repeat_daily     BOOLEAN NOT NULL,
                        enabled          BOOLEAN NOT NULL,
                        last_fired_date  VARCHAR(10) NOT NULL
                    )
                    """);

            // 1 分钟 K 线：复合主键天然支持 upsert。
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS simulation_klines (
                        code         VARCHAR(16) NOT NULL,
                        bucket_start BIGINT NOT NULL,
                        open         DOUBLE NOT NULL,
                        high         DOUBLE NOT NULL,
                        low          DOUBLE NOT NULL,
                        close        DOUBLE NOT NULL,
                        PRIMARY KEY (code, bucket_start)
                    )
                    """);

            migrateSchema(conn);
        }
    }

    /**
     * 结构迁移（幂等）：
     *
     * <ol>
     *   <li>建 {@code schema_version} 版本表；</li>
     *   <li>库版本低于 {@link #CURRENT_SCHEMA_VERSION} 时，按 {@link #BACKFILL_COLUMNS}
     *       为旧表补缺列、按 {@link #BACKFILL_INDEXES} 补索引 —— 全部通过
     *       {@link java.sql.DatabaseMetaData} 判存，重复执行无副作用；</li>
     *   <li>把版本推进到 {@link #CURRENT_SCHEMA_VERSION}。</li>
     * </ol>
     *
     * <p>新装的库建表即完整，巡检为空操作，只多花一次元数据查询。
     */
    private void migrateSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
        }

        int version = readSchemaVersion(conn);
        if (version >= CURRENT_SCHEMA_VERSION) return;

        int addedColumns = backfillColumns(conn);
        int addedIndexes = backfillIndexes(conn);
        writeSchemaVersion(conn, CURRENT_SCHEMA_VERSION);

        if (addedColumns > 0 || addedIndexes > 0) {
            logger.info("[MineStock] 数据库结构迁移完成（v" + version + " → v"
                    + CURRENT_SCHEMA_VERSION + "）：补列 " + addedColumns
                    + "，补索引 " + addedIndexes);
        }
    }

    /** 读 schema_version；无表 / 无行（老库）视为版本 0。 */
    private int readSchemaVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {
            // 表刚建但查询仍失败（个别库的隔离可见性）→ 按版本 0 处理，补列巡检本身幂等。
        }
        return 0;
    }

    private void writeSchemaVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM schema_version");
            stmt.execute("INSERT INTO schema_version (version) VALUES (" + version + ")");
        }
    }

    /** 为旧表补缺列，返回补列数。 */
    private int backfillColumns(Connection conn) throws SQLException {
        int added = 0;
        for (Map.Entry<String, Map<String, String>> table : BACKFILL_COLUMNS.entrySet()) {
            Set<String> existing = existingColumns(conn, table.getKey());
            for (Map.Entry<String, String> column : table.getValue().entrySet()) {
                if (existing.contains(column.getKey())) continue;
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE " + table.getKey()
                            + " ADD COLUMN " + column.getKey() + " " + column.getValue());
                }
                added++;
                logger.info("[MineStock] 数据库迁移：为 " + table.getKey() + " 补列 " + column.getKey());
            }
        }
        return added;
    }

    /**
     * 列出表的现有列名（小写）。
     *
     * <p>不用 {@code DatabaseMetaData.getColumns}：它对未加引号标识符的大小写处理
     * 各库不一（实测 H2 2.x 对小写表名返回空集，会误判"整表无列"而连环报
     * {@code Duplicate column}）。{@code SELECT ... WHERE 1=0} 不取任何行、
     * 只读结果集元数据，任何 JDBC 驱动都可靠。
     */
    private Set<String> existingColumns(Connection conn, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** 为缺索引的表补索引，返回补建数。 */
    private int backfillIndexes(Connection conn) throws SQLException {
        int added = 0;
        for (String[] index : BACKFILL_INDEXES) {
            if (indexExists(conn, index[0], index[1])) continue;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE INDEX " + index[1] + " ON " + index[0] + " (" + index[2] + ")");
            }
            added++;
            logger.info("[MineStock] 数据库迁移：为 " + index[0] + " 补索引 " + index[1]);
        }
        return added;
    }

    private boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
        // 与 existingColumns 同因：元数据方法对小写表名的匹配各库不一（H2 2.x 返回空集），
        // 先按原样查、查不到再按大写表名重试一次。
        for (String candidate : new String[]{table, table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = conn.getMetaData()
                    .getIndexInfo(conn.getCatalog(), null, candidate, false, true)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (indexName.equalsIgnoreCase(name)) return true;
                }
            }
        }
        return false;
    }

    /** 组装补列清单：表名 → 列名 → 定义。 */
    private static Map<String, Map<String, String>> buildBackfillColumns() {
        Map<String, Map<String, String>> columns = new LinkedHashMap<>();
        columns.put("holdings", cols(
                "player_uuid", "VARCHAR(36) NOT NULL DEFAULT ''",
                "stock_code", "VARCHAR(20) NOT NULL DEFAULT ''",
                "amount", "INT NOT NULL DEFAULT 0",
                "avg_cost", "DOUBLE NOT NULL DEFAULT 0",
                "last_price", "DOUBLE NOT NULL DEFAULT 0",
                "last_fetched", "BIGINT NOT NULL DEFAULT 0"));
        columns.put("player_leverage", cols(
                "leverage", "INT NOT NULL DEFAULT 1",
                "updated_at", "BIGINT NOT NULL DEFAULT 0"));
        columns.put("player_stock_leverage", cols(
                "leverage", "INT NOT NULL DEFAULT 1",
                "updated_at", "BIGINT NOT NULL DEFAULT 0"));
        columns.put("margin_positions", cols(
                "player_uuid", "VARCHAR(36) NOT NULL DEFAULT ''",
                "market", "VARCHAR(16) NOT NULL DEFAULT 'FOREX'",
                "symbol", "VARCHAR(24) NOT NULL DEFAULT ''",
                "direction", "VARCHAR(8) NOT NULL DEFAULT 'LONG'",
                "quantity", "DOUBLE NOT NULL DEFAULT 0",
                "entry_price", "DOUBLE NOT NULL DEFAULT 0",
                "leverage", "INT NOT NULL DEFAULT 1",
                "margin", "DOUBLE NOT NULL DEFAULT 0",
                "liquidation_price", "DOUBLE NOT NULL DEFAULT 0",
                "unrealized_pnl", "DOUBLE NOT NULL DEFAULT 0",
                "opened_at", "BIGINT NOT NULL DEFAULT 0"));
        columns.put("futures_positions", cols(
                "player_uuid", "VARCHAR(36) NOT NULL DEFAULT ''",
                "symbol", "VARCHAR(24) NOT NULL DEFAULT ''",
                "direction", "VARCHAR(8) NOT NULL DEFAULT 'LONG'",
                "quantity", "DOUBLE NOT NULL DEFAULT 0",
                "entry_price", "DOUBLE NOT NULL DEFAULT 0",
                "contract_size", "DOUBLE NOT NULL DEFAULT 0",
                "initial_margin_rate", "DOUBLE NOT NULL DEFAULT 0",
                "margin", "DOUBLE NOT NULL DEFAULT 0",
                "expiry_date", "VARCHAR(10) NOT NULL DEFAULT ''",
                "last_settle_price", "DOUBLE NOT NULL DEFAULT 0",
                "realized_pnl", "DOUBLE NOT NULL DEFAULT 0",
                "opened_at", "BIGINT NOT NULL DEFAULT 0"));
        columns.put("simulation_stocks", cols(
                "name", "VARCHAR(64) NOT NULL DEFAULT ''",
                "sector", "VARCHAR(32) NOT NULL DEFAULT ''",
                "init_price", "DOUBLE NOT NULL DEFAULT 0",
                "prev_close", "DOUBLE NOT NULL DEFAULT 0",
                "limit_percent", "DOUBLE NOT NULL DEFAULT 10",
                "current_price", "DOUBLE NOT NULL DEFAULT 0",
                "auto_price", "DOUBLE NOT NULL DEFAULT 0",
                "suspended", "BOOLEAN NOT NULL DEFAULT FALSE",
                "tradable", "BOOLEAN NOT NULL DEFAULT TRUE",
                "paused", "BOOLEAN NOT NULL DEFAULT FALSE",
                "amplitude", "DOUBLE NOT NULL DEFAULT 0",
                "preset", "VARCHAR(16) NOT NULL DEFAULT 'NORMAL'",
                "boundary", "VARCHAR(24) NOT NULL DEFAULT ''",
                "mu", "DOUBLE NOT NULL DEFAULT 0",
                "sigma", "DOUBLE NOT NULL DEFAULT 0",
                "theta", "DOUBLE NOT NULL DEFAULT 0",
                "jump_lambda", "DOUBLE NOT NULL DEFAULT 0",
                "jump_magnitude", "DOUBLE NOT NULL DEFAULT 0",
                "seed", "BIGINT NOT NULL DEFAULT 0",
                "min_price", "DOUBLE NOT NULL DEFAULT 0",
                "max_price", "DOUBLE NOT NULL DEFAULT 0",
                "anchor", "DOUBLE NOT NULL DEFAULT 0",
                "manual_price", "DOUBLE NULL",
                "manual_target_pct", "DOUBLE NULL",
                "slope_per_ms", "DOUBLE NOT NULL DEFAULT 0",
                "slope_until", "BIGINT NOT NULL DEFAULT 0",
                "created_at", "BIGINT NOT NULL DEFAULT 0",
                "updated_at", "BIGINT NOT NULL DEFAULT 0"));
        columns.put("simulation_holdings", cols(
                "player_uuid", "VARCHAR(36) NOT NULL DEFAULT ''",
                "code", "VARCHAR(16) NOT NULL DEFAULT ''",
                "amount", "INT NOT NULL DEFAULT 0",
                "avg_cost", "DOUBLE NOT NULL DEFAULT 0",
                "last_price", "DOUBLE NOT NULL DEFAULT 0",
                "last_fetched", "BIGINT NOT NULL DEFAULT 0"));
        columns.put("simulation_links", cols(
                "code_a", "VARCHAR(16) NOT NULL DEFAULT ''",
                "code_b", "VARCHAR(16) NOT NULL DEFAULT ''",
                "correlation", "DOUBLE NOT NULL DEFAULT 0"));
        columns.put("simulation_schedules", cols(
                "code", "VARCHAR(16) NOT NULL DEFAULT ''",
                "time_of_day", "VARCHAR(5) NOT NULL DEFAULT '09:30'",
                "change_percent", "DOUBLE NOT NULL DEFAULT 0",
                "repeat_daily", "BOOLEAN NOT NULL DEFAULT FALSE",
                "enabled", "BOOLEAN NOT NULL DEFAULT TRUE",
                "last_fired_date", "VARCHAR(10) NOT NULL DEFAULT ''"));
        columns.put("simulation_klines", cols(
                "open", "DOUBLE NOT NULL DEFAULT 0",
                "high", "DOUBLE NOT NULL DEFAULT 0",
                "low", "DOUBLE NOT NULL DEFAULT 0",
                "close", "DOUBLE NOT NULL DEFAULT 0"));
        return columns;
    }

    /** 把"列名, 定义"成对参数组装成有序映射。 */
    private static Map<String, String> cols(String... nameAndDefinition) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameAndDefinition.length; i += 2) {
            map.put(nameAndDefinition[i], nameAndDefinition[i + 1]);
        }
        return map;
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("MineStock database is closed");
        }
        return dataSource.getConnection();
    }

    public synchronized void close() {
        HikariDataSource current = dataSource;
        dataSource = null;
        if (current != null && !current.isClosed()) {
            current.close();
        }

        ClassLoader pluginLoader = DatabaseManager.class.getClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() != pluginLoader) continue;
            try {
                if (driver.getClass().getName().equals("org.h2.Driver")) {
                    org.h2.Driver.unload();
                } else {
                    DriverManager.deregisterDriver(driver);
                }
            } catch (SQLException e) {
                logger.warning("[MineStock] 注销 JDBC 驱动失败: " + e.getMessage());
            }
        }
    }
}
