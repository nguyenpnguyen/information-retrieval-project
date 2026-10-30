package com.hust.crawler.storage.controller;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code GET /stats}: số bản ghi mỗi collection, dung lượng DB và các bản ghi mới nhất, cho trang theo dõi crawl. */
@RestController
@CrossOrigin  // trang dashboard mở từ file:// gọi sang
public class StatsController {
    private static final List<String> CONTENT = List.of("html", "picture", "other");
    private static final int RECENT = 10;
    private final MongoTemplate mongo;
    public StatsController(MongoTemplate mongo) { this.mongo = mongo; }

    @GetMapping(value = "/stats", produces = "application/json")
    public Map<String, Object> stats() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String c : CONTENT) counts.put(c, mongo.estimatedCount(c));
        counts.put("graph", mongo.estimatedCount("graph"));
        Document db = mongo.getDb().runCommand(new Document("dbStats", 1));
        List<Document> recent = new ArrayList<>();
        for (String c : CONTENT) {
            // _id (ObjectId) tăng theo lúc thêm: bản ghi mới thêm nhất, không phải mới cập nhật nhất
            Query q = new Query().with(Sort.by(Sort.Direction.DESC, "_id")).limit(RECENT);
            q.fields().include("url", "format", "title", "updatedAt").exclude("_id");
            for (Document d : mongo.find(q, Document.class, c)) recent.add(d.append("collection", c));
        }
        recent.sort(Comparator.comparing((Document d) -> d.getDate("updatedAt"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("dataBytes", db.get("dataSize"));
        out.put("storageBytes", db.get("storageSize"));
        out.put("recent", recent.subList(0, Math.min(RECENT, recent.size())));
        return out;
    }

    /** {@code GET /record?collection=&url=}: một bản ghi đã lưu (ảnh kèm base64), cho khung xem trước của dashboard. */
    @GetMapping(value = "/record", produces = "application/json")
    public ResponseEntity<Map<String, Object>> record(@RequestParam String collection, @RequestParam String url) {
        if (!CONTENT.contains(collection))
            return ResponseEntity.badRequest().body(Map.of("detail", "collection phải là html, picture hoặc other"));
        Query q = Query.query(Criteria.where("url").is(url));
        q.fields().exclude("_id");
        Document d = mongo.findOne(q, Document.class, collection);
        if (d == null) return ResponseEntity.status(404).body(Map.of("detail", "không có bản ghi " + url));
        d.append("collection", collection);
        return ResponseEntity.ok(d);
    }
}
