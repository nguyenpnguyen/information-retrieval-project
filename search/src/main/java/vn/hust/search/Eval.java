package vn.hust.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.FSDirectory;

/** Đánh giá 4 profile A–D bằng P@10 và nDCG@10 trên qrels gán tay. Dùng: Eval [--detail] queries.tsv qrels.tsv | Eval --pool queries.tsv qrels.tsv */
public final class Eval {
    static final int K = 10;

    /** Điểm của một profile trên một truy vấn. */
    record Score(String qid, String query, double p, double ndcg, int unlabeled) {}

    // ---- Độ đo (thuần) ----

    /** P@k: số kết quả top k có grade >= 1 chia k (thiếu thì tính là không liên quan). */
    static double precisionAt(List<Integer> grades, int k) {
        long rel = grades.stream().limit(k).filter(g -> g >= 1).count();
        return (double) rel / k;
    }

    /** nDCG@k với gain 2^g-1; IDCG lấy từ mọi grade đã gán nhãn của truy vấn. */
    static double ndcgAt(List<Integer> gradesInRankOrder, List<Integer> allLabeledGrades, int k) {
        double idcg = dcg(allLabeledGrades.stream().sorted((a, b) -> b - a).toList(), k);
        return idcg == 0 ? 0 : dcg(gradesInRankOrder, k) / idcg;
    }

    private static double dcg(List<Integer> g, int k) {
        double s = 0;
        for (int i = 0; i < Math.min(k, g.size()); i++) s += ((1 << g.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        return s;
    }

    // ---- TSV ----

    /** Đọc TSV, bỏ dòng tiêu đề, mỗi dòng đủ cols cột. Thiếu file thì rỗng. */
    static List<String[]> readTsv(Path p, int cols) throws IOException {
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        for (String l : lines.subList(Math.min(1, lines.size()), lines.size())) {
            if (l.isBlank()) continue;
            String[] f = l.split("\t", -1);
            out.add(java.util.Arrays.copyOf(f, cols));
            for (int i = f.length; i < cols; i++) out.get(out.size() - 1)[i] = "";
        }
        return out;
    }

    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    /** Gộp ứng viên [qid,grade,url,title] vào qrels cũ: giữ nguyên dòng cũ, chỉ thêm (qid,url) mới, gom theo qid. */
    static List<String[]> merge(List<String[]> existing, List<String[]> candidates) {
        Map<String, List<String[]>> by = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String[] r : existing) {
            by.computeIfAbsent(r[0], x -> new ArrayList<>()).add(r);
            seen.add(r[0] + "\t" + r[2]);
        }
        for (String[] c : candidates) {
            if (!seen.add(c[0] + "\t" + c[2])) continue;
            by.computeIfAbsent(c[0], x -> new ArrayList<>()).add(new String[] {c[0], "", c[2], clean(c[3])});
        }
        List<String[]> out = new ArrayList<>();
        by.values().forEach(out::addAll);
        return out;
    }

    static void writeQrels(Path p, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder("qid\tgrade\turl\ttitle\n");
        for (String[] r : rows) sb.append(String.join("\t", r[0], r[1], r[2], r[3])).append('\n');
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- Chế độ pool ----

    /** Chạy 4 profile, gộp top 10 vào qrels. Trả về {số dòng mới, số dòng chưa gán nhãn}. */
    static int[] pool(Searcher s, List<String[]> queries, Path qrels) throws IOException {
        List<String[]> existing = readTsv(qrels, 4);
        List<String[]> cands = new ArrayList<>();
        for (String[] q : queries)
            for (Profile p : Profile.values())
                for (Searcher.Hit h : s.search(q[1], null, null, p, false, 1, K).hits())
                    cands.add(new String[] {q[0], "", h.url(), h.title()});
        List<String[]> merged = merge(existing, cands);
        writeQrels(qrels, merged);
        return new int[] {merged.size() - existing.size(), (int) merged.stream().filter(r -> r[1].isBlank()).count()};
    }

    // ---- Chế độ đánh giá ----

    static Map<Profile, List<Score>> evaluate(Searcher s, List<String[]> queries, List<String[]> qrels) throws IOException {
        Map<String, Map<String, Integer>> labels = new LinkedHashMap<>(); // qid -> url -> grade (chỉ dòng đã gán)
        for (String[] r : qrels)
            if (!r[1].isBlank()) labels.computeIfAbsent(r[0], x -> new LinkedHashMap<>()).put(r[2], Integer.parseInt(r[1].trim()));
        Map<Profile, List<Score>> out = new LinkedHashMap<>();
        for (Profile p : Profile.values()) {
            List<Score> l = new ArrayList<>();
            for (String[] q : queries) {
                Map<String, Integer> lab = labels.getOrDefault(q[0], Map.of());
                List<Integer> ranked = new ArrayList<>();
                int unl = 0;
                for (Searcher.Hit h : s.search(q[1], null, null, p, false, 1, K).hits()) {
                    Integer g = lab.get(h.url());
                    if (g == null) unl++;
                    ranked.add(g == null ? 0 : g);
                }
                l.add(new Score(q[0], q[1], precisionAt(ranked, K), ndcgAt(ranked, List.copyOf(lab.values()), K), unl));
            }
            out.put(p, l);
        }
        return out;
    }

    static double mean(List<Score> l, java.util.function.ToDoubleFunction<Score> f) {
        return l.stream().mapToDouble(f).average().orElse(0);
    }

    static String table(Map<Profile, List<Score>> res, List<String[]> qrels, boolean detail) {
        StringBuilder sb = new StringBuilder();
        Set<String> labeled = new LinkedHashSet<>();
        for (String[] r : qrels) if (!r[1].isBlank()) labeled.add(r[0]);
        List<Score> any = res.values().iterator().next();
        long noIdcg = any.stream().filter(x -> !labeled.contains(x.qid())).count();
        sb.append(String.format(Locale.ROOT, "%-8s %-6s %-8s %s%n", "profile", "P@10", "nDCG@10", "chưa gán nhãn"));
        res.forEach((p, l) -> sb.append(String.format(Locale.ROOT, "%-8s %-6.3f %-8.3f %d%n", p, mean(l, Score::p),
                mean(l, Score::ndcg), l.stream().mapToInt(Score::unlabeled).sum())));
        sb.append(String.format(Locale.ROOT, "(%d truy vấn; %d truy vấn chưa có nhãn nào, IDCG=0 nên nDCG=0 nhưng vẫn tính vào trung bình)%n",
                any.size(), noIdcg));
        if (detail) {
            sb.append(String.format(Locale.ROOT, "%n%-5s %-36s", "qid", "query"));
            res.keySet().forEach(p -> sb.append(String.format(Locale.ROOT, " | %s P@10  nDCG", p)));
            sb.append('\n');
            for (int i = 0; i < any.size(); i++) {
                sb.append(String.format(Locale.ROOT, "%-5s %-36s", any.get(i).qid(), any.get(i).query()));
                for (List<Score> l : res.values())
                    sb.append(String.format(Locale.ROOT, " | %.3f %.3f", l.get(i).p(), l.get(i).ndcg()));
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<>(List.of(args));
        boolean pool = a.remove("--pool"), detail = a.remove("--detail");
        if (a.size() != 2) {
            System.err.println("Dùng: Eval [--detail] <queries.tsv> <qrels.tsv> | Eval --pool <queries.tsv> <qrels.tsv>");
            System.exit(2);
        }
        List<String[]> queries = readTsv(Path.of(a.get(0)), 2);
        Path qrels = Path.of(a.get(1));
        String dir = System.getenv().getOrDefault("INDEX_DIR", "/index");
        try (var mgr = new SearcherManager(FSDirectory.open(Path.of(dir)), Searcher.factory())) {
            Searcher s = new Searcher(mgr);
            if (pool) {
                int[] r = pool(s, queries, qrels);
                System.out.printf("Thêm %d dòng mới; còn %d dòng chưa gán nhãn.%n", r[0], r[1]);
            } else {
                System.out.print(table(evaluate(s, queries, readTsv(qrels, 4)), readTsv(qrels, 4), detail));
            }
        }
    }
}
