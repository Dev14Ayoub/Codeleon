package com.codeleon.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mirror of the file content that has been indexed into the AI
 * pipeline. The backend cannot extract individual file text from the Y.Doc
 * snapshot byte blob without a server-side Yjs decoder, so we capture the
 * text at indexing time — the only path where the frontend already ships
 * file content to the backend — and serve agent tools (read_file,
 * list_files) from this mirror.
 *
 * <p>Lifecycle matches the indexer:
 * <ul>
 *   <li>{@code put} on every successful {@code RoomFileIndexer#index} call</li>
 *   <li>{@code deletePath} when a file is re-indexed empty (rare)</li>
 *   <li>{@code deleteRoom} when the room's index is wiped</li>
 * </ul>
 *
 * <p>State is process-local — if the backend restarts the store is empty
 * until the next index call. That is acceptable because the frontend
 * re-indexes before every chat turn, so the agent has a fresh view
 * within seconds of any restart.
 */
@Component
public class RoomFileSnapshotStore {

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>> rooms = new ConcurrentHashMap<>();

    public void put(UUID roomId, String path, String text) {
        if (text == null || text.isEmpty()) {
            deletePath(roomId, path);
            return;
        }
        rooms.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(path, text);
    }

    public String get(UUID roomId, String path) {
        Map<String, String> files = rooms.get(roomId);
        return files == null ? null : files.get(path);
    }

    public List<String> listPaths(UUID roomId) {
        Map<String, String> files = rooms.get(roomId);
        if (files == null) return List.of();
        List<String> paths = new ArrayList<>(files.keySet());
        paths.sort(String::compareTo);
        return paths;
    }

    public int fileCount(UUID roomId) {
        Map<String, String> files = rooms.get(roomId);
        return files == null ? 0 : files.size();
    }

    public void deletePath(UUID roomId, String path) {
        Map<String, String> files = rooms.get(roomId);
        if (files != null) files.remove(path);
    }

    public void deleteRoom(UUID roomId) {
        rooms.remove(roomId);
    }
}
