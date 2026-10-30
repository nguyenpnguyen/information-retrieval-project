package vn.hust.search;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalTest {
    @Test
    void precision() {
        assertEquals(0.2, Eval.precisionAt(List.of(2, 0, 1, 0, 0), 10), 1e-9);
        assertEquals(0.3, Eval.precisionAt(List.of(1, 1, 1), 10), 1e-9);
    }

    @Test
    void ndcg() {
        assertEquals(0.84726, Eval.ndcgAt(List.of(2, 0, 1), List.of(2, 1, 1), 10), 1e-4);
        assertEquals(1.0, Eval.ndcgAt(List.of(2, 1, 1), List.of(1, 2, 1), 10), 1e-9);
        assertEquals(0.0, Eval.ndcgAt(List.of(0, 0), List.of(0, 0), 10), 0);
        assertEquals(0.0, Eval.ndcgAt(List.of(0), List.of(), 10), 0);
    }

    @Test
    void merge() {
        List<String[]> old = List.of(new String[] {"q1", "2", "http://a", "A"}, new String[] {"q2", "", "http://b", "B"});
        var m = Eval.merge(old, List.of(new String[] {"q1", "", "http://a", "dup"}, new String[] {"q1", "", "http://c", "C\tx\ny"},
                new String[] {"q2", "", "http://b", "dup"}));
        assertEquals(3, m.size());
        assertArrayEquals(new String[] {"q1", "2", "http://a", "A"}, m.get(0));
        assertArrayEquals(new String[] {"q1", "", "http://c", "C x y"}, m.get(1));
        assertEquals("http://b", m.get(2)[2]);
    }

    @Test
    void endToEnd(@TempDir Path dir) throws Exception {
        var l = SearchTest.noise();
        l.add(SearchTest.rec("http://x.vn/1", "html", "học phí", "nội dung"));
        l.add(SearchTest.rec("http://x.vn/2", "html", "khác", "bài về học phí kỳ này"));
        var s = SearchTest.index(l, List.of());
        Path qf = dir.resolve("q.tsv"), rf = dir.resolve("r.tsv");
        Files.writeString(qf, "qid\tquery\nq1\thọc phí\n");
        var qs = Eval.readTsv(qf, 2);

        int[] r = Eval.pool(s, qs, rf);
        assertEquals(2, r[0]);
        assertEquals(2, r[1]);
        // gán nhãn: trang title khớp = 2, trang kia = 1
        List<String[]> rows = new ArrayList<>(Eval.readTsv(rf, 4));
        for (String[] row : rows) row[1] = row[2].endsWith("/1") ? "2" : "1";
        Eval.writeQrels(rf, rows);
        assertEquals(0, Eval.pool(s, qs, rf)[0]); // chạy lại không thêm gì, nhãn giữ nguyên
        assertEquals("2", Eval.readTsv(rf, 4).stream().filter(x -> x[2].endsWith("/1")).findFirst().get()[1]);

        var res = Eval.evaluate(s, qs, Eval.readTsv(rf, 4));
        var d = res.get(Profile.D).get(0);
        assertEquals(0.2, d.p(), 1e-9);
        assertEquals(1.0, d.ndcg(), 1e-9);
        assertEquals(0, d.unlabeled());
        String t = Eval.table(res, Eval.readTsv(rf, 4), true);
        assertTrue(t.contains("chưa gán nhãn") && t.contains("D ") && t.contains("q1"), t);
    }
}
