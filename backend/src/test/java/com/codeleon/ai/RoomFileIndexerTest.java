package com.codeleon.ai;

import com.codeleon.ai.chunking.CodeChunkerDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
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
        when(ollama.embedDocument(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        UUID roomId = UUID.randomUUID();
        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        IndexResult result = indexer.index(roomId, "main", "hello world");

        assertThat(result.chunks()).isEqualTo(1);
        verify(qdrant).ensureCollection();
        verify(qdrant).deleteByFilter(any());
        verify(ollama, times(1)).embedDocument("hello world");

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
        when(ollama.embedDocument(any())).thenReturn(new float[]{0.0f});

        // 3600 chars → 3 windows of 1500 (overlap 150, step 1350) via the
        // fallback text chunker.
        String text = "a".repeat(3600);
        UUID roomId = UUID.randomUUID();
        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        IndexResult result = indexer.index(roomId, "main", text);

        assertThat(result.chunks()).isEqualTo(3);
        verify(ollama, times(3)).embedDocument(any());
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

    @Test
    void indexPersistsContentHashAfterUpsert() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        RoomFileIndexStateRepository state = mock(RoomFileIndexStateRepository.class);
        when(ollama.embedDocument(any())).thenReturn(new float[]{0.1f});
        when(state.findByRoomIdAndPath(any(), any())).thenReturn(Optional.empty());

        UUID room = UUID.randomUUID();
        RoomFileIndexer indexer = new RoomFileIndexer(
                ollama, qdrant, CodeChunkerDispatcher.defaultInstance(), null, null, state);
        indexer.index(room, "main", "print('hi')");

        ArgumentCaptor<RoomFileIndexState> captor = ArgumentCaptor.forClass(RoomFileIndexState.class);
        verify(state).save(captor.capture());
        assertThat(captor.getValue().getRoomId()).isEqualTo(room);
        assertThat(captor.getValue().getPath()).isEqualTo("main");
        assertThat(captor.getValue().getContentHash()).isEqualTo(RoomFileIndexer.sha256("print('hi')"));
    }

    @Test
    void emptyTextDeletesPersistentStateRow() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        RoomFileIndexStateRepository state = mock(RoomFileIndexStateRepository.class);

        UUID room = UUID.randomUUID();
        RoomFileIndexer indexer = new RoomFileIndexer(
                ollama, qdrant, CodeChunkerDispatcher.defaultInstance(), null, null, state);
        indexer.index(room, "gone", "   ");

        verify(state).deleteByRoomIdAndPath(room, "gone");
        verify(state, never()).save(any());
    }

    @Test
    void deleteRoomIndexClearsPersistentState() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        RoomFileIndexStateRepository state = mock(RoomFileIndexStateRepository.class);

        UUID room = UUID.randomUUID();
        RoomFileIndexer indexer = new RoomFileIndexer(
                ollama, qdrant, CodeChunkerDispatcher.defaultInstance(), null, null, state);
        indexer.deleteRoomIndex(room);

        verify(state).deleteByRoomId(room);
    }

    @Test
    void indexStateReturnsPathToHashMap() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        RoomFileIndexStateRepository state = mock(RoomFileIndexStateRepository.class);
        UUID room = UUID.randomUUID();
        when(state.findByRoomId(room)).thenReturn(List.of(
                RoomFileIndexState.builder().roomId(room).path("a.py").contentHash("ha").build(),
                RoomFileIndexState.builder().roomId(room).path("b.py").contentHash("hb").build()
        ));

        RoomFileIndexer indexer = new RoomFileIndexer(
                ollama, qdrant, CodeChunkerDispatcher.defaultInstance(), null, null, state);

        assertThat(indexer.indexState(room))
                .containsEntry("a.py", "ha")
                .containsEntry("b.py", "hb")
                .hasSize(2);
    }

    @Test
    void deletePathQuietlySwallowsQdrantFailure() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        doThrow(new RuntimeException("Connection refused")).when(qdrant).ensureCollection();

        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        // Must not propagate — a delete/rename cannot be blocked by Qdrant.
        indexer.deletePathQuietly(UUID.randomUUID(), "x.py");

        verify(qdrant).ensureCollection();
    }

    @Test
    void sha256MatchesKnownVector() {
        // SHA-256("abc") — pins the exact contract the frontend's
        // crypto.subtle.digest('SHA-256', ...) must reproduce so the two
        // views of "is this file already indexed" agree.
        assertThat(RoomFileIndexer.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void deleteRoomIndexQuietlySwallowsQdrantFailure() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        // Simulate Qdrant being unreachable when a room is deleted.
        doThrow(new RuntimeException("Connection refused")).when(qdrant).ensureCollection();

        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);

        // Must not propagate — deleting a room from the DB cannot be blocked
        // by an unrelated vector-store outage.
        indexer.deleteRoomIndexQuietly(UUID.randomUUID());

        verify(qdrant).ensureCollection();
    }

    @Test
    void deleteRoomIndexQuietlyDelegatesOnHappyPath() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        UUID room = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        indexer.deleteRoomIndexQuietly(room);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(qdrant).deleteByFilter(captor.capture());
        assertThat(captor.getValue()).isEqualTo(RoomFileIndexer.filterForRoom(room));
    }

    @Test
    void deleteRoomIndexDeletesEveryPathInRoom() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        UUID room = UUID.fromString("00000000-0000-0000-0000-000000000abc");

        RoomFileIndexer indexer = new RoomFileIndexer(ollama, qdrant);
        indexer.deleteRoomIndex(room);

        verify(qdrant).ensureCollection();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(qdrant).deleteByFilter(captor.capture());
        assertThat(captor.getValue()).isEqualTo(RoomFileIndexer.filterForRoom(room));
        verify(ollama, never()).embed(any());
        verify(qdrant, never()).upsert(any());
    }
}
