package vn.hust.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Dịch vụ tìm kiếm. Biến môi trường: {@code PORT} (7700), {@code MONGODB_URI}, {@code INDEX_DIR} (/index).
 * <ul>
 *   <li>{@code GET /search?q=&type=html|other|picture&host=&profile=A..D&fresh=1&page=&size=}</li>
 *   <li>{@code POST /reindex}: dựng lại chỉ mục ở nền (202), đang dựng thì 409.</li>
 *   <li>{@code GET /ping}: số tài liệu, lần index cuối, thống kê.</li>
 * </ul>
 * Lỗi trả {@code {"detail": "..."}}. Chưa có chỉ mục thì dựng một lần ngay khi khởi động.
 */
public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("html", "other", "picture");

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String uri = env.getOrDefault("MONGODB_URI", "mongodb://localhost:27018/HUST_2026_IT4863_TEST");
        String db = new ConnectionString(uri).getDatabase();
        MongoSource src = new MongoSource(uri, db);
        Directory dir = FSDirectory.open(Path.of(env.getOrDefault("INDEX_DIR", "/index")));
        start(Integer.parseInt(env.getOrDefault("PORT", "7700")), dir, () -> src);
    }

    private static String statsLine(IndexBuilder.Stats s, long ms) {
        return "index: docs=%d spam=%d (ngưỡng độ lạ %.2f) dup=%d edges=%d pagerank=%.1f%% trong %.1f s".formatted(
                s.docs(), s.spam(), s.oddityThreshold(), s.dup(), s.edges(),
                s.docs() == 0 ? 0.0 : 100.0 * s.pagerankMatched() / s.docs(), ms / 1000.0);
    }

    /** Dựng và chạy server; {@code port} 0 là cổng tự chọn (test). */
    static HttpServer start(int port, Directory dir, Supplier<IndexBuilder.Source> source) throws IOException {
        AtomicReference<IndexBuilder.Stats> last = new AtomicReference<>();
        AtomicReference<Instant> indexedAt = new AtomicReference<>();
        if (!DirectoryReader.indexExists(dir)) {
            long t = System.currentTimeMillis();
            IndexBuilder.Stats s = IndexBuilder.build(source.get(), dir);
            last.set(s);
            indexedAt.set(Instant.now());
            System.err.println(statsLine(s, System.currentTimeMillis() - t));
        }
        SearcherManager mgr = new SearcherManager(dir, Searcher.factory());
        Searcher searcher = new Searcher(mgr);
        AtomicBoolean building = new AtomicBoolean();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/ping", ex -> handle(ex, "GET", q -> {
            IndexSearcher s = mgr.acquire();
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("docs", s.getIndexReader().numDocs());
                m.put("indexedAt", indexedAt.get() == null ? null : indexedAt.get().toString());
                m.put("building", building.get());
                m.put("stats", last.get());
                return new Resp(200, m);
            } finally {
                mgr.release(s);
            }
        }));
        server.createContext("/search", ex -> handle(ex, "GET", q -> {
            String type = q.getOrDefault("type", "");
            if (!type.isBlank() && !TYPES.contains(type)) return err(400, "type phải là html, other hoặc picture");
            Profile p;
            try {
                p = Profile.parse(q.get("profile"));
            } catch (IllegalArgumentException e) {
                return err(400, "profile phải là A, B, C hoặc D");
            }
            int page = num(q.get("page"), 1), size = Math.max(1, Math.min(50, num(q.get("size"), 10)));
            String fresh = q.getOrDefault("fresh", "");
            long t = System.nanoTime();
            Searcher.Result r = searcher.search(q.get("q"), type, q.get("host"), p,
                    fresh.equals("1") || fresh.equalsIgnoreCase("true"), Math.max(page, 1), size);
            long ms = (System.nanoTime() - t) / 1_000_000;
            System.err.printf("search profile=%s q=\"%s\" total=%d tookMs=%d%n", p, q.getOrDefault("q", ""), r.total(), ms);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("q", q.getOrDefault("q", ""));
            m.put("profile", p.name());
            m.put("total", r.total());
            m.put("tookMs", ms);
            m.put("page", Math.max(page, 1));
            m.put("hits", r.hits());
            return new Resp(200, m);
        }));
        server.createContext("/reindex", ex -> handle(ex, "POST", q -> {
            if (!building.compareAndSet(false, true)) return new Resp(409, Map.of("status", "đang index"));
            Thread.startVirtualThread(() -> {
                try {
                    long t = System.currentTimeMillis();
                    IndexBuilder.Stats s = IndexBuilder.build(source.get(), dir);
                    mgr.maybeRefresh();
                    last.set(s);
                    indexedAt.set(Instant.now());
                    System.err.println(statsLine(s, System.currentTimeMillis() - t));
                } catch (Exception e) {
                    System.err.println("reindex lỗi: " + e);
                } finally {
                    building.set(false);
                }
            });
            return new Resp(202, Map.of("status", "đã nhận"));
        }));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.err.println("search-service listening on: " + server.getAddress().getPort());
        return server;
    }

    record Resp(int status, Object body) {}

    interface Handler {
        Resp run(Map<String, String> query) throws Exception;
    }

    private static Resp err(int status, String detail) {
        return new Resp(status, Map.of("detail", detail));
    }

    private static int num(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Kiểm tra method, chạy handler, ghi JSON UTF-8 kèm CORS. */
    private static void handle(HttpExchange ex, String method, Handler h) throws IOException {
        Resp r;
        try {
            r = ex.getRequestMethod().equals(method) ? h.run(query(ex)) : err(405, "chỉ nhận " + method);
        } catch (Exception e) {
            System.err.println("lỗi " + ex.getRequestURI() + ": " + e);
            r = err(500, "lỗi nội bộ");
        }
        byte[] out = JSON.writeValueAsBytes(r.body());
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(r.status(), out.length);
        try (var os = ex.getResponseBody()) {
            os.write(out);
        }
    }
}
