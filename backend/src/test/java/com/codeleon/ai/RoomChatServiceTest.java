package com.codeleon.ai;

import com.codeleon.ai.retrieval.HybridRetriever;
import com.codeleon.ai.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomChatServiceTest {

    /** Query-only request — the common case before AI-1's direct context. */
    private static ChatRequest queryOnly(String query) {
        return new ChatRequest(query, null, null, null, null, null);
    }

    private static RetrievedChunk vectorChunk(String path, String text, int chunkIndex, double rrfScore) {
        return new RetrievedChunk(path, null, null, null, null, chunkIndex, text,
                0.9d, 0.0d, rrfScore, true, false);
    }

    @Test
    void buildSystemPromptInjectsExcerpts() {
        List<RetrievedChunk> hits = List.of(
                vectorChunk("main",
                        "public int fibonacci(int n) { return n <= 1 ? n : fibonacci(n-1) + fibonacci(n-2); }",
                        0, 0.91d),
                vectorChunk("main",
                        "public static void main(String[] args) { System.out.println(fibonacci(10)); }",
                        1, 0.87d)
        );

        String prompt = RoomChatService.buildSystemPrompt(hits, queryOnly("how does fibonacci work?"));

        assertThat(prompt).contains("excerpt 1");
        assertThat(prompt).contains("path=main");
        assertThat(prompt).contains("score=0.91");
        assertThat(prompt).contains("fibonacci");
        assertThat(prompt).contains("excerpt 2");
        assertThat(prompt).contains("score=0.87");
    }

    @Test
    void buildSystemPromptHandlesEmptyContext() {
        String prompt = RoomChatService.buildSystemPrompt(List.of(), queryOnly("anything?"));
        assertThat(prompt).contains("the room appears empty");
    }

    @Test
    void buildSystemPromptInjectsActiveFileAndRunError() {
        // No RAG hits at all — the assistant should still get real context
        // from the open file and the last run's error.
        ChatRequest request = new ChatRequest(
                "why does this crash?",
                null,
                null,
                "main.py",
                "print(undefined_var)",
                "NameError: name 'undefined_var' is not defined"
        );

        String prompt = RoomChatService.buildSystemPrompt(List.of(), request);

        assertThat(prompt).contains("currently open file (main.py)");
        assertThat(prompt).contains("print(undefined_var)");
        assertThat(prompt).contains("error from the user's last run");
        assertThat(prompt).contains("NameError");
        // With a real open file present, we must NOT fall back to the
        // "room appears empty" message even though there are no RAG hits.
        assertThat(prompt).doesNotContain("the room appears empty");
    }

    @Test
    void buildSystemPromptSurfacesRetrievalProvenance() {
        // A chunk surfaced by both back-ends should advertise it so the
        // model can weight exact-identifier matches alongside semantic ones.
        RetrievedChunk hybrid = new RetrievedChunk("auth/User.java", "User.refreshToken",
                "METHOD", 12, 28, 0, "public String refreshToken() {}",
                0.7d, 4.2d, 0.05d, true, true);
        String prompt = RoomChatService.buildSystemPrompt(List.of(hybrid), queryOnly("refresh token?"));
        assertThat(prompt).contains("source=vector+bm25");
        assertThat(prompt).contains("symbol=User.refreshToken");
        assertThat(prompt).contains("lines 12-28");
    }

    @Test
    void contextPayloadTruncatesLongText() {
        String longText = "x".repeat(500);
        List<RetrievedChunk> hits = List.of(
                new RetrievedChunk("main", null, null, null, null, 0, longText,
                        0.0d, 0.0d, 0.5d, true, false)
        );

        @SuppressWarnings("unchecked")
        String preview = (String) RoomChatService.toContextPayload(hits).get(0).get("preview");

        assertThat(preview).hasSize(203); // 200 + "..."
        assertThat(preview).endsWith("...");
    }

    @Test
    void streamChatPushesContextThenTokensThenDone() throws Exception {
        OllamaStreamer streamer = mock(OllamaStreamer.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        SseEmitter emitter = mock(SseEmitter.class);

        UUID roomId = UUID.randomUUID();
        when(retriever.retrieve(eq(roomId), anyString(), anyInt(), any())).thenReturn(List.of(
                vectorChunk("main", "int fibonacci(int n) { ... }", 0, 0.9d)
        ));

        // Simulate the streamer emitting two tokens then completing
        when(streamer.streamChat(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("Fibonacci ");
            onToken.accept("is recursive.");
            return "Fibonacci is recursive.";
        });

        RoomChatService service = new RoomChatService(streamer, retriever, null);
        // null user: this test focuses on the streaming pipeline; the
        // 3-arg test constructor disables history persistence so the
        // user reference is irrelevant here.
        service.streamChat(roomId, null, queryOnly("how does fibonacci work?"), emitter);

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
        OllamaStreamer streamer = mock(OllamaStreamer.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        SseEmitter emitter = mock(SseEmitter.class);

        UUID roomId = UUID.randomUUID();
        // Retrieval failures are swallowed inside HybridRetriever — what
        // we still want to surface as an error event is a streamer crash
        // (LLM unreachable, network error mid-tokens).
        when(retriever.retrieve(any(), anyString(), anyInt(), any())).thenReturn(List.of());
        when(streamer.streamChat(any(), any())).thenThrow(new IllegalStateException("Ollama unreachable"));

        RoomChatService service = new RoomChatService(streamer, retriever, null);
        service.streamChat(roomId, null, queryOnly("hi"), emitter);

        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(any());
    }
}
