# Sequence — RAG chat

How a question typed in the AI assistant panel becomes a streamed,
context-grounded answer. Covers the embed → vector search → prompt
assembly → token streaming pipeline end to end.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant CP as ChatPanel<br/>(useRoomChat hook)
    participant API as ChatController
    participant Svc as RoomChatService
    participant Streamer as OllamaStreamer<br/>(java.net.http.HttpClient)
    participant Ollama
    participant Qdrant

    User->>CP: types "how does fib() work?" + Enter
    CP->>API: POST /rooms/{id}/chat<br/>Accept: text/event-stream<br/>{ query, topK, history }
    API->>API: aiProperties.enabled() ?
    API->>API: roomFileService.canRead(roomId, user) ?
    API-->>CP: SseEmitter (90s timeout)
    API->>Svc: streamChat(roomId, request, emitter)<br/>(runs on cached executor)

    Svc->>Ollama: POST /api/embeddings<br/>{ model: nomic-embed-text, prompt: query }
    Ollama-->>Svc: { embedding: float[768] }

    Svc->>Qdrant: POST /collections/codeleon-room-files/points/search<br/>{ vector, limit: topK, filter: { roomId: {match} } }
    Qdrant-->>Svc: List<ScoredPoint><br/>(top-K chunks with payload)

    Svc-->>CP: SSE event: context<br/>data: [{path, score, preview, chunkIndex}, ...]
    CP-->>User: collapsible "context excerpts" drawer

    Svc->>Svc: buildSystemPrompt(hits)<br/>= role: system + N excerpts injected verbatim
    Svc->>Streamer: streamChat([system, ...history, user])

    Streamer->>Ollama: POST /api/chat<br/>{ model, messages, stream: true }

    loop NDJSON stream until done=true
        Ollama-->>Streamer: { message: { content: "  Fibo" }, done: false }
        Streamer->>Svc: onToken("  Fibo")
        Svc-->>CP: SSE event: token<br/>data: {"t":"  Fibo"}
        CP->>CP: append to last assistant bubble
        CP-->>User: live token in UI
    end

    Ollama-->>Streamer: { done: true }
    Streamer-->>Svc: full assembled reply (return value)

    Svc-->>CP: SSE event: done<br/>data: { tokens, characters, durationMs, contextChunks }
    Svc->>API: emitter.complete()
    CP->>CP: setStreaming(false)
```

## Notes

- **`event: context` arrives before any token.** It carries truncated
  previews of every chunk Qdrant returned plus their cosine score, so
  the UI can show a collapsible "I read these N excerpts" panel before
  the model starts answering. This is critical UX — users trust an
  answer more when they see it is grounded in their own code.
- **Tokens are wrapped as `{"t": "..."}`** instead of being emitted
  raw. SSE's "strip one leading space after the colon" rule eats real
  whitespace at token boundaries when the stream contains plain text.
  Wrapping in JSON sidesteps the ambiguity entirely.
- **Embed and search are blocking**, but the chat call uses
  `java.net.http.HttpClient`'s line-streaming
  `BodyHandlers.ofInputStream` to read NDJSON without buffering. We
  did not pull in `spring-boot-starter-webflux` — the JDK HTTP client
  is enough.
- **The success handler runs on a cached executor**, not the request
  thread. The Tomcat connection thread returns the `SseEmitter` and is
  freed immediately; the actual streaming runs in the background until
  `emitter.complete()` is called.
- **Filter-by-roomId at search time** means a user querying their own
  room never sees chunks belonging to a different room, even though
  every chunk lives in the same Qdrant collection.
