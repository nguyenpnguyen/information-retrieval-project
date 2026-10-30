package com.hust.crawler.storage.submission;

import java.util.Locale;
import java.util.Set;

public enum ContentCollection {
    HTML("html"), PICTURE("picture"), OTHER("other");

    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp");
    private final String collectionName;
    ContentCollection(String collectionName) { this.collectionName = collectionName; }
    public String collectionName() { return collectionName; }

    public static ContentCollection forFormat(String format) {
        String f = format == null ? "" : format.strip().toLowerCase(Locale.ROOT);
        if (f.equals("html") || f.equals("htm")) return HTML;
        return IMAGES.contains(f) ? PICTURE : OTHER;
    }
}
