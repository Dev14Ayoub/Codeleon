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

    // 15 minutes — has to be longer than the Ollama HTTP client timeout
    // (600s) AND tolerate the first-token wait when a large model is paging
    // in from disk on a constrained host: a 7B Q4 chat model on an 8 GB VM
    // can take 3-8 min before its first token on a cold start. The previous
    // 5 min value caused mid-chat "network error" toasts on those cold paths.
    private static final long EMITTER_TIMEOUT_MS = 900_000L;

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
            // Release the slot on every termination path. SseEmitter fires
            // onCompletion OR onTimeout OR onError (mutually exclusive), so
            // wiring only onCompletion leaks the permit on every timeout —
            // four timeouts and the chat would be "busy" until restart. A
            // guard makes the release idempotent in case Spring ever invokes
            // more than one of the callbacks.
            java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable releaseOnce = () -> {
                if (released.compareAndSet(false, true)) chatSlots.release();
            };
            emitter.onCompletion(releaseOnce);
            emitter.onTimeout(releaseOnce);
            emitter.onError(ex -> releaseOnce.run());
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
