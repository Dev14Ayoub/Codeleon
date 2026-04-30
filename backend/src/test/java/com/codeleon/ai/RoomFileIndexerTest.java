package com.codeleon.ai;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomFileIndexerTest {

    @Test
    void emptyTextSkipsEmbedAndUpsert() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);

        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        IndexResult result = indexer.index(UUID.randomUUID(), "main", "   ");

        assertThat(result.chunks()).isZero();
        verify(qdrant).ensureCollection();
        verify(qdrant).deleteByFilter(any());
        verify(ollama, never()).embed(any());
        verify(qdrant, never()).upsert(any());
    }

    @Test
    void indexesShortTextAsSingleChunk() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        when(ollama.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        UUID roomId = UUID.randomUUID();
        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        IndexResult result = indexer.index(roomId, "main", "hello world");

        assertThat(result.chunks()).isEqualTo(1);
        verify(qdrant).ensureCollection();
        verify(qdrant).deleteByFilter(any());
        verify(ollama, times(1)).embed("hello world");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QdrantClient.Point>> captor = ArgumentCaptor.forClass(List.class);
        verify(qdrant).upsert(captor.capture());

        List<QdrantClient.Point> points = captor.getValue();
        assertThat(points).hasSize(1);
        QdrantClient.Point p = points.get(0);
        assertThat(p.id()).isEqualTo(RoomFileIndexer.deterministicId(roomId, "main", 0));
        assertThat(p.payload()).containsEntry("roomId", roomId.toString());
        assertThat(p.payload()).containsEntry("path", "main");
        assertThat(p.payload()).containsEntry("chunkIndex", 0);
        assertThat(p.payload()).containsEntry("text", "hello world");
        assertThat(p.vector()).hasSize(3);
    }

    @Test
    void indexesLongTextAsMultipleChunks() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        when(ollama.embed(any())).thenReturn(new float[]{0.0f});

        String text = "a".repeat(1200);
        UUID roomId = UUID.randomUUID();
        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        IndexResult result = indexer.index(roomId, "main", text);

        assertThat(result.chunks()).isEqualTo(3);
        verify(ollama, times(3)).embed(any());
        verify(qdrant).upsert(anyList());
    }

    @Test
    void deterministicIdIsStableAcrossCalls() {
        UUID room = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id1 = RoomFileIndexer.deterministicId(room, "main", 0);
        UUID id2 = RoomFileIndexer.deterministicId(room, "main", 0);
        UUID id3 = RoomFileIndexer.deterministicId(room, "main", 1);

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
    }

    @Test
    void filterShapeMatchesQdrantContract() {
        UUID room = UUID.fromString("00000000-0000-0000-0000-000000000abc");
        Map<String, Object> filter = RoomFileIndexer.filterFor(room, "main");

        assertThat(filter).containsKey("must");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> must = (List<Map<String, Object>>) filter.get("must");
        assertThat(must).hasSize(2);
        assertThat(must.get(0)).containsEntry("key", "roomId");
        assertThat(must.get(1)).containsEntry("key", "path");
    }
}
