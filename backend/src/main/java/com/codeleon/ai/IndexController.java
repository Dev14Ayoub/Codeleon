package com.codeleon.ai;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.RoomFileService;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/rooms/{roomId}/index")
@RequiredArgsConstructor
public class IndexController {

    private static final Logger log = LoggerFactory.getLogger(IndexController.class);

    private final RoomFileIndexer indexer;
    private final RoomFileService roomFileService;
    private final AiProperties aiProperties;

    @PostMapping
    public IndexResult index(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody IndexRequest request
    ) {
        if (!aiProperties.enabled()) {
            throw new BadRequestException("AI features are disabled on this server");
        }
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        return indexer.index(roomId, request.pathOrDefault(), request.text());
    }

    /**
     * Indexes every file in the room in one call. The frontend sends the
     * whole project so RAG retrieval can draw on all files, not just the
     * tab the user happened to have open. The aggregate result sums the
     * chunk counts and total wall time across the files.
     */
    @PostMapping("/all")
    public IndexResult indexAll(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody IndexAllRequest request
    ) {
        if (!aiProperties.enabled()) {
            throw new BadRequestException("AI features are disabled on this server");
        }
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        int totalChunks = 0;
        long totalDuration = 0;
        int failed = 0;
        indexer.deleteRoomIndex(roomId);
        for (IndexAllRequest.IndexFile file : request.files()) {
            // Per-file isolation: one bad file (embed/Qdrant error, oversized
            // or pathological content) must NOT abort the whole batch and
            // leave the room unindexed. Skip it, count it, keep going.
            try {
                IndexResult result = indexer.index(roomId, file.pathOrDefault(), file.text());
                totalChunks += result.chunks();
                totalDuration += result.durationMs();
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Skipping file '{}' during bulk index of room {}: {}",
                        file.pathOrDefault(), roomId, ex.getMessage());
            }
        }
        return new IndexResult(totalChunks, totalDuration, failed);
    }

    /**
     * Returns the durable index baseline for the room — {@code path -> content
     * hash} of every file currently embedded. The frontend fetches this on
     * mount, hashes the project's current content, and re-embeds only the
     * files whose hash differs (and clears the ones that disappeared). This
     * survives refreshes, browser tabs and collaborators.
     *
     * <p>Not gated on {@code ai.enabled}: it only reads a table. When AI is
     * off the table is empty, so the caller simply gets {@code []}.
     */
    @GetMapping("/state")
    public IndexStateResponse indexState(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user
    ) {
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        Map<String, String> state = indexer.indexState(roomId);
        List<FileHash> files = state.entrySet().stream()
                .map(entry -> new FileHash(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(FileHash::path))
                .toList();
        return new IndexStateResponse(files);
    }

    public record FileHash(String path, String hash) {
    }

    public record IndexStateResponse(List<FileHash> files) {
    }
}
