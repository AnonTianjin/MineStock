package dev.onelili.mstock.ui.gui;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.model.ForexCategory;
import dev.onelili.mstock.model.ForexQuote;
import dev.onelili.mstock.model.MarketSymbol;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 外汇界面，分三级。
 *
 * <ol>
 *   <li><b>板块页</b>：把"国家外汇"与"虚拟货币"分成两个独立入口，另有"全部"与"搜索"。</li>
 *   <li><b>行情页</b>：只在当前板块内展示活跃品种，支持翻页、板块切换与模糊搜索。</li>
 *   <li><b>交易页</b>：买入 / 卖出 / 设置数量 / 查看K线。</li>
 * </ol>
 *
 * <p>所有交易按钮都调用 {@code ForexService#buy|sell}，
 * 与 {@code /mstock forex buy|sell} 是同一条路径。
 */
public class ForexGui {

    public static final String CATEGORY_TITLE = "外汇板块";
    public static final String MARKET_TITLE = "外汇行情";
    public static final String TRADE_TITLE = "外汇交易";

    // ── 板块页槽位 ────────────────────────────────────────────────────────────
    /** 板块页标题信息（各页面统一用 4 号槽做信息位）。 */
    public static final int SLOT_INFO = 4;
    public static final int SLOT_CAT_FIAT = 20;
    public static final int SLOT_CAT_CRYPTO = 22;
    public static final int SLOT_CAT_ALL = 24;
    public static final int SLOT_CAT_SEARCH = 30;

    // ── 行情页槽位 ────────────────────────────────────────────────────────────
    /** 品种占 0..44。 */
    public static final int SYMBOL_SLOTS = 45;
    public static final int SLOT_PREV_PAGE = 45;
    /** 刷新放在 46：49 留给"返回主菜单"，两者不能同槽。 */
    public static final int SLOT_REFRESH = 46;
    /** 搜索按钮。 */
    public static final int SLOT_SEARCH = 47;
    /** 清空当前搜索。 */
    public static final int SLOT_CLEAR_SEARCH = 48;
    public static final int SLOT_NEXT_PAGE = 53;

    // ── 交易页槽位 ────────────────────────────────────────────────────────────
    public static final int SLOT_BUY = 20;
    public static final int SLOT_SELL = 24;
    public static final int SLOT_QUANTITY = 22;
    public static final int SLOT_CHART = 40;
    public static final int SLOT_BACK_TO_MARKET = 45;

    private final MineStock plugin;

    public ForexGui(MineStock plugin) {
        this.plugin = plugin;
    }

    // ── 板块页 ────────────────────────────────────────────────────────────────

    /** 打开板块选择页。 */
    public void openCategory(Player player) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.FOREX_CATEGORY);
        Inventory inventory = GuiItems.create(holder, CATEGORY_TITLE, NamedTextColor.GOLD);
        renderCategoryLoading(inventory);
        player.openInventory(inventory);
        loadCategoryCounts(player, holder);
    }

    private void renderCategoryLoading(Inventory inventory) {
        inventory.setItem(SLOT_INFO, GuiItems.info(Material.CLOCK,
                Component.text("加载中…").color(NamedTextColor.YELLOW),
                List.of(GuiItems.label("正在统计各板块品种数量"))));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    private void loadCategoryCounts(Player player, GuiHolder holder) {
        var service = plugin.getForexService();
        if (service == null) {
            renderCategory(holder.getInventory(), Map.of(), 0);
            return;
        }
        service.categoryCounts().thenAccept(counts -> runOnPlayer(player, () -> {
            Player current = Bukkit.getPlayer(player.getUniqueId());
            if (current == null || !current.isOnline()) return;
            if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            renderCategory(holder.getInventory(), counts, total);
        })).exceptionally(error -> {
            plugin.getLogger().warning("[MineStock] 加载外汇板块统计失败: " + error.getMessage());
            runOnPlayer(player, () -> {
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current != null && current.isOnline()) {
                    current.sendMessage(plugin.getLang().get("forex-symbols-failed"));
                }
            });
            return null;
        });
    }

    private void renderCategory(Inventory inventory, Map<ForexCategory, Integer> counts, int total) {
        int fiat = counts.getOrDefault(ForexCategory.FIAT, 0);
        int crypto = counts.getOrDefault(ForexCategory.CRYPTO, 0);

        inventory.setItem(4, GuiItems.info(Material.GOLD_INGOT,
                Component.text("外汇板块").color(NamedTextColor.GOLD),
                List.of(
                        GuiItems.line("活跃品种总数", String.valueOf(total), NamedTextColor.WHITE),
                        GuiItems.label("数据源 biquote（价格取 mid）"),
                        GuiItems.label("选择板块后进入行情页"))));

        // 两个独立板块：国家外汇 / 虚拟货币
        inventory.setItem(SLOT_CAT_FIAT, GuiItems.button(Material.GOLD_INGOT,
                Component.text(ForexCategory.FIAT.label()).color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.label(ForexCategory.FIAT.description()),
                        GuiItems.line("活跃品种", String.valueOf(fiat) + " 个", NamedTextColor.WHITE),
                        GuiItems.label("如 EURUSD / USDJPY / XAUUSD"),
                        Component.empty(),
                        GuiItems.label("点击进入"))));

        inventory.setItem(SLOT_CAT_CRYPTO, GuiItems.button(Material.ENDER_EYE,
                Component.text(ForexCategory.CRYPTO.label()).color(NamedTextColor.LIGHT_PURPLE),
                List.of(
                        GuiItems.label(ForexCategory.CRYPTO.description()),
                        GuiItems.line("活跃品种", String.valueOf(crypto) + " 个",
                                NamedTextColor.WHITE),
                        GuiItems.label("如 BTCUSD / ETHUSDT / SOLUSDT"),
                        Component.empty(),
                        GuiItems.label("点击进入"))));

        inventory.setItem(SLOT_CAT_ALL, GuiItems.button(Material.CHEST,
                Component.text("全部板块").color(NamedTextColor.WHITE),
                List.of(
                        GuiItems.line("活跃品种", String.valueOf(total) + " 个",
                                NamedTextColor.WHITE),
                        GuiItems.label("不区分板块，一次列出全部"))));

        inventory.setItem(SLOT_CAT_SEARCH, GuiItems.button(Material.COMPASS,
                Component.text("模糊搜索").color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.label("按代码或中文名搜索"),
                        GuiItems.label("支持子序列，如 EUU → EURUSD"),
                        Component.empty(),
                        GuiItems.label("点击后在聊天框输入关键词"))));

        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    // ── 行情页 ────────────────────────────────────────────────────────────────

    /** 打开行情页：先显示"加载中…"，数据到达后刷新。 */
    public void openMarket(Player player, int page) {
        openMarket(player, ForexCategory.FIAT, "", page);
    }

    /** 打开指定板块 / 关键词的行情页。 */
    public void openMarket(Player player, ForexCategory category, String keyword, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.FOREX_MARKET)
                .category(category)
                .keyword(keyword)
                .page(page);
        Inventory inventory = GuiItems.create(holder, marketTitle(category, keyword),
                NamedTextColor.GOLD);
        renderMarketLoading(inventory, category, keyword);
        player.openInventory(inventory);
        loadMarket(player, holder);
    }

    private static String marketTitle(ForexCategory category, String keyword) {
        if (keyword != null && !keyword.isBlank()) return MARKET_TITLE + " · 搜索";
        if (category == null) return MARKET_TITLE + " · 全部";
        return MARKET_TITLE + " · " + category.label();
    }

    private void renderMarketLoading(Inventory inventory, ForexCategory category, String keyword) {
        inventory.setItem(4, GuiItems.info(Material.CLOCK,
                Component.text("加载中…").color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.label("正在获取活跃品种（liveOnly=true）"),
                        GuiItems.line("板块",
                                category == null ? "全部" : category.label(), NamedTextColor.GRAY),
                        GuiItems.line("关键词",
                                keyword == null || keyword.isBlank() ? "-" : keyword,
                                NamedTextColor.GRAY))));
        inventory.setItem(SLOT_REFRESH, GuiItems.button(Material.ARROW,
                Component.text("刷新").color(NamedTextColor.GREEN)));
        inventory.setItem(SLOT_SEARCH, GuiItems.button(Material.COMPASS,
                Component.text("搜索").color(NamedTextColor.AQUA)));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    private void loadMarket(Player player, GuiHolder holder) {
        var service = plugin.getForexService();
        if (service == null) return;
        service.search(holder.getCategory(), holder.getKeyword(), 0)
                .thenAccept(symbols -> runOnPlayer(player, () -> {
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    if (current == null || !current.isOnline()) return;
                    if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;
                    renderMarket(current, holder, symbols);
                })).exceptionally(error -> {
            plugin.getLogger().warning("[MineStock] 加载外汇活跃品种失败: " + error.getMessage());
            runOnPlayer(player, () -> {
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current == null || !current.isOnline()) return;
                current.sendMessage(plugin.getLang().get("forex-symbols-failed"));
            });
            return null;
        });
    }

    /** 重新拉取并刷新（板块切换 / 搜索 / 翻页 / 刷新按钮共用）。 */
    public void reload(Player player, GuiHolder holder) {
        loadMarket(player, holder);
    }

    private void renderMarket(Player player, GuiHolder holder, List<MarketSymbol> symbols) {
        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < SYMBOL_SLOTS; slot++) inventory.setItem(slot, null);

        // 每页数量由 forex.symbols-per-page 决定（上限 SYMBOL_SLOTS，即 45 格容量）。
        int perPage = symbolsPerPage();
        int pageCount = Math.max(1, (int) Math.ceil(symbols.size() / (double) perPage));
        int safePage = Math.max(0, Math.min(pageCount - 1, holder.getPage()));
        int start = safePage * perPage;

        List<MarketSymbol> visible = new ArrayList<>();
        for (int i = 0; i < perPage && start + i < symbols.size(); i++) {
            MarketSymbol symbol = symbols.get(start + i);
            visible.add(symbol);
            inventory.setItem(i, buildSymbolItem(symbol, null));
        }

        ForexCategory category = holder.getCategory();
        String keyword = holder.getKeyword();
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(GuiItems.line("板块", category == null ? "全部" : category.label(),
                NamedTextColor.WHITE));
        infoLore.add(GuiItems.line("关键词",
                keyword == null || keyword.isBlank() ? "-" : keyword, NamedTextColor.AQUA));
        infoLore.add(GuiItems.line("命中", symbols.size() + " 个", NamedTextColor.WHITE));
        infoLore.add(GuiItems.line("页码", (safePage + 1) + " / " + pageCount,
                NamedTextColor.GRAY));
        infoLore.add(GuiItems.label("数据源 biquote（价格取 mid）"));
        if (symbols.isEmpty()) {
            infoLore.add(GuiItems.line("结果", "无匹配品种", NamedTextColor.RED));
            infoLore.add(GuiItems.label("休市时段 liveOnly 可能为空"));
        }
        infoLore.add(GuiItems.label("点击品种进入交易页"));

        inventory.setItem(4, GuiItems.info(Material.GOLD_INGOT,
                Component.text(marketTitle(category, keyword)).color(NamedTextColor.GOLD),
                infoLore));

        if (safePage > 0) {
            inventory.setItem(SLOT_PREV_PAGE, GuiItems.button(Material.ARROW,
                    Component.text("上一页").color(NamedTextColor.YELLOW)));
        }
        if (safePage < pageCount - 1) {
            inventory.setItem(SLOT_NEXT_PAGE, GuiItems.button(Material.ARROW,
                    Component.text("下一页").color(NamedTextColor.YELLOW)));
        }
        inventory.setItem(SLOT_REFRESH, GuiItems.button(Material.ARROW,
                Component.text("刷新").color(NamedTextColor.GREEN)));
        inventory.setItem(SLOT_SEARCH, GuiItems.button(Material.COMPASS,
                Component.text("搜索").color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.label("按代码 / 中文名模糊搜索"),
                        GuiItems.line("当前关键词",
                                keyword == null || keyword.isBlank() ? "-" : keyword,
                                NamedTextColor.WHITE),
                        GuiItems.label("等价于 /mstock forex search <关键词>"))));
        if (holder.isSearching()) {
            inventory.setItem(SLOT_CLEAR_SEARCH, GuiItems.button(Material.BARRIER,
                    Component.text("清空搜索").color(NamedTextColor.RED),
                    List.of(GuiItems.line("当前关键词", keyword, NamedTextColor.WHITE))));
        }
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.button(Material.OAK_DOOR,
                Component.text("返回板块").color(NamedTextColor.YELLOW),
                List.of(GuiItems.label("回到板块选择页"))));

        holder.page(safePage);
        GuiItems.fillEmpty(inventory, SYMBOL_SLOTS, inventory.getSize());

        loadPageQuotes(player, holder, visible);
    }

    /**
     * 每页展示的品种数量：{@code forex.symbols-per-page}，硬上限为品种区容量。
     *
     * <p>配置值超过 {@link #SYMBOL_SLOTS} 时会被夹到 45 —— 品种区只有这么多格，
     * 多出的条目无处可放，会静默丢失。配置项本身已在 {@code MainConfig} 里
     * 夹到 {@code [1,45]}，这里再夹一次是为了防住直接构造的测试 / 未来调用方。
     */
    private int symbolsPerPage() {
        var config = plugin.getMainConfig();
        int configured = config != null ? config.getForexSymbolsPerPage() : SYMBOL_SLOTS;
        return Math.max(1, Math.min(SYMBOL_SLOTS, configured));
    }

    /**
     * 异步补齐当前页每个品种的 mid 价格与日内涨跌（悬停可见）。
     *
     * <p><b>一次批量请求</b>取回整页报价。此前是逐品种各发一次请求（45 格 = 45 次），
     * 既违反 biquote "never one symbol per request" 的明确要求，也更容易撞上
     * HTTP/2 的 GOAWAY 断连 —— 那会让若干格子永久停在"加载中…"。
     */
    private void loadPageQuotes(Player player, GuiHolder holder, List<MarketSymbol> visible) {
        var service = plugin.getForexService();
        if (service == null || visible.isEmpty()) return;

        List<String> symbols = new ArrayList<>(visible.size());
        for (MarketSymbol symbol : visible) symbols.add(symbol.getName());

        service.quotes(symbols).thenAccept(quotes -> runOnPlayer(player, () -> {
            Player current = Bukkit.getPlayer(player.getUniqueId());
            if (current == null || !current.isOnline()) return;
            if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;
            for (int i = 0; i < visible.size(); i++) {
                MarketSymbol symbol = visible.get(i);
                ForexQuote quote = quotes.get(symbol.getName().toUpperCase(java.util.Locale.ROOT));
                if (quote == null) continue;
                holder.getInventory().setItem(i, buildSymbolItem(symbol, quote));
            }
        })).exceptionally(error -> {
            plugin.getLogger().warning("[MineStock] 批量获取外汇报价失败: " + error.getMessage());
            return null;
        });
    }

    private ItemStack buildSymbolItem(MarketSymbol symbol, ForexQuote quote) {
        ForexCategory category = ForexCategory.of(symbol);
        Material material = switch (symbol.getMarket()) {
            case CRYPTO -> Material.ENDER_EYE;
            case EQUITY -> Material.PAPER;
            case COMMODITY -> Material.GOLD_NUGGET;
            case INDEX -> Material.COMPARATOR;
            default -> category == ForexCategory.CRYPTO
                    ? Material.ENDER_EYE : Material.GOLD_INGOT;
        };

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.label(symbol.getDescription()));
        lore.add(GuiItems.line("板块", ForexCategory.labelOf(symbol), NamedTextColor.GRAY));
        lore.add(GuiItems.line("类型", symbol.getMarket().label(), NamedTextColor.GRAY));
        if (quote == null) {
            lore.add(GuiItems.line("中间价 mid", "加载中…", NamedTextColor.YELLOW));
        } else {
            lore.add(GuiItems.line("中间价 mid", format6(quote.getMid()), NamedTextColor.WHITE));
            if (quote.isMarketClosed()) {
                lore.add(GuiItems.line("状态", "休市 · 最后价", NamedTextColor.YELLOW));
            } else {
                boolean up = quote.getDayDiffPercent() >= 0;
                lore.add(GuiItems.line("日内涨跌",
                        (up ? "+" : "") + format2(quote.getDayDiffPercent()) + "%",
                        up ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
        }
        lore.add(Component.empty());
        lore.add(GuiItems.label("点击进入交易页"));

        NamedTextColor nameColor = quote == null
                ? NamedTextColor.AQUA
                : (quote.getDayDiffPercent() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED);
        return GuiItems.button(material,
                Component.text(symbol.getName()).color(nameColor), lore);
    }

    // ── 交易页 ────────────────────────────────────────────────────────────────

    /** 打开交易页。 */
    public void openTrade(Player player, String symbol) {
        openTrade(player, symbol, null, "", 0);
    }

    /** 打开交易页，并记住来源板块 / 关键词 / 页码，便于"返回行情"回到原处。 */
    public void openTrade(Player player, String symbol, ForexCategory category,
                          String keyword, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.FOREX_TRADE)
                .symbol(symbol)
                .quantity(1.0)
                .category(category)
                .keyword(keyword)
                .page(page);
        Inventory inventory = GuiItems.create(holder, TRADE_TITLE + " - " + symbol,
                NamedTextColor.GOLD);
        renderTrade(player, holder);
        player.openInventory(inventory);
        loadQuote(player, holder);
    }

    /** 重新渲染交易页（数量变化后调用）。 */
    public void renderTrade(Player player, GuiHolder holder) {
        Inventory inventory = holder.getInventory();
        String symbol = holder.getSymbol();
        double quantity = holder.getQuantity();

        inventory.setItem(4, GuiItems.info(Material.GOLD_INGOT,
                Component.text(symbol).color(NamedTextColor.AQUA),
                List.of(
                        GuiItems.label("价格加载中…"),
                        GuiItems.line("数量", formatQty(quantity), NamedTextColor.WHITE),
                        GuiItems.label("价格取 biquote mid"))));

        inventory.setItem(SLOT_BUY, GuiItems.button(Material.LIME_DYE,
                Component.text("买入（开多）").color(NamedTextColor.GREEN),
                List.of(
                        GuiItems.line("数量", formatQty(quantity), NamedTextColor.WHITE),
                        GuiItems.label("等价于 /mstock forex buy " + symbol + " "
                                + formatQty(quantity)),
                        GuiItems.label("权限 minestock.forex.trade"))));

        inventory.setItem(SLOT_SELL, GuiItems.button(Material.RED_DYE,
                Component.text("卖出（开空）").color(NamedTextColor.RED),
                List.of(
                        GuiItems.line("数量", formatQty(quantity), NamedTextColor.WHITE),
                        GuiItems.label("等价于 /mstock forex sell " + symbol + " "
                                + formatQty(quantity)),
                        GuiItems.label("权限 minestock.forex.trade"))));

        inventory.setItem(SLOT_QUANTITY, GuiItems.button(Material.PAPER,
                Component.text("数量 " + formatQty(quantity)).color(NamedTextColor.YELLOW),
                List.of(
                        GuiItems.label("左键 +1　右键 -1"),
                        GuiItems.label("Shift+左键 +10　Shift+右键 -10"),
                        GuiItems.label("中键 / 其他：聊天框输入数量"),
                        GuiItems.label("输入 cancel 取消"))));

        inventory.setItem(SLOT_CHART, GuiItems.button(Material.FILLED_MAP,
                Component.text("查看K线").color(NamedTextColor.LIGHT_PURPLE),
                List.of(
                        GuiItems.label("等价于 /mstock forex chart " + symbol),
                        GuiItems.label("图表输出到聊天框"))));

        inventory.setItem(SLOT_BACK_TO_MARKET, GuiItems.button(Material.ARROW,
                Component.text("返回行情").color(NamedTextColor.YELLOW),
                List.of(GuiItems.line("板块",
                        holder.getCategory() == null ? "全部" : holder.getCategory().label(),
                        NamedTextColor.GRAY))));
        inventory.setItem(GuiItems.SLOT_BACK_TO_MENU, GuiItems.backToMenu());
        GuiItems.fillEmpty(inventory, 0, inventory.getSize());
    }

    private void loadQuote(Player player, GuiHolder holder) {
        var service = plugin.getForexService();
        if (service == null) return;
        service.quote(holder.getSymbol()).thenAccept(quote ->
                runOnPlayer(player, () -> {
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    if (current == null || !current.isOnline()) return;
                    if (!holder.isBoundTo(current.getOpenInventory().getTopInventory())) return;
                    renderQuote(holder, quote);
                })).exceptionally(error -> {
            // 交易页保留"价格加载中…"占位即可，不必刷屏；批量与重试已在
            // BiquoteForexProvider 层处理，走到这里说明该品种确实暂时无报价。
            plugin.getLogger().fine("[MineStock] 获取外汇报价失败 " + holder.getSymbol()
                    + ": " + error.getMessage());
            return null;
        });
    }

    private void renderQuote(GuiHolder holder, ForexQuote quote) {
        Inventory inventory = holder.getInventory();
        double quantity = holder.getQuantity();
        boolean up = quote.getDayDiffPercent() >= 0;

        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.label(quote.getDescription()));
        lore.add(GuiItems.line("中间价 mid", format6(quote.getMid()), NamedTextColor.WHITE));
        lore.add(GuiItems.line("买价 / 卖价",
                format6(quote.getBid()) + " / " + format6(quote.getAsk()), NamedTextColor.GRAY));
        if (quote.isMarketClosed()) {
            lore.add(GuiItems.line("状态", "休市 · 最后价", NamedTextColor.YELLOW));
        } else {
            lore.add(GuiItems.line("日内涨跌",
                    (up ? "+" : "") + format2(quote.getDayDiffPercent()) + "%",
                    up ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        lore.add(GuiItems.line("日内高 / 低",
                format6(quote.getHigh()) + " / " + format6(quote.getLow()), NamedTextColor.GRAY));
        lore.add(GuiItems.line("数量", formatQty(quantity), NamedTextColor.WHITE));
        lore.add(GuiItems.line("名义价值",
                format2(quote.getMid() * quantity), NamedTextColor.WHITE));

        inventory.setItem(4, GuiItems.info(Material.GOLD_INGOT,
                Component.text(quote.getSymbol() + "  " + format6(quote.getMid()))
                        .color(up ? NamedTextColor.GREEN : NamedTextColor.RED),
                lore));
    }

    /** 在玩家线程刷新报价（也可由指令触发）。 */
    public void refreshQuote(Player player, GuiHolder holder) {
        loadQuote(player, holder);
    }

    private static String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 外汇价格量级差异很大（USDJPY ~150，BTCUSD ~100000），保留 6 位再裁剪。 */
    private static String format6(double value) {
        if (!Double.isFinite(value)) return "0.000000";
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    static String formatQty(double quantity) {
        return BigDecimal.valueOf(quantity).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private void runOnPlayer(Player player, Runnable task) {
        dev.onelili.mstock.scheduler.CompatScheduler.get().runOnEntity(plugin, player, () -> {
            if (plugin.isOperational() && player.isOnline()) task.run();
        });
    }
}
