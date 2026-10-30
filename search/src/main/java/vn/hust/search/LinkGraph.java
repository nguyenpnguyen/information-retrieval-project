package vn.hust.search;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** PageRank trên đồ thị liên kết. */
public final class LinkGraph {
    private LinkGraph() {}

    /** Mỗi cạnh là {nguồn, đích}. Bỏ self-loop và cạnh trùng; tổng điểm = 1. */
    public static Map<String, Double> pageRank(Iterable<String[]> edges) {
        Map<String, Integer> id = new HashMap<>();
        var from = new IntList();
        var to = new IntList();
        var seen = new HashSet<Long>();
        for (String[] e : edges) {
            if (e == null || e[0] == null || e[1] == null || e[0].equals(e[1])) continue;
            int a = id.computeIfAbsent(e[0], k -> id.size());
            int b = id.computeIfAbsent(e[1], k -> id.size());
            if (seen.add(((long) a << 32) | b)) {
                from.add(a);
                to.add(b);
            }
        }
        int n = id.size();
        Map<String, Double> out = new HashMap<>();
        if (n == 0) return out;
        int[] deg = new int[n];
        for (int i = 0; i < from.n; i++) deg[from.a[i]]++;
        double[] r = new double[n], nx = new double[n];
        Arrays.fill(r, 1.0 / n);
        for (int it = 0; it < 50; it++) {
            double dangling = 0;
            for (int i = 0; i < n; i++) if (deg[i] == 0) dangling += r[i];
            Arrays.fill(nx, (0.15 + 0.85 * dangling) / n);
            for (int i = 0; i < from.n; i++) nx[to.a[i]] += 0.85 * r[from.a[i]] / deg[from.a[i]];
            double diff = 0;
            for (int i = 0; i < n; i++) diff += Math.abs(nx[i] - r[i]);
            double[] t = r; r = nx; nx = t;
            if (diff < 1e-6) break;
        }
        for (var e : id.entrySet()) out.put(e.getKey(), r[e.getValue()]);
        return out;
    }

    private static final class IntList {
        int[] a = new int[1024];
        int n;

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }
    }
}
