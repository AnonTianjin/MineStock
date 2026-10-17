package dev.onelili.mstock.util;

import dev.onelili.mstock.model.MarketSymbol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 品种模糊搜索。
 *
 * <p>纯函数实现，不依赖 Bukkit，便于单元测试。
 *
 * <p>匹配范围：品种代码（{@code EURUSD}）与中文描述（{@code Euro vs US Dollar}）。
 * 支持"子序列模糊"——{@code EUU} 能匹配 {@code EURUSD}，{@code 欧元} 能匹配描述字段。
 *
 * <p>排序按相关性：完全相等 &gt; 代码前缀 &gt; 代码包含 &gt; 描述包含 &gt; 子序列。
 * 同档次内按代码字典序，保证结果稳定可复现。
 */
public final class SymbolSearch {

    private SymbolSearch() {
    }

    /**
     * 过滤并排序品种列表。
     *
     * @param symbols 候选品种（通常已按板块过滤）
     * @param keyword 关键词；{@code null} 或空白表示不过滤（返回原列表的副本）
     * @param limit   返回上限；{@code <= 0} 表示不限制
     */
    public static List<MarketSymbol> filter(List<MarketSymbol> symbols, String keyword, int limit) {
        if (symbols == null || symbols.isEmpty()) return List.of();

        String query = normalize(keyword);
        if (query.isEmpty()) {
            return limit > 0 && symbols.size() > limit
                    ? new ArrayList<>(symbols.subList(0, limit))
                    : new ArrayList<>(symbols);
        }

        List<Scored> matches = new ArrayList<>();
        for (MarketSymbol symbol : symbols) {
            int score = score(symbol, query);
            if (score > 0) matches.add(new Scored(symbol, score));
        }

        matches.sort(Comparator
                .comparingInt(Scored::score).reversed()
                .thenComparing(scored -> scored.symbol().getName()));

        List<MarketSymbol> result = new ArrayList<>(matches.size());
        for (Scored match : matches) {
            if (limit > 0 && result.size() >= limit) break;
            result.add(match.symbol());
        }
        return result;
    }

    /** 是否命中（等价于 {@code score > 0}）。 */
    public static boolean matches(MarketSymbol symbol, String keyword) {
        return symbol != null && score(symbol, normalize(keyword)) > 0;
    }

    /**
     * 相关性打分，{@code 0} 表示不匹配。
     *
     * <p>分数刻意保持量级分明，避免"描述里偶然出现"压过"代码精确匹配"。
     */
    static int score(MarketSymbol symbol, String query) {
        if (symbol == null || query.isEmpty()) return 0;

        // 多词查询必须先处理：否则含空格的输入会被下面的子序列逻辑提前命中，
        // 丢掉"每个词都要命中"的约束。
        if (query.contains(" ")) {
            List<String> tokens = tokenize(query);
            if (tokens.size() > 1) {
                int combined = 0;
                for (String token : tokens) {
                    int tokenScore = score(symbol, token);
                    if (tokenScore == 0) return 0;
                    combined += tokenScore;
                }
                return combined / tokens.size();
            }
            // 只有空白字符，视为无关键词
            if (tokens.isEmpty()) return 0;
            return score(symbol, tokens.get(0));
        }

        String code = normalize(symbol.getName());
        String description = normalize(symbol.getDescription());

        if (code.equals(query)) return 1000;
        if (code.startsWith(query)) return 800 - Math.min(200, code.length() - query.length());
        if (code.contains(query)) return 600;
        if (description.startsWith(query)) return 500;
        if (description.contains(query)) return 400;

        // 子序列模糊：查询字符按顺序出现在代码中即可
        if (isSubsequence(query, code)) return 200;
        if (isSubsequence(query, description)) return 100;

        return 0;
    }

    /** {@code query} 的字符是否按顺序出现在 {@code text} 中。 */
    static boolean isSubsequence(String query, String text) {
        if (query.isEmpty()) return false;
        int textIndex = 0;
        for (int queryIndex = 0; queryIndex < query.length(); queryIndex++) {
            char target = query.charAt(queryIndex);
            // 跳过空格，让 "b t c" 这类输入也能命中
            if (target == ' ') continue;
            boolean found = false;
            while (textIndex < text.length()) {
                if (text.charAt(textIndex++) == target) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        for (String token : query.split("\\s+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    /** 去掉首尾空白并转小写；{@code null} 归一为空串。 */
    static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private record Scored(MarketSymbol symbol, int score) {
    }
}
