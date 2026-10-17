package dev.onelili.mstock.simulation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 模拟股票池：启动时从 DB 载入、按需补齐默认宇宙、提供 CRUD。
 *
 * <p>线程安全：内部用 {@code ConcurrentHashMap} 承载，{@code all()} 返回按 code 升序的
 * <b>不可变快照</b>（调用方 add 不影响后续读取）。
 */
public final class SimulationStockRegistry {

    /** 默认宇宙的固定模板：code / name / sector / initPrice。 */
    private static final String[][] UNIVERSE = {
            {"SIM001", "模拟股 01", "科技", "10.00"},
            {"SIM002", "模拟股 02", "金融", "25.50"},
            {"SIM003", "模拟股 03", "能源", "88.00"},
            {"SIM004", "模拟股 04", "消费", "5.20"},
            {"SIM005", "模拟股 05", "医药", "120.00"},
            {"SIM006", "模拟股 06", "科技", "42.00"},
            {"SIM007", "模拟股 07", "金融", "15.75"},
            {"SIM008", "模拟股 08", "能源", "300.00"},
            {"SIM009", "模拟股 09", "消费", "7.30"},
            {"SIM010", "模拟股 10", "医药", "55.60"}
    };

    private final SimulationRepository repository;
    private final SimulationConfig config;
    private final Logger logger;

    /** key = {@link #normalize(String)} 归一化后的代码。 */
    private final Map<String, SimulationStock> stocks = new ConcurrentHashMap<>();

    public SimulationStockRegistry(SimulationRepository repository, SimulationConfig config,
                                   Logger logger) {
        this.repository = repository;
        this.config = config;
        this.logger = logger;
    }

    /**
     * 从 DB 载入；不足 {@code auto-fill-count} 时用默认宇宙补齐并落库。返回是否发生了写库。
     *
     * <p>补齐策略：以 {@link #defaultUniverse(int, long)} 的前 N 只为模板，<b>只补库里没有的代码</b>；
     * 已存在的股票（管理员改过名字/价格/预设）一律保持库里的值不变 —— 覆盖会让重启回退管理员配置。
     */
    public boolean bootstrap() throws SQLException {
        List<SimulationStock> loaded = repository.findAllStocks();

        // 以 DB 为权威重建内存镜像
        Map<String, SimulationStock> next = new ConcurrentHashMap<>();
        for (SimulationStock stock : loaded) {
            String code = normalize(stock.getCode());
            if (code != null) {
                next.put(code, stock);
            }
        }

        List<SimulationStock> toPersist = new ArrayList<>();
        for (SimulationStock template : defaultUniverse(config.autoFillCount(), config.seedBase())) {
            if (next.containsKey(template.getCode())) continue;
            next.put(template.getCode(), template);
            toPersist.add(template);
        }

        stocks.clear();
        stocks.putAll(next);
        if (!toPersist.isEmpty()) {
            repository.upsertStocks(toPersist);
            logger.info("[MineStock] 模拟股票池自动补齐 " + toPersist.size() + " 只，当前共 "
                    + stocks.size() + " 只");
            return true;
        }
        return false;
    }

    /**
     * 默认宇宙：{@code seed = seedBase + index}（同一种子 ⇒ 同一价格路径）。
     *
     * @param count    需要的股票数量，超出模板长度时截断到模板长度
     * @param seedBase 种子基数
     */
    public static List<SimulationStock> defaultUniverse(int count, long seedBase) {
        int limit = Math.max(0, Math.min(UNIVERSE.length, count));
        java.util.List<SimulationStock> stocks = new java.util.ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String[] row = UNIVERSE[i];
            stocks.add(new SimulationStock(row[0], row[1], row[2],
                    Double.parseDouble(row[3]), seedBase + i));
        }
        return List.copyOf(stocks);
    }

    /** 大小写不敏感的存在性判断。 */
    public boolean contains(String code) {
        String key = normalize(code);
        return key != null && stocks.containsKey(key);
    }

    public Optional<SimulationStock> find(String code) {
        String key = normalize(code);
        return key == null ? Optional.empty() : Optional.ofNullable(stocks.get(key));
    }

    /** 按 code 升序的不可变快照。 */
    public List<SimulationStock> all() {
        List<SimulationStock> snapshot = new ArrayList<>(stocks.values());
        snapshot.sort(Comparator.comparing(SimulationStock::getCode));
        return List.copyOf(snapshot);
    }

    public int size() {
        return stocks.size();
    }

    /**
     * 新增或覆盖一只股票（管理员）。
     *
     * <p>只改内存镜像，<b>不落库</b>（本签名不抛 {@code SQLException}）；持久化由调用方
     * （{@code SimulationService}）在 {@code LifecycleTaskManager} 上完成。
     */
    public void put(SimulationStock stock) {
        if (stock == null) return;
        String code = normalize(stock.getCode());
        if (code == null) return;
        stocks.put(code, stock);
    }

    /**
     * 移除一只股票（管理员）；返回是否真的移除了。
     *
     * <p>级联由本方法<b>显式</b>完成 —— 仓储的 {@code deleteStock} 不做任何级联：
     * ① 股票行；② 该股参与的全部联动；③ 指向该股的全部定时操作。
     * 持仓（{@code simulation_holdings}）与 K 线历史<b>刻意保留</b>，避免误删玩家资产与历史。
     *
     * <p>本签名不抛 {@code SQLException}（public 签名已冻结），故落库失败时包成
     * {@link IllegalStateException} 抛出，绝不静默返回 {@code true} 造成"内存已删、库还在"的漂移。
     */
    public boolean remove(String code) {
        String key = normalize(code);
        if (key == null || stocks.remove(key) == null) {
            return false;
        }
        try {
            repository.deleteStock(key);
            repository.deleteLinksFor(key);
            for (SimulationSchedule schedule : repository.findSchedules()) {
                if (key.equals(normalize(schedule.code()))) {
                    repository.deleteSchedule(schedule.id());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove simulation stock: " + key, e);
        }
        return true;
    }

    /**
     * 生成一个<b>每次调用都不同</b>的新种子。
     *
     * <p>旧实现是 {@code seedBase + stocks.size()}：对固定的股票池规模恒为同一个值，
     * 于是 {@code /mstock simulation admin seed <代码>}（不带显式种子）永远得到同一个数，
     * 表现为"重设随机种子没有生效"。种子只用于 {@link SimulationStock#reseed(long)}
     * 重建 {@link java.util.Random}，因此取时间熵即可 —— 但必须避开上一次的取值，
     * 保证连续两次"重设"一定产生不同的价格路径。
     *
     * <p>{@code seedBase} 继续作为偏移锚点，让种子仍然落在配置约定的量级上。
     */
    public long nextSeed() {
        long candidate;
        do {
            candidate = config.seedBase() + Math.floorMod(System.nanoTime(), SEED_SPREAD);
        } while (candidate == lastSeed);
        lastSeed = candidate;
        return candidate;
    }

    /** {@link #nextSeed()} 的时间熵取模范围（10 亿）—— 与 {@code seedBase} 同量级。 */
    private static final long SEED_SPREAD = 1_000_000_000L;

    /** 上一次发放的种子；{@link #nextSeed()} 保证不会连续两次给出同一个值。 */
    private volatile long lastSeed = Long.MIN_VALUE;

    /** 只清内存镜像，不删库（供 {@code reload} 前重建）。 */
    public void clear() {
        stocks.clear();
    }

    /** 代码归一化：strip + 大写；空白返回 {@code null}。 */
    static String normalize(String code) {
        if (code == null) return null;
        String trimmed = code.strip();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
