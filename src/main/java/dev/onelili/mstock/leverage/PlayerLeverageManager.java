package dev.onelili.mstock.leverage;

import dev.onelili.mstock.config.LeverageConfig;
import dev.onelili.mstock.database.TradingRepository;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 每个玩家独立的杠杆倍数，按市场分开管理。
 *
 * <p>外汇 / CFD 与股票的杠杆<b>互不影响</b>：股票波动率与监管口径不同，
 * 玩家可以只调其中一个。两套设置分别落在 {@code player_leverage} 与
 * {@code player_stock_leverage} 两张表。
 *
 * <p>在线玩家保存在内存（{@link ConcurrentHashMap}），登录时加载、退出时写库；
 * 未设置过的玩家返回各自的默认值。
 *
 * <p>杠杆只影响<b>新开仓位</b>：已有仓位保留开仓时记录的杠杆与保证金。
 */
public class PlayerLeverageManager {

    private final TradingRepository repository;
    private final LeverageConfig config;
    private final Logger logger;

    /** 外汇 / CFD 杠杆。 */
    private final Map<UUID, Integer> forexCache = new ConcurrentHashMap<>();
    /** 股票杠杆。 */
    private final Map<UUID, Integer> stockCache = new ConcurrentHashMap<>();

    public PlayerLeverageManager(TradingRepository repository, LeverageConfig config, Logger logger) {
        this.repository = repository;
        this.config = config;
        this.logger = logger;
    }

    // ── 外汇 / CFD 杠杆 ───────────────────────────────────────────────────────

    /** 当前生效的外汇杠杆：内存优先，缺省为 {@code forex.default-leverage}。 */
    public int getLeverage(UUID playerUuid) {
        Integer cached = forexCache.get(playerUuid);
        if (cached != null) return config.clamp(cached);
        Integer stored = loadFromDatabase(playerUuid);
        if (stored != null) {
            forexCache.put(playerUuid, stored);
            return config.clamp(stored);
        }
        return config.getForexDefaultLeverage();
    }

    /**
     * 设置玩家外汇杠杆。
     *
     * <p>越界返回 {@code false} 且不做任何修改（不抛异常，由调用方决定提示文案）。
     */
    public boolean setLeverage(UUID playerUuid, int leverage) {
        if (!config.isValidLeverage(leverage)) return false;
        forexCache.put(playerUuid, leverage);
        if (repository == null) return true;
        try {
            repository.saveLeverage(playerUuid, leverage);
            return true;
        } catch (SQLException e) {
            logger.warning("[MineStock] 保存玩家杠杆失败 " + playerUuid + ": " + e.getMessage());
            return false;
        }
    }

    public int getMaxLeverage() {
        return config.getMaxLeverage();
    }

    public int getDefaultLeverage() {
        return config.getForexDefaultLeverage();
    }

    // ── 股票杠杆 ──────────────────────────────────────────────────────────────

    /** 当前生效的股票杠杆；未设置时为 {@link LeverageConfig#getStockDefaultLeverage}。 */
    public int getStockLeverage(UUID playerUuid) {
        Integer cached = stockCache.get(playerUuid);
        if (cached != null) return config.clampStock(cached);
        Integer stored = loadStockFromDatabase(playerUuid);
        if (stored != null) {
            stockCache.put(playerUuid, stored);
            return config.clampStock(stored);
        }
        return config.getStockDefaultLeverage();
    }

    /**
     * 设置股票杠杆，越界返回 {@code false}。
     *
     * <p>校验用 {@link LeverageConfig#accepts}（股票制度）而非裸区间：
     * 这样 {@code leverage.stock.enabled: false} 时写入路径同样被挡住。
     * 只在读取时判断开关是不够的 —— 玩家可能已经把股票页签打开着，
     * 管理员随后关闭了开关。
     */
    public boolean setStockLeverage(UUID playerUuid, int leverage) {
        if (!config.accepts(LeverageConfig.Policy.STOCK, leverage)) return false;
        stockCache.put(playerUuid, leverage);
        if (repository == null) return true;
        try {
            repository.saveStockLeverage(playerUuid, leverage);
            return true;
        } catch (SQLException e) {
            logger.warning("[MineStock] 保存股票杠杆失败 " + playerUuid + ": " + e.getMessage());
            return false;
        }
    }

    public int getStockMaxLeverage() {
        return config.getStockMaxLeverage();
    }

    public int getStockDefaultLeverage() {
        return config.getStockDefaultLeverage();
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    /** 登录时预加载两套杠杆，失败静默降级为内存默认值。 */
    public void load(UUID playerUuid) {
        Integer forex = loadFromDatabase(playerUuid);
        if (forex != null) forexCache.put(playerUuid, forex);
        Integer stock = loadStockFromDatabase(playerUuid);
        if (stock != null) stockCache.put(playerUuid, stock);
    }

    /** 退出时落库并释放内存。 */
    public void saveAndEvict(UUID playerUuid) {
        Integer forex = forexCache.remove(playerUuid);
        Integer stock = stockCache.remove(playerUuid);
        if (repository == null || (forex == null && stock == null)) return;
        try {
            if (forex != null) repository.saveLeverage(playerUuid, forex);
            if (stock != null) repository.saveStockLeverage(playerUuid, stock);
        } catch (SQLException e) {
            logger.warning("[MineStock] 退出时保存玩家杠杆失败 " + playerUuid + ": " + e.getMessage());
        }
    }

    /** 关服前把内存中的杠杆全部落库。 */
    public void flushAll() {
        if (repository == null) return;
        for (Map.Entry<UUID, Integer> entry : forexCache.entrySet()) {
            try {
                repository.saveLeverage(entry.getKey(), entry.getValue());
            } catch (SQLException e) {
                logger.warning("[MineStock] 关服前保存玩家杠杆失败 " + entry.getKey()
                        + ": " + e.getMessage());
            }
        }
        for (Map.Entry<UUID, Integer> entry : stockCache.entrySet()) {
            try {
                repository.saveStockLeverage(entry.getKey(), entry.getValue());
            } catch (SQLException e) {
                logger.warning("[MineStock] 关服前保存股票杠杆失败 " + entry.getKey()
                        + ": " + e.getMessage());
            }
        }
        forexCache.clear();
        stockCache.clear();
    }

    private Integer loadFromDatabase(UUID playerUuid) {
        if (repository == null) return null;
        try {
            return repository.findLeverage(playerUuid);
        } catch (SQLException e) {
            logger.warning("[MineStock] 读取玩家杠杆失败 " + playerUuid + ": " + e.getMessage());
            return null;
        }
    }

    private Integer loadStockFromDatabase(UUID playerUuid) {
        if (repository == null) return null;
        try {
            return repository.findStockLeverage(playerUuid);
        } catch (SQLException e) {
            logger.warning("[MineStock] 读取股票杠杆失败 " + playerUuid + ": " + e.getMessage());
            return null;
        }
    }
}
