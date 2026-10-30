package vn.hust.search;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;

/** Dựng chỉ mục Lucene từ một {@link Source}. */
public final class IndexBuilder {
    private IndexBuilder() {}

    public interface Source {
        Iterable<Record> records();

        Iterable<String[]> edges();
    }

    /** {@code oddityThreshold}: ngưỡng độ lạ tự chọn (Infinity = không có cụm rác). */
    public record Stats(int docs, int spam, int dup, int edges, int pagerankMatched, double oddityThreshold) {}

    private static final FieldType CONTENT = new FieldType();

    static {
        CONTENT.setTokenized(true);
        CONTENT.setStored(true);
        CONTENT.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        CONTENT.freeze();
    }

    private static final float MIN = 1e-4f;

    public static Stats build(Source src, Directory dir) throws IOException {
        int edgeCount = 0;
        for (String[] ignored : src.edges()) edgeCount++;
        Map<String, Double> pr = LinkGraph.pageRank(src.edges());
        int n = Math.max(pr.size(), 1);

        // Lượt 1: df của từ trên các trang html đủ dài (cho độ lạ).
        Map<String, Integer> df = new HashMap<>();
        int htmlDocs = 0;
        for (Record r : src.records()) {
            if (!"html".equals(r.collection())) continue;
            List<String> w = Junk.words(r.title(), r.content());
            if (w.size() < Junk.MIN_WORDS) continue;
            htmlDocs++;
            for (String t : new HashSet<>(w)) df.merge(t, 1, Integer::sum);
        }

        // Lượt 2: độ lạ của từng trang html → ngưỡng; khoá nội dung để khử trùng. Chỉ giữ url và số, không giữ nội dung.
        Map<String, Double> odd = new HashMap<>();
        Map<String, String> keyOf = new HashMap<>();
        for (Record r : src.records()) {
            if ("html".equals(r.collection())) {
                double o = Junk.oddity(Junk.words(r.title(), r.content()), df, htmlDocs);
                if (!Double.isNaN(o)) odd.put(r.url(), o);
            }
            String key = Junk.contentKey(r.content());
            if (key != null) keyOf.put(r.url(), key);
        }
        double threshold = Junk.valley(odd.values().stream().mapToDouble(Double::doubleValue).toArray());
        Set<String> spamUrls = new HashSet<>();
        odd.forEach((u, o) -> { if (o > threshold) spamUrls.add(u); });

        // Với mỗi khoá nội dung giữ url có pagerank cao nhất (hoà: url ngắn hơn); trang rác không được làm bản giữ lại.
        Map<String, String> winner = new HashMap<>();
        for (var e : keyOf.entrySet()) {
            if (spamUrls.contains(e.getKey())) continue;
            winner.merge(e.getValue(), e.getKey(), (a, b) -> {
                double pa = pr.getOrDefault(a, 0.0), pb = pr.getOrDefault(b, 0.0);
                if (pa != pb) return pa > pb ? a : b;
                return b.length() < a.length() ? b : a;
            });
        }

        Instant now = Instant.now();
        int docs = 0, spam = 0, dup = 0, matched = 0;
        IndexWriterConfig cfg = new IndexWriterConfig(Analyzers.perField());
        cfg.setSimilarity(Analyzers.similarity());
        cfg.setRAMBufferSizeMB(256);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        try (IndexWriter w = new IndexWriter(dir, cfg)) {
            for (Record r : src.records()) {
                String title = Analyzers.nfc(r.title() == null ? "" : r.title());
                String content = Analyzers.nfc(r.content() == null ? "" : r.content());
                Double rank = pr.get(r.url());
                if (rank != null) matched++;
                String junk = null;
                if (spamUrls.contains(r.url())) {
                    junk = "spam";
                    spam++;
                } else {
                    String key = keyOf.get(r.url());
                    if (key != null && !r.url().equals(winner.get(key))) {
                        junk = "dup";
                        dup++;
                    }
                }
                Document d = new Document();
                d.add(new StringField("url", r.url(), Field.Store.YES));
                d.add(new StringField("host", host(r.url()), Field.Store.YES));
                d.add(new StringField("collection", r.collection(), Field.Store.YES));
                d.add(new StringField("format", r.format() == null ? "" : r.format(), Field.Store.YES));
                d.add(new TextField("title", title, Field.Store.YES));
                d.add(new Field("content", content, CONTENT));
                d.add(new TextField("title_bi", title, Field.Store.NO));
                d.add(new TextField("content_bi", content, Field.Store.NO));
                d.add(new TextField("title_ascii", title, Field.Store.NO));
                d.add(new TextField("content_ascii", content, Field.Store.NO));
                d.add(new TextField("url_tokens", Analyzers.nfc(r.url()), Field.Store.NO));
                d.add(new FeatureField("sig", "pagerank", rank == null ? MIN : (float) Math.max(rank * n, MIN)));
                double ageDays = r.updatedAt() == null ? Double.MAX_VALUE
                        : Math.max(0, Duration.between(r.updatedAt(), now).toSeconds() / 86400.0);
                d.add(new FeatureField("sig", "recency", (float) Math.max(1 / (1 + ageDays / 30), MIN)));
                if (r.updatedAt() != null) d.add(new StoredField("updatedAt", r.updatedAt().toEpochMilli()));
                if (junk != null) d.add(new StringField("junk", junk, Field.Store.YES));
                w.addDocument(d);
                docs++;
            }
            w.commit();
        }
        return new Stats(docs, spam, dup, edgeCount, matched, threshold);
    }

    static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
