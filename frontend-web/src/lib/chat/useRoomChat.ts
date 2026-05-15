import { useCallback, useEffect, useRef, useState } from "react";
import { API_BASE_URL, fetchChatHistory } from "@/lib/api";
import { useAuthStore } from "@/stores/auth-store";

export type ChatRole = "user" | "assistant";

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

export interface ChatContextChunk {
  path: string;
  chunkIndex: number;
  preview: string;
  score: number;
}

export interface ChatDoneStats {
  tokens: number;
  characters: number;
  durationMs: number;
  contextChunks: number;
}

/**
 * "Live" context the editor can attach to a chat turn — the file the
 * user currently has open and the error from their last run. The RAG
 * index cannot provide these (it can be stale or miss unsaved edits),
 * so the frontend ships them directly with each question.
 */
export interface ChatSendContext {
  activeFilePath?: string;
  activeFileContent?: string;
  lastRunStderr?: string;
}

interface UseRoomChatResult {
  messages: ChatMessage[];
  context: ChatContextChunk[];
  streaming: boolean;
  /**
   * True while we're hydrating the caller's persisted conversation
   * for this room on mount. The Send button stays disabled during
   * this brief window so a fast typist can't post a turn that gets
   * stomped on by the loaded history arriving a beat later.
   */
  loadingHistory: boolean;
  error: string | null;
  lastStats: ChatDoneStats | null;
  send: (query: string, sendContext?: ChatSendContext) => Promise<void>;
  clear: () => void;
  cancel: () => void;
}

/**
 * Streams a chat reply from POST /rooms/{roomId}/chat using fetch + ReadableStream.
 * The native EventSource API cannot do POST or custom Authorization headers, so we
 * parse SSE manually here. Events expected:
 *   event:context  data:[{path,chunkIndex,preview,score}, ...]
 *   event:token    data:{"t":"..."}
 *   event:done     data:{tokens,characters,durationMs,contextChunks}
 *   event:error    data:{message}
 */
export function useRoomChat(roomId: string | undefined): UseRoomChatResult {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [context, setContext] = useState<ChatContextChunk[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastStats, setLastStats] = useState<ChatDoneStats | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Hydrate the caller's persisted thread on mount and whenever the
  // room changes. A network failure is silent (empty history is the
  // same UX as an offline backend at this stage); the user can still
  // ask new questions and they'll persist on success.
  useEffect(() => {
    if (!roomId) return;
    let cancelled = false;
    setLoadingHistory(true);
    setMessages([]);
    setContext([]);
    setError(null);
    setLastStats(null);
    fetchChatHistory(roomId)
      .then((entries) => {
        if (cancelled) return;
        setMessages(
          entries.map((entry) => ({
            role: entry.role === "USER" ? "user" : "assistant",
            content: entry.content,
          })),
        );
      })
      .catch(() => {
        // Empty history is the natural fallback — no need to surface
        // a network error here.
      })
      .finally(() => {
        if (!cancelled) setLoadingHistory(false);
      });
    return () => {
      cancelled = true;
    };
  }, [roomId]);

  const clear = useCallback(() => {
    setMessages([]);
    setContext([]);
    setError(null);
    setLastStats(null);
  }, []);

  const cancel = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setStreaming(false);
  }, []);

  const send = useCallback(
    async (query: string, sendContext?: ChatSendContext) => {
      if (!roomId || !query.trim() || streaming) return;
      setError(null);
      setContext([]);
      setLastStats(null);

      const trimmed = query.trim();
      // Capture the history before adding the new turn (last 6 messages = 3 turns).
      const history = messages.slice(-6);

      setMessages((prev) => [
        ...prev,
        { role: "user", content: trimmed },
        { role: "assistant", content: "" },
      ]);
      setStreaming(true);

      const token = useAuthStore.getState().accessToken;
      const ctrl = new AbortController();
      abortRef.current = ctrl;

      // The `done` SSE event is the authoritative "answer complete" signal.
      // Once we've seen it, the reply is fully rendered — any exception
      // afterwards is just the connection being torn down (Chrome's fetch
      // ReadableStream throws "network error" on SSE close even when the
      // stream finished cleanly). We must NOT surface that as a chat error.
      let receivedDone = false;

      try {
        const res = await fetch(`${API_BASE_URL}/rooms/${roomId}/chat`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: token ? `Bearer ${token}` : "",
            Accept: "text/event-stream",
          },
          body: JSON.stringify({
            query: trimmed,
            history,
            // Backend caps these (255 / 16000 / 4000 chars); we send them
            // raw and let it truncate rather than guessing limits here.
            activeFilePath: sendContext?.activeFilePath || undefined,
            activeFileContent: sendContext?.activeFileContent || undefined,
            lastRunStderr: sendContext?.lastRunStderr || undefined,
          }),
          signal: ctrl.signal,
        });

        if (!res.ok || !res.body) {
          throw new Error(`Chat request failed (HTTP ${res.status})`);
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          let idx;
          while ((idx = buffer.indexOf("\n\n")) >= 0) {
            const raw = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            handleSseEvent(raw);
          }
        }
        // flush trailing event without terminating \n\n (rare)
        if (buffer.trim().length > 0) handleSseEvent(buffer);
      } catch (ex) {
        // Ignore errors once the answer is complete (see receivedDone above)
        // or when the user cancelled — neither is a real failure.
        if (!ctrl.signal.aborted && !receivedDone) {
          const msg = ex instanceof Error ? ex.message : String(ex);
          setError(msg);
        }
      } finally {
        setStreaming(false);
        abortRef.current = null;
      }

      function handleSseEvent(raw: string) {
        let eventName = "message";
        const dataParts: string[] = [];
        for (const line of raw.split("\n")) {
          if (line.startsWith("event:")) {
            eventName = line.slice(6).trim();
          } else if (line.startsWith("data:")) {
            // SSE spec strips one leading space — but we wrap tokens as JSON,
            // so we rely on JSON.parse for tokens/context/done/error.
            dataParts.push(line.slice(5).replace(/^ /, ""));
          }
        }
        const data = dataParts.join("\n");
        if (!eventName || !data) return;

        if (eventName === "token") {
          try {
            const parsed = JSON.parse(data) as { t?: string };
            if (typeof parsed.t === "string") {
              appendAssistantToken(parsed.t);
            }
          } catch {
            // fallback: append as raw
            appendAssistantToken(data);
          }
        } else if (eventName === "context") {
          try {
            setContext(JSON.parse(data) as ChatContextChunk[]);
          } catch {
            // ignore malformed context
          }
        } else if (eventName === "done") {
          // Mark the turn as successfully completed BEFORE parsing stats,
          // so even a malformed done payload still suppresses the spurious
          // post-stream "network error".
          receivedDone = true;
          try {
            setLastStats(JSON.parse(data) as ChatDoneStats);
          } catch {
            // ignore
          }
        } else if (eventName === "error") {
          try {
            const parsed = JSON.parse(data) as { message?: string };
            setError(parsed.message ?? "Chat failed");
          } catch {
            setError(data);
          }
        }
      }

      function appendAssistantToken(token: string) {
        setMessages((prev) => {
          const arr = [...prev];
          const last = arr[arr.length - 1];
          if (last && last.role === "assistant") {
            arr[arr.length - 1] = { ...last, content: last.content + token };
          }
          return arr;
        });
      }
    },
    [roomId, messages, streaming],
  );

  return { messages, context, streaming, loadingHistory, error, lastStats, send, clear, cancel };
}
