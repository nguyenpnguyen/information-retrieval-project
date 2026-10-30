package com.hust.crawler.storage.controller;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Date;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StatsControllerTest {
    @Test void countsSizesAndNewestRecordsFirst() throws Exception {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoDatabase db = mock(MongoDatabase.class);
        when(mongo.estimatedCount(anyString())).thenAnswer(i -> switch ((String) i.getArgument(0)) {
            case "html" -> 40L; case "picture" -> 3L; case "other" -> 5L; default -> 120L; });
        when(mongo.getDb()).thenReturn(db);
        when(db.runCommand(any(Document.class))).thenReturn(new Document("dataSize", 1000).append("storageSize", 600));
        when(mongo.find(any(Query.class), eq(Document.class), anyString())).thenReturn(List.of());
        when(mongo.find(any(Query.class), eq(Document.class), eq("html"))).thenReturn(List.of(
                new Document("url", "https://hust.edu.vn/cu").append("updatedAt", new Date(1000))));
        when(mongo.find(any(Query.class), eq(Document.class), eq("picture"))).thenReturn(List.of(
                new Document("url", "https://hust.edu.vn/moi.jpg").append("updatedAt", new Date(2000))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StatsController(mongo)).build();
        mvc.perform(get("/stats").header("Origin", "null"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(jsonPath("$.counts.html").value(40))
                .andExpect(jsonPath("$.counts.graph").value(120))
                .andExpect(jsonPath("$.dataBytes").value(1000))
                .andExpect(jsonPath("$.recent[0].url").value("https://hust.edu.vn/moi.jpg"))
                .andExpect(jsonPath("$.recent[0].collection").value("picture"))
                .andExpect(jsonPath("$.recent[1].collection").value("html"));
    }

    @Test void recordReturnsStoredPictureOr404() throws Exception {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("picture"))).thenReturn(
                new Document("url", "https://hust.edu.vn/a.png").append("format", "png").append("base64", "iVBORw0KGgo="));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StatsController(mongo)).build();
        mvc.perform(get("/record").param("collection", "picture").param("url", "https://hust.edu.vn/a.png").header("Origin", "null"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(jsonPath("$.base64").value("iVBORw0KGgo="))
                .andExpect(jsonPath("$.collection").value("picture"));
        mvc.perform(get("/record").param("collection", "html").param("url", "https://hust.edu.vn/khong-co"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
        mvc.perform(get("/record").param("collection", "graph").param("url", "x"))
                .andExpect(status().isBadRequest());
    }
}
