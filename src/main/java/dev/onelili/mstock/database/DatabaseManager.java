package dev.onelili.mstock.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.onelili.mstock.config.DatabaseConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.logging.Logger;

public class DatabaseManager {
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
     * 建表（幂等）。
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
        }
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
