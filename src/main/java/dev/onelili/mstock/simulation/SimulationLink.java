package dev.onelili.mstock.simulation;

/**
 * 模拟盘联动：两只股票之间的相关系数。
 *
 * @param codeA       代码 A（归一化后 {@code codeA < codeB}）
 * @param codeB       代码 B
 * @param correlation 相关系数，夹取到 {@code [-0.999, 0.999]}
 */
public record SimulationLink(String codeA, String codeB, double correlation) {

    /** 相关系数合法区间的绝对值上界（避免 {@code sqrt(1-rho^2)} 退化与完全共线）。 */
    private static final double MAX_ABS_CORRELATION = 0.999;

    /** 归一化构造：保证 {@code codeA < codeB}，使 {@code UNIQUE(code_a, code_b)} 能真正去重。 */
    public static SimulationLink of(String codeA, String codeB, double correlation) {
        String a = normalize(codeA);
        String b = normalize(codeB);
        // 归一化顺序：字典序小的放 codeA，保证 (A,B) 与 (B,A) 落成同一行
        if (a != null && b != null && a.compareTo(b) > 0) {
            String swap = a;
            a = b;
            b = swap;
        }
        double rho = Double.isFinite(correlation) ? correlation : 0.0;
        rho = Math.max(-MAX_ABS_CORRELATION, Math.min(MAX_ABS_CORRELATION, rho));
        return new SimulationLink(a, b, rho);
    }

    /** 代码归一化：去空白 + 大写；{@code null}/空白 → {@code null}。 */
    private static String normalize(String code) {
        if (code == null) return null;
        String stripped = code.strip();
        return stripped.isEmpty() ? null : stripped.toUpperCase(java.util.Locale.ROOT);
    }
}
