package vn.hust.search;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

class SearchTest {
    static List<String> tokens(Analyzer a, String text) throws Exception {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = a.tokenStream("f", Analyzers.nfc(text))) {
            CharTermAttribute t = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(t.toString());
            ts.end();
        }
        return out;
    }

    static Record rec(String url, String coll, String title, String content) {
        return new Record(url, coll, "html", title, content, Instant.now());
    }

    static String filler(int n) {
        return "lorem ipsum dolor ".repeat(n);
    }

    static Searcher index(List<Record> recs, List<String[]> edges) throws Exception {
        var dir = new ByteBuffersDirectory();
        IndexBuilder.build(new IndexBuilder.Source() {
            public Iterable<Record> records() { return recs; }
            public Iterable<String[]> edges() { return edges; }
        }, dir);
        return new Searcher(new SearcherManager(dir, Searcher.factory()));
    }

    static List<String> urls(Searcher.Result r) {
        return r.hits().stream().map(Searcher.Hit::url).toList();
    }

    static List<Record> noise() {
        List<Record> l = new ArrayList<>();
        for (int i = 0; i < 6; i++) l.add(rec("http://n.vn/" + i, "html", "tin " + i, "thông tin chung số " + i));
        return l;
    }

    @Test
    void analyzers() throws Exception {
        assertTrue(tokens(Analyzers.viBi(), "Đại học Bách khoa").contains("bách khoa"));
        assertEquals(List.of("dao", "tao"), tokens(Analyzers.viAscii(), "Đào tạo"));
    }

    @Test
    void pageRank() {
        List<String[]> e = List.of(new String[] {"A", "C"}, new String[] {"B", "C"}, new String[] {"C", "A"},
                new String[] {"D", "D"}, new String[] {"A", "C"}, new String[] {"B", "D"});
        Map<String, Double> pr = LinkGraph.pageRank(e);
        assertEquals(1.0, pr.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(pr.get("C") > pr.get("A") && pr.get("C") > pr.get("B") && pr.get("C") > pr.get("D"));
    }

    @Test
    void junk() {
        // độ lạ: từ có ở mọi trang → 0; từ chỉ một trang có → log(n)
        var w = java.util.Collections.nCopies(Junk.MIN_WORDS, "chung");
        assertEquals(0, Junk.oddity(w, Map.of("chung", 10), 10), 1e-9);
        assertEquals(Math.log(10), Junk.oddity(java.util.Collections.nCopies(Junk.MIN_WORDS, "lạ"), Map.of(), 10), 1e-9);
        assertTrue(Double.isNaN(Junk.oddity(List.of("ngắn"), Map.of(), 10)));
        String a = "Xin chào   thế giới. ".repeat(20);
        assertEquals(Junk.contentKey(a), Junk.contentKey(a.toUpperCase().replace(" ", "\n ")));
        assertNull(Junk.contentKey("ngắn"));
    }

    @Test
    void titleBeatsContent() throws Exception {
        var l = noise();
        l.add(rec("http://x.vn/c", "html", "khác", "bài viết về tuyển sinh năm nay"));
        l.add(rec("http://x.vn/t", "html", "tuyển sinh", "nội dung gì đó"));
        var r = index(l, List.of()).search("tuyển sinh", null, null, Profile.A, false, 1, 10);
        assertEquals("http://x.vn/t", r.hits().get(0).url());
    }

    @Test
    void diacritics() throws Exception {
        var l = noise();
        l.add(rec("http://x.vn/1", "html", "a", "thông báo học phí kỳ này"));
        l.add(rec("http://x.vn/2", "html", "b", "thong bao hoc phi ky nay"));
        var s = index(l, List.of());
        assertEquals(List.of("http://x.vn/2"), urls(s.search("hoc phi", null, null, Profile.A, false, 1, 10)));
        assertTrue(urls(s.search("hoc phi", null, null, Profile.B, false, 1, 10)).contains("http://x.vn/1"));
        assertTrue(urls(s.search("hoc phi", null, null, Profile.D, false, 1, 10)).contains("http://x.vn/1"));
        assertEquals("http://x.vn/1", s.search("học phí", null, null, Profile.B, false, 1, 10).hits().get(0).url());
        // total là số đếm đúng, không phải cận dưới
        var r = s.search("hoc phi", null, null, Profile.B, false, 1, 1);
        assertEquals(2, r.total());
    }

    @Test
    void rerankPhrase() throws Exception {
        var l = noise();
        l.add(rec("http://x.vn/p", "html", "thông báo", "thông báo về học bổng khuyến khích học tập năm nay " + filler(30)));
        l.add(rec("http://x.vn/s", "html", "thông báo", "khích a b c d e khuyến a b c d e bổng a b c d e học. ".repeat(4)));
        var s = index(l, List.of());
        String q = "học bổng khuyến khích";
        assertEquals("http://x.vn/s", s.search(q, null, null, Profile.B, false, 1, 10).hits().get(0).url());
        assertEquals("http://x.vn/p", s.search(q, null, null, Profile.C, false, 1, 10).hits().get(0).url());
    }

    @Test
    void valleyThreshold() {
        double[] two = new double[1060];
        for (int i = 0; i < 1000; i++) two[i] = 3.0 + (i % 50) * 0.012;      // cụm chính 3,0–3,6
        for (int i = 0; i < 60; i++) two[1000 + i] = 5.0 + (i % 6) * 0.04;  // cụm rác quanh 5,1
        double t = Junk.valley(two);
        assertTrue(t > 3.6 && t < 5.0, "ngưỡng " + t);
        double[] one = new double[1000];
        for (int i = 0; i < 1000; i++) one[i] = 3.0 + (i % 50) * 0.012;
        assertEquals(Double.POSITIVE_INFINITY, Junk.valley(one));
        double[] flipped = new double[1060];                                  // cụm phải đông hơn → không phải rác
        for (int i = 0; i < 60; i++) flipped[i] = 3.0 + (i % 6) * 0.04;
        for (int i = 0; i < 1000; i++) flipped[60 + i] = 5.0 + (i % 50) * 0.012;
        assertEquals(Double.POSITIVE_INFINITY, Junk.valley(flipped));
    }

    @Test
    void cleanProfile() throws Exception {
        var l = noise();
        String sentence = "thông báo kỳ thi tốt nghiệp áp dụng cho toàn bộ sinh viên năm cuối của trường";
        String body = (sentence + ", ").repeat(5);
        // 40 trang thường cùng từ vựng (xoay câu cho khác nội dung) → độ lạ thấp
        String[] w = sentence.split(" ");
        for (int i = 0; i < 40; i++) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < 5 * w.length; k++) b.append(w[(k + i) % w.length]).append(' ');
            l.add(rec("http://n.vn/t" + i, "html", "tin", b + "số " + i));
        }
        l.add(rec("http://x.vn/dup1", "html", "thi", body));
        l.add(rec("http://x.vn/dup2", "html", "thi", body));
        // 8 trang chèn rác tiếng Bồ Đào Nha, không có từ khoá nào → chỉ độ lạ bắt được
        for (int i = 0; i < 8; i++)
            l.add(rec("http://x.vn/odd" + i, "html", "thi", "aposte agora bônus rodadas grátis ganhe dinheiro jogos roleta ".repeat(7) + "thi " + i));
        var s = index(l, List.<String[]>of(new String[] {"http://n.vn/1", "http://x.vn/dup2"}));
        var d = urls(s.search("thi", null, null, Profile.D, false, 1, 50));
        assertTrue(d.contains("http://x.vn/dup2"));
        assertTrue(d.contains("http://n.vn/t0"), "trang thường không bị coi là rác");
        assertFalse(d.contains("http://x.vn/dup1"));
        assertTrue(d.stream().noneMatch(u -> u.startsWith("http://x.vn/odd")), d.toString());
        var c = urls(s.search("thi", null, null, Profile.C, false, 1, 50));
        assertTrue(c.containsAll(List.of("http://x.vn/dup1", "http://x.vn/dup2", "http://x.vn/odd0")));
    }

    @Test
    void collectionFilter() throws Exception {
        var l = noise();
        l.add(rec("http://x.vn/h", "html", "học phí", "a"));
        l.add(rec("http://x.vn/o", "other", "học phí", "a"));
        var r = index(l, List.of()).search("học phí", "other", null, Profile.D, false, 1, 10);
        assertEquals(List.of("http://x.vn/o"), urls(r));
    }

    @Test
    void snippetEscaped() throws Exception {
        var l = noise();
        l.add(rec("http://x.vn/s", "html", "t", "<script>alert(1)</script> học phí"));
        var r = index(l, List.of()).search("học phí", null, null, Profile.D, false, 1, 10);
        String sn = r.hits().get(0).snippet();
        assertTrue(sn.contains("&lt;script&gt;") && sn.contains("<b>"), sn);
        assertFalse(sn.contains("<script>"));
        // truy vấn không dấu vẫn tô đậm chữ có dấu trong văn bản gốc
        String folded = index(l, List.of()).search("hoc phi", null, null, Profile.D, false, 1, 10).hits().get(0).snippet();
        assertTrue(folded.contains("<b>học</b>") && folded.contains("&lt;script&gt;"), folded);
    }

    @Test
    void pagerankLifts() throws Exception {
        var l = noise();
        l.add(rec("http://x.vn/b", "html", "t", "bài về hội thảo khoa học"));
        l.add(rec("http://x.vn/a", "html", "t", "bài về hội thảo khoa học"));
        var s = index(l, List.of(new String[] {"http://n.vn/1", "http://x.vn/a"}, new String[] {"http://n.vn/2", "http://x.vn/a"}));
        assertEquals("http://x.vn/a", s.search("hội thảo", null, null, Profile.C, false, 1, 10).hits().get(0).url());
    }
}
