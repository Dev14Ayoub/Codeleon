package com.codeleon.ai;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomFileIndexer {

    private static final Logger log = LoggerFactory.getLogger(RoomFileIndexer.class);

    static final int CHUNK_SIZE = 500;
    static final int CHUNK_OVERLAP = 50;

    private final OllamaClient ollama;
    private final QdrantClient qdrant;

    public IndexResult index(UUID roomId, String path, String text) {
        long start = System.currentTimeMillis();
        String resolvedPath = (path == null || path.isBlank()) ? "main" : path;

        qdrant.ensureCollection();
        qdrant.deleteByFilter(filterFor(roomId, resolvedPath));

        List<String> chunks = TextChunker.chunk(text, CHUNK_SIZE, CHUNK_OVERLAP);
        if (chunks.isEmpty()) {
            log.debug("Skipping index for room {} path {}: no chunks", roomId, resolvedPath);
            return new IndexResult(0, System.currentTimeMillis() - start);
        }

        List<QdrantClient.Point> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            float[] vector = ollama.embed(chunkText);
            points.add(new QdrantClient.Point(
                    deterministicId(roomId, resolvedPath, i),
                    vector,
                    Map.of(
                            "roomId", roomId.toString(),
                            "path", resolvedPath,
                            "chunkIndex", i,
                            "text", chunkText
                    )
            ));
        }

        qdrant.upsert(points);
        long duration = System.currentTimeMillis() - start;
        log.info("Indexed {} chunks for room {} path {} in {} ms", chunks.size(), roomId, resolvedPath, duration);
        return new IndexResult(chunks.size(), duration);
    }

    static UUID deterministicId(UUID roomId, String path, int chunkIndex) {
        String name = roomId + ":" + path + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> filterFor(UUID roomId, String path) {
        return Map.of("must", List.of(
                Map.of("key", "roomId", "match", Map.of("value", roomId.toString())),
                Map.of("key", "path", "match", Map.of("value", path))
        ));
    }
}
