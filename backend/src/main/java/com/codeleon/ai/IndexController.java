package com.codeleon.ai;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.RoomFileService;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/rooms/{roomId}/index")
@RequiredArgsConstructor
public class IndexController {

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
        for (IndexAllRequest.IndexFile file : request.files()) {
            IndexResult result = indexer.index(roomId, file.pathOrDefault(), file.text());
            totalChunks += result.chunks();
            totalDuration += result.durationMs();
        }
        return new IndexResult(totalChunks, totalDuration);
    }
}
