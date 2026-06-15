package com.codeleon.ai;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.RoomFileService;
import com.codeleon.room.event.RoomEventService;
import com.codeleon.room.event.RoomEventType;
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
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/rooms/{roomId}/chat")
@RequiredArgsConstructor
public class ChatController {

    // 5 minutes — has to be longer than the Ollama HTTP client timeout
    // (240s in application.yml) so the SSE doesn't close mid-inference on
    // a slow CPU-only Ollama. The previous 90s value was the root cause
    // of the "Chat failed" toast users saw 60-90s into a long chat turn.
    private static final long EMITTER_TIMEOUT_MS = 300_000L;

    // AI chat concurrency is bounded: Ollama serves one inference at a time
    // (OLLAMA_NUM_PARALLEL=1) and a turn can block for minutes, so an unbounded
    // pool would let a burst of requests pile up threads + held connections
    // until the VM tips over. Cap it and reject cleanly over the limit.
    private static final int MAX_CONCURRENT_CHATS = 4;

    private final RoomChatService chatService;
    private final RoomFileService roomFileService;
    private final RoomEventService roomEventService;
    private final AiProperties aiProperties;
    private final Semaphore chatSlots = new Semaphore(MAX_CONCURRENT_CHATS, true);
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_CHATS);

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

        // Acquire a concurrency slot up front; fail fast if the server is
        // already at capacity rather than spawning another long-lived thread.
        if (!chatSlots.tryAcquire()) {
            throw new BadRequestException("The AI assistant is busy right now. Try again in a moment.");
        }
        boolean started = false;
        try {
            // Record the prompt in the activity feed before the answer streams
            // back. We log the event at request time (not on completion) so a
            // long or aborted stream still shows "asked the AI" in the feed.
            roomEventService.emit(roomId, user, RoomEventType.AI_ASKED);

            SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
            // Release the slot exactly once when the stream ends — completion,
            // error and timeout all funnel through onCompletion.
            emitter.onCompletion(chatSlots::release);
            CompletableFuture.runAsync(() -> chatService.streamChat(roomId, user, request, emitter), executor);
            started = true;
            return emitter;
        } finally {
            if (!started) {
                chatSlots.release();
            }
        }
    }
}
