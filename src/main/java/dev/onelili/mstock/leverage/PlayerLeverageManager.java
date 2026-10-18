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

    /**
     * 缓存哨兵：{@code 0} 表示「已查过库，该玩家未设置过杠杆」。
     *
     * <p>没有这个哨兵，从未设置过杠杆的玩家（最常见情形）每次读取都会在
     * 调用线程上同步查库 —— 交易路径在主线程上调用，等于每笔交易卡一次 JDBC 往返。
     */
    private static final int DB_MISS = 0;

    public PlayerLeverageManager(TradingRepository repository, LeverageConfig config, Logger logger) {
        this.repository = repository;
        this.config = config;
        this.logger = logger;
    }

    // ── 外汇 / CFD 杠杆 ───────────────────────────────────────────────────────

    /** 当前生效的外汇杠杆：内存优先，缺省为 {@code forex.default-leverage}。 */
    public int getLeverage(UUID playerUuid) {
        Integer cached = forexCache.get(playerUuid);
        if (cached != null) {
            return cached == DB_MISS ? config.getForexDefaultLeverage() : config.clamp(cached);
        }
        Integer stored = loadFromDatabase(playerUuid);
        // 查不到也要回填哨兵：只缓存「库里有值」的行，会让未设置过的玩家
        // 每次读取都同步走一趟 JDBC（主线程交易路径）。
        forexCache.put(playerUuid, stored != null && stored > 0 ? stored : DB_MISS);
        return stored != null && stored > 0 ? config.clamp(stored) : config.getForexDefaultLeverage();
    }

    /**
     * 设置玩家外汇杠杆。
     *
     * <p>越界返回 {@code false} 且不做任何修改（不抛异常，由调用方决定提示文案）。
     *
     * <p><b>先落库、成功后再进缓存</b>：反过来（内存先行）一旦写库失败，
     * 玩家被告知「设置失败」而本次会话却已生效，内存与 DB 分裂。
     */
    public boolean setLeverage(UUID playerUuid, int leverage) {
        if (!config.isValidLeverage(leverage)) return false;
        if (repository == null) {
            forexCache.put(playerUuid, leverage);
            return true;
        }
        try {
            repository.saveLeverage(playerUuid, leverage);
            forexCache.put(playerUuid, leverage);
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
        if (cached != null) {
            return cached == DB_MISS ? config.getStockDefaultLeverage() : config.clampStock(cached);
        }
        Integer stored = loadStockFromDatabase(playerUuid);
        stockCache.put(playerUuid, stored != null && stored > 0 ? stored : DB_MISS);
        return stored != null && stored > 0
                ? config.clampStock(stored) : config.getStockDefaultLeverage();
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
        if (repository == null) {
            stockCache.put(playerUuid, leverage);
            return true;
        }
        try {
            // 先落库、成功后再进缓存（与 setLeverage 同序），写库失败不留脏缓存。
            repository.saveStockLeverage(playerUuid, leverage);
            stockCache.put(playerUuid, leverage);
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

    /** 登录时预加载两套杠杆：无论是否设置过都回填缓存（未设置过回填哨兵）。 */
    public void load(UUID playerUuid) {
        Integer forex = loadFromDatabase(playerUuid);
        forexCache.put(playerUuid, forex != null && forex > 0 ? forex : DB_MISS);
        Integer stock = loadStockFromDatabase(playerUuid);
        stockCache.put(playerUuid, stock != null && stock > 0 ? stock : DB_MISS);
    }

    /**
     * 退出时落库并释放内存。
     *
     * <p><b>先写库、成功后再移除缓存</b>：反过来一旦写库失败，缓存已被清空，
     * 本次设置永久丢失，重启后表现为「莫名回退」。写库失败时保留缓存，
     * 关服 {@link #flushAll()} 还有一次兜底机会。
     */
    public void saveAndEvict(UUID playerUuid) {
        Integer forex = forexCache.get(playerUuid);
        Integer stock = stockCache.get(playerUuid);
        if (repository == null || (isUnset(forex) && isUnset(stock))) {
            forexCache.remove(playerUuid);
            stockCache.remove(playerUuid);
            return;
        }
        boolean saved = true;
        try {
            if (!isUnset(forex)) repository.saveLeverage(playerUuid, forex);
            if (!isUnset(stock)) repository.saveStockLeverage(playerUuid, stock);
        } catch (SQLException e) {
            saved = false;
            logger.warning("[MineStock] 退出时保存玩家杠杆失败 " + playerUuid + ": " + e.getMessage());
        }
        if (saved) {
            forexCache.remove(playerUuid);
            stockCache.remove(playerUuid);
        }
    }

    private static boolean isUnset(Integer value) {
        return value == null || value == DB_MISS;
    }

    /** 关服前把内存中的杠杆全部落库。 */
    public void flushAll() {
        if (repository == null) return;
        for (Map.Entry<UUID, Integer> entry : forexCache.entrySet()) {
            if (isUnset(entry.getValue())) continue; // 哨兵行没有可落库的设置
            try {
                repository.saveLeverage(entry.getKey(), entry.getValue());
            } catch (SQLException e) {
                logger.warning("[MineStock] 关服前保存玩家杠杆失败 " + entry.getKey()
                        + ": " + e.getMessage());
            }
        }
        for (Map.Entry<UUID, Integer> entry : stockCache.entrySet()) {
            if (isUnset(entry.getValue())) continue;
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
