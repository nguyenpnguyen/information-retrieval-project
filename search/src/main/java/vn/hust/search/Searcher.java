package vn.hust.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryRescorer;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.QueryBuilder;

/** Tìm kiếm hai giai đoạn: BM25 nhiều trường lấy top 100, rồi (tuỳ profile) chấm lại bằng cụm từ + pagerank. */
public final class Searcher {
    public record Hit(String url, String title, String collection, String format, String host, float score,
                      String snippet, Long updatedAt) {}

    public record Result(long total, List<Hit> hits) {}

    private static final int TOP = 100;
    private final SearcherManager mgr;
    private final Analyzer analyzer = Analyzers.perField(); // dùng chung được giữa các luồng

    public Searcher(SearcherManager mgr) {
        this.mgr = mgr;
    }

    /** SearcherFactory đặt similarity đúng cho mọi searcher mới (dùng khi tạo SearcherManager). */
    public static SearcherFactory factory() {
        return new SearcherFactory() {
            @Override
            public IndexSearcher newSearcher(org.apache.lucene.index.IndexReader r, org.apache.lucene.index.IndexReader prev) {
                IndexSearcher s = new IndexSearcher(r);
                s.setSimilarity(Analyzers.similarity());
                return s;
            }
        };
    }

    private void add(BooleanQuery.Builder b, String field, String text, float boost) {
        Query q = new QueryBuilder(analyzer).createBooleanQuery(field, text, Occur.SHOULD);
        if (q != null) b.add(new BoostQuery(q, boost), Occur.SHOULD);
    }

    public Result search(String q, String collection, String host, Profile p, boolean fresh, int page, int pageSize)
            throws IOException {
        String text = Analyzers.nfc(q == null ? "" : q).trim();
        Result empty = new Result(0, List.of());
        if (text.isEmpty()) return empty;

        BooleanQuery.Builder m = new BooleanQuery.Builder();
        add(m, "url_tokens", text, 1.5f);
        // gõ cả câu không dấu (profile B+): chỉ hỏi bản bỏ dấu, để trang có dấu và tên file không dấu ngang nhau
        boolean plainOnly = p.expand && text.equals(fold(text));
        if (plainOnly) {
            add(m, "title_ascii", text, 3f);
            add(m, "content_ascii", text, 1f);
        } else {
            add(m, "title", text, 3f);
            add(m, "content", text, 1f);
            if (p.expand) {
                add(m, "title_bi", text, 3f);
                add(m, "content_bi", text, 1.5f);
                add(m, "title_ascii", text, 2f);  // nhẹ hơn để khớp đúng dấu đứng trên
                add(m, "content_ascii", text, 0.7f);
            }
        }
        BooleanQuery matching = m.build();
        if (matching.clauses().isEmpty()) return empty;
        m = new BooleanQuery.Builder().add(matching, Occur.MUST);
        if (collection != null && !collection.isBlank()) m.add(new TermQuery(new Term("collection", collection)), Occur.FILTER);
        if (host != null && !host.isBlank()) m.add(new TermQuery(new Term("host", host.toLowerCase())), Occur.FILTER);
        if (p.clean) {
            m.add(new TermQuery(new Term("junk", "spam")), Occur.MUST_NOT);
            m.add(new TermQuery(new Term("junk", "dup")), Occur.MUST_NOT);
        }
        Query query = m.build();

        IndexSearcher s = mgr.acquire();
        try {
            TopDocs top = s.search(query, TOP);
            long total = s.count(query);  // totalHits của search() chỉ là cận dưới
            if (p.rerank && top.scoreDocs.length > 0) top = rescorer(text, fresh).rescore(s, top, top.scoreDocs.length);
            int from = (Math.max(page, 1) - 1) * pageSize;
            if (from >= top.scoreDocs.length) return new Result(total, List.of());
            ScoreDoc[] slice = java.util.Arrays.copyOfRange(top.scoreDocs, from, Math.min(from + pageSize, top.scoreDocs.length));
            TopDocs pageDocs = new TopDocs(top.totalHits, slice);

            UnifiedHighlighter hl = UnifiedHighlighter.builder(s, analyzer)
                    .withFormatter(new DefaultPassageFormatter("<b>", "</b>", "… ", true))
                    .withMaxLength(100_000)
                    .withMaxNoHighlightPassages(0)
                    .build();
            String[] snips = hl.highlight("content", query, pageDocs, 2);
            UnifiedHighlighter folded = UnifiedHighlighter.builderWithoutSearcher(analyzer)
                    .withFormatter(new DefaultPassageFormatter("<b>", "</b>", "… ", true))
                    .withMaxLength(100_000)
                    .withMaxNoHighlightPassages(0)
                    .build();

            List<Hit> hits = new ArrayList<>();
            for (int i = 0; i < slice.length; i++) {
                Document d = s.storedFields().document(slice[i].doc);
                String snip = snips[i];
                String c = d.get("content");
                if ((snip == null || snip.isBlank()) && c != null && p.expand) {
                    // truy vấn không dấu chỉ khớp content_ascii; offset của bản bỏ dấu trùng với văn bản gốc
                    Object o = folded.highlightWithoutSearcher("content_ascii", query, c, 2);
                    snip = o == null ? null : o.toString();
                }
                if (snip == null || snip.isBlank()) snip = c == null ? "" : escape(c.substring(0, Math.min(200, c.length())));
                var up = d.getField("updatedAt");
                hits.add(new Hit(d.get("url"), d.get("title"), d.get("collection"), d.get("format"), d.get("host"),
                        slice[i].score, snip, up == null ? null : up.numericValue().longValue()));
            }
            return new Result(total, hits);
        } finally {
            mgr.release(s);
        }
    }

    /** Điểm mới = điểm gốc + điểm truy vấn chấm lại (nếu khớp). */
    private QueryRescorer rescorer(String text, boolean fresh) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        QueryBuilder qb = new QueryBuilder(analyzer);
        for (String[] f : new String[][] {{"title", "2"}, {"content", "1"}}) {
            Query ph = qb.createPhraseQuery(f[0], text, 2);
            if (ph != null && !(ph instanceof TermQuery)) b.add(new BoostQuery(ph, Float.parseFloat(f[1])), Occur.SHOULD);
        }
        b.add(new BoostQuery(FeatureField.newSaturationQuery("sig", "pagerank"), PR_BOOST), Occur.SHOULD);
        if (fresh) b.add(new BoostQuery(FeatureField.newSaturationQuery("sig", "recency"), 2f), Occur.SHOULD);
        return new QueryRescorer(b.build()) {
            @Override
            protected float combine(float first, boolean matched, float second) {
                return first + (matched ? second : 0f);
            }
        };
    }

    static final float PR_BOOST = 2f;

    static String fold(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
