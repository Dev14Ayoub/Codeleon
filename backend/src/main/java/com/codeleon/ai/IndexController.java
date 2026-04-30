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
}
