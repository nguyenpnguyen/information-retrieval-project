package vn.hust.search;

import java.time.Instant;

/** Một tài liệu đọc từ Mongo; {@code collection} là html/other/picture. */
public record Record(String url, String collection, String format, String title, String content, Instant updatedAt) {}
