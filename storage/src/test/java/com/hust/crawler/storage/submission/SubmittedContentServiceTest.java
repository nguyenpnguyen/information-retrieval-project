package com.hust.crawler.storage.submission;

import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubmittedContentServiceTest {
    MongoTemplate mongo;
    SubmittedContentService service;
    SubmittedContent stored;

    @BeforeEach void setup() {
        mongo = mock(MongoTemplate.class);
        service = new SubmittedContentService(mongo);
        stored = new SubmittedContent("1", "https://h.vn/a", "html", "T", "c", null, null, List.of(), Instant.EPOCH, Instant.EPOCH);
        when(mongo.indexOps(anyString())).thenReturn(mock(IndexOperations.class));
        when(mongo.indexOps(GraphRelation.class)).thenReturn(mock(IndexOperations.class));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(SubmittedContent.class), anyString())).thenAnswer(i -> stored);
        when(mongo.upsert(any(Query.class), any(Update.class), any(Class.class))).thenReturn(mock(UpdateResult.class));
        when(mongo.upsert(any(Query.class), any(Update.class), anyString())).thenReturn(UpdateResult.unacknowledged());
    }

    @Test void forFormatMapsCollections() {
        assertEquals(ContentCollection.HTML, ContentCollection.forFormat("HTM"));
        assertEquals(ContentCollection.HTML, ContentCollection.forFormat("html"));
        assertEquals(ContentCollection.PICTURE, ContentCollection.forFormat("JPEG"));
        assertEquals(ContentCollection.PICTURE, ContentCollection.forFormat("svg"));
        assertEquals(ContentCollection.OTHER, ContentCollection.forFormat("pdf"));
        assertEquals(ContentCollection.OTHER, ContentCollection.forFormat(null));
        assertEquals("other", ContentCollection.OTHER.collectionName());
    }

    @Test void saveWithOutLinksUpsertsOnlyGraphEdges() {
        service.save("https://h.vn/a", "html", "T", "c", null, null, List.of(
                new SubmittedContent.OutLink("https://h.vn/tin-tuc2", "Thông tin"),
                new SubmittedContent.OutLink("https://h.vn/tai-lieu.PDF", "Tải")));
        verify(mongo, never()).upsert(any(Query.class), any(Update.class), anyString());
        var edges = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(2)).upsert(any(Query.class), edges.capture(), eq(GraphRelation.class));
        for (Update u : edges.getAllValues()) assertFalse(u.modifies("intro"));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), anyString());
    }
}
