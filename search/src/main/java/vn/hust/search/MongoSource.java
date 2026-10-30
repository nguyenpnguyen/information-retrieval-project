package vn.hust.search;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Projections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import org.bson.Document;

/** Đọc từ Mongo; không bao giờ lấy base64 của ảnh. */
public final class MongoSource implements IndexBuilder.Source, AutoCloseable {
    private static final List<String> COLLECTIONS = List.of("html", "other", "picture");
    private final MongoClient client;
    private final String db;

    public MongoSource(String uri, String db) {
        this.client = MongoClients.create(uri);
        this.db = db;
    }

    @Override
    public Iterable<Record> records() {
        return () -> new Iterator<>() {
            int ci = -1;
            MongoCursor<Document> cur;
            Record next;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (cur == null || !cur.hasNext()) {
                        if (cur != null) cur.close();
                        if (++ci >= COLLECTIONS.size()) return false;
                        cur = client.getDatabase(db).getCollection(COLLECTIONS.get(ci))
                                .find().projection(Projections.include("url", "format", "title", "content", "updatedAt"))
                                .batchSize(500).iterator();
                        continue;
                    }
                    Document d = cur.next();
                    String url = d.getString("url");
                    if (url == null) continue;
                    var up = d.getDate("updatedAt");
                    next = new Record(url, COLLECTIONS.get(ci), d.getString("format"), d.getString("title"),
                            d.getString("content"), up == null ? null : up.toInstant());
                }
                return true;
            }

            @Override
            public Record next() {
                if (!hasNext()) throw new NoSuchElementException();
                Record r = next;
                next = null;
                return r;
            }
        };
    }

    @Override
    public Iterable<String[]> edges() {
        return () -> {
            MongoCursor<Document> cur = client.getDatabase(db).getCollection("graph").find()
                    .projection(Projections.include("source", "destinations")).batchSize(5000).iterator();
            return new Iterator<>() {
                String[] next;

                @Override
                public boolean hasNext() {
                    while (next == null && cur.hasNext()) {
                        Document d = cur.next();
                        if (d.get("source") instanceof String s && d.get("destinations") instanceof String t) next = new String[] {s, t};
                    }
                    if (next == null) cur.close();
                    return next != null;
                }

                @Override
                public String[] next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    String[] r = next;
                    next = null;
                    return r;
                }
            };
        };
    }

    @Override
    public void close() {
        client.close();
    }
}
