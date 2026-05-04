package com.codeleon.ai;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomChatServiceTest {

    @Test
    void buildSystemPromptInjectsExcerpts() {
        List<QdrantClient.ScoredPoint> hits = List.of(
                new QdrantClient.ScoredPoint(UUID.randomUUID(), 0.91d, Map.of(
                        "path", "main",
                        "text", "public int fibonacci(int n) { return n <= 1 ? n : fibonacci(n-1) + fibonacci(n-2); }",
                        "chunkIndex", 0
                )),
                new QdrantClient.ScoredPoint(UUID.randomUUID(), 0.87d, Map.of(
                        "path", "main",
                        "text", "public static void main(String[] args) { System.out.println(fibonacci(10)); }",
                        "chunkIndex", 1
                ))
        );

        String prompt = RoomChatService.buildSystemPrompt(hits);

        assertThat(prompt).contains("excerpt 1");
        assertThat(prompt).contains("path=main");
        assertThat(prompt).contains("score=0.91");
        assertThat(prompt).contains("fibonacci");
        assertThat(prompt).contains("excerpt 2");
        assertThat(prompt).contains("score=0.87");
    }

    @Test
    void buildSystemPromptHandlesEmptyContext() {
        String prompt = RoomChatService.buildSystemPrompt(List.of());
        assertThat(prompt).contains("No code excerpts");
    }

    @Test
    void contextPayloadTruncatesLongText() {
        String longText = "x".repeat(500);
        List<QdrantClient.ScoredPoint> hits = List.of(
                new QdrantClient.ScoredPoint(UUID.randomUUID(), 0.5d, Map.of(
                        "path", "main",
                        "text", longText,
                        "chunkIndex", 0
                ))
        );

        List<Map<String, Object>> payload = RoomChatService.toContextPayload(hits);
        String preview = (String) payload.get(0).get("preview");

        assertThat(preview).hasSize(203); // 200 + "..."
        assertThat(preview).endsWith("...");
    }

    @Test
    void streamChatPushesContextThenTokensThenDone() throws Exception {
        OllamaClient ollama = mock(OllamaClient.class);
        OllamaStreamer streamer = mock(OllamaStreamer.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        SseEmitter emitter = mock(SseEmitter.class);

        UUID roomId = UUID.randomUUID();
        when(ollama.embed("how does fibonacci work?")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(qdrant.search(any(), eq(5), any())).thenReturn(List.of(
                new QdrantClient.ScoredPoint(UUID.randomUUID(), 0.9d, Map.of(
                        "path", "main",
                        "text", "int fibonacci(int n) { ... }",
                        "chunkIndex", 0
                ))
        ));

        // Simulate the streamer emitting two tokens then completing
        when(streamer.streamChat(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("Fibonacci ");
            onToken.accept("is recursive.");
            return "Fibonacci is recursive.";
        });

        RoomChatService service = new RoomChatService(ollama, streamer, qdrant);
        ChatRequest request = new ChatRequest("how does fibonacci work?", null, null);

        service.streamChat(roomId, request, emitter);

        // Verify the emitter received: 1 context + 2 token + 1 done = 4 events
        ArgumentCaptor<SseEmitter.SseEventBuilder> events =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(events.capture());
        verify(emitter).complete();

        List<SseEmitter.SseEventBuilder> sent = events.getAllValues();
        assertThat(sent).hasSize(4);
    }

    @Test
    void streamChatPropagatesErrorEvent() throws Exception {
        OllamaClient ollama = mock(OllamaClient.class);
        OllamaStreamer streamer = mock(OllamaStreamer.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        SseEmitter emitter = mock(SseEmitter.class);

        UUID roomId = UUID.randomUUID();
        when(ollama.embed(any())).thenThrow(new IllegalStateException("Ollama unreachable"));

        RoomChatService service = new RoomChatService(ollama, streamer, qdrant);
        service.streamChat(roomId, new ChatRequest("hi", null, null), emitter);

        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(any());
    }
}
