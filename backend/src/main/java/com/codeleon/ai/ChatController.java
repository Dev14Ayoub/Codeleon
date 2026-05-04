package com.codeleon.ai;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.RoomFileService;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/rooms/{roomId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final long EMITTER_TIMEOUT_MS = 90_000L;

    private final RoomChatService chatService;
    private final RoomFileService roomFileService;
    private final AiProperties aiProperties;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ChatRequest request
    ) {
        if (!aiProperties.enabled()) {
            throw new BadRequestException("AI features are disabled on this server");
        }
        if (!roomFileService.canRead(roomId, user)) {
            throw new NotFoundException("Room not found");
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> chatService.streamChat(roomId, request, emitter), executor);
        return emitter;
    }
}
