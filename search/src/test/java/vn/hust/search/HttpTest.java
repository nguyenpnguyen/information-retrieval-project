package vn.hust.search;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HttpTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static HttpServer server;
    static String base;

    @BeforeAll
    static void up() throws Exception {
        List<Record> recs = List.of(
                new Record("https://hust.edu.vn/hoc-phi", "html", "html", "Học phí năm 2025",
                        "Thông báo mức học phí năm 2025 của Đại học Bách khoa Hà Nội. " + "chi tiết ".repeat(30), Instant.now()),
                new Record("https://hust.edu.vn/tuyen-sinh", "html", "html", "Tuyển sinh đại học",
                        "Thông tin tuyển sinh đại học chính quy. " + "hồ sơ ".repeat(30), Instant.now()));
        server = Main.start(0, new ByteBuffersDirectory(), () -> new IndexBuilder.Source() {
            public Iterable<Record> records() { return recs; }
            public Iterable<String[]> edges() { return List.of(); }
        });
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        server.stop(0);
    }

    static HttpResponse<String> send(String method, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pingAndSearch() throws Exception {
        var ping = send("GET", "/ping");
        assertEquals("*", ping.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(JSON.readTree(ping.body()).get("docs").asInt() > 0);

        var r = send("GET", "/search?q=h%E1%BB%8Dc%20ph%C3%AD");
        assertEquals(200, r.statusCode());
        JsonNode hit = JSON.readTree(r.body()).get("hits").get(0);
        assertEquals("https://hust.edu.vn/hoc-phi", hit.get("url").asText());
        assertTrue(hit.get("snippet").asText().contains("<b>"));

        assertEquals(200, send("GET", "/search?q=hoc+phi&profile=A").statusCode());
        assertEquals(200, send("GET", "/search?q=hoc+phi").statusCode());
        assertEquals(0, JSON.readTree(send("GET", "/search?q=").body()).get("hits").size());
    }

    @Test
    void badRequests() throws Exception {
        assertEquals(400, send("GET", "/search?q=a&type=bogus").statusCode());
        assertEquals(400, send("GET", "/search?q=a&profile=Z").statusCode());
        assertEquals(405, send("POST", "/search").statusCode());
        assertEquals(404, send("GET", "/nope").statusCode());
    }

    @Test
    void reindex() throws Exception {
        int docs = JSON.readTree(send("GET", "/ping").body()).get("docs").asInt();
        int code = send("POST", "/reindex").statusCode();
        assertTrue(code == 202 || code == 409);
        long end = System.currentTimeMillis() + 20_000;
        JsonNode p;
        do {
            Thread.sleep(100);
            p = JSON.readTree(send("GET", "/ping").body());
        } while (p.get("building").asBoolean() && System.currentTimeMillis() < end);
        assertFalse(p.get("building").asBoolean());
        assertEquals(docs, p.get("docs").asInt());
    }
}
