import { Bot, ChevronDown, ChevronRight, Database, Loader2, Send, Sparkles, Trash2 } from "lucide-react";
import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useRoomChat, type ChatContextChunk } from "@/lib/chat/useRoomChat";
import { indexRoom } from "@/lib/api";
import { cn } from "@/lib/utils";

interface ChatPanelProps {
  roomId: string;
  /** Returns the current editor text (used by the "Index" button). */
  getEditorText: () => string;
}

export function ChatPanel({ roomId, getEditorText }: ChatPanelProps) {
  const chat = useRoomChat(roomId);
  const [draft, setDraft] = useState("");
  const [contextOpen, setContextOpen] = useState(false);
  const [indexing, setIndexing] = useState(false);
  const [indexInfo, setIndexInfo] = useState<string | null>(null);
  const [indexError, setIndexError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  // Auto-scroll on new messages or token arrival.
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [chat.messages, chat.streaming]);

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!draft.trim() || chat.streaming) return;
    void chat.send(draft);
    setDraft("");
  };

  const onTextareaKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      if (!chat.streaming) {
        event.currentTarget.form?.requestSubmit();
      }
    }
  };

  const onIndex = async () => {
    if (indexing) return;
    setIndexError(null);
    setIndexInfo(null);
    try {
      setIndexing(true);
      const text = getEditorText();
      if (!text.trim()) {
        setIndexError("The editor is empty.");
        return;
      }
      const result = await indexRoom(roomId, { path: "main", text });
      setIndexInfo(`Indexed ${result.chunks} chunk${result.chunks === 1 ? "" : "s"} (${result.durationMs} ms)`);
    } catch (ex) {
      setIndexError(ex instanceof Error ? ex.message : "Indexing failed");
    } finally {
      setIndexing(false);
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-medium text-zinc-200">
          <Bot className="h-4 w-4 text-cyan" />
          AI assistant
        </div>
        {chat.messages.length > 0 && (
          <button
            type="button"
            className="inline-flex items-center gap-1 text-xs text-zinc-500 hover:text-zinc-300"
            onClick={chat.clear}
            aria-label="Clear conversation"
          >
            <Trash2 className="h-3 w-3" /> clear
          </button>
        )}
      </div>

      {/* Index button + status */}
      <div className="mb-3 flex flex-col gap-1">
        <Button
          type="button"
          variant="secondary"
          onClick={() => void onIndex()}
          disabled={indexing}
          className="w-full"
        >
          {indexing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Database className="h-4 w-4" />}
          {indexing ? "Indexing..." : "Index this code"}
        </Button>
        {indexInfo && <p className="text-[11px] text-emerald-400">{indexInfo}</p>}
        {indexError && <p className="text-[11px] text-rose-400">{indexError}</p>}
      </div>

      {/* Messages */}
      <div
        ref={scrollRef}
        className="mb-3 flex-1 min-h-0 space-y-3 overflow-auto rounded-lg border border-zinc-800 bg-zinc-950 p-3"
      >
        {chat.messages.length === 0 && !chat.streaming && (
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <Sparkles className="h-6 w-6 text-zinc-600" />
            <p className="mt-2 text-xs text-zinc-500">
              Index your code, then ask the assistant about it.
            </p>
          </div>
        )}

        {chat.messages.map((msg, idx) => (
          <ChatBubble
            key={idx}
            role={msg.role}
            content={msg.content}
            isLastAssistant={
              idx === chat.messages.length - 1 && msg.role === "assistant" && chat.streaming
            }
          />
        ))}

        {chat.error && (
          <div className="rounded-md border border-rose-900 bg-rose-950/40 px-3 py-2 text-xs text-rose-300">
            {chat.error}
          </div>
        )}
      </div>

      {/* Context drawer */}
      {chat.context.length > 0 && (
        <div className="mb-3 rounded-md border border-zinc-800 bg-zinc-950">
          <button
            type="button"
            onClick={() => setContextOpen((v) => !v)}
            className="flex w-full items-center justify-between px-3 py-2 text-xs text-zinc-400 hover:text-zinc-200"
          >
            <span className="flex items-center gap-1.5">
              {contextOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
              {chat.context.length} context excerpt{chat.context.length === 1 ? "" : "s"} retrieved
            </span>
            {chat.lastStats && (
              <span className="font-mono text-[10px] text-zinc-500">
                {chat.lastStats.tokens}t · {chat.lastStats.durationMs}ms
              </span>
            )}
          </button>
          {contextOpen && (
            <ul className="space-y-2 border-t border-zinc-800 px-3 py-2">
              {chat.context.map((c, i) => (
                <ContextItem key={i} chunk={c} />
              ))}
            </ul>
          )}
        </div>
      )}

      {/* Input */}
      <form onSubmit={onSubmit} className="flex flex-col gap-2">
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onTextareaKey}
          placeholder="Ask about the indexed code..."
          rows={3}
          disabled={chat.streaming}
          className={cn(
            "resize-none rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2 text-sm text-zinc-100",
            "placeholder:text-zinc-600 focus:border-cyan focus:outline-none disabled:opacity-60",
          )}
        />
        <div className="flex items-center justify-between gap-2">
          <span className="text-[10px] text-zinc-600">Enter to send · Shift+Enter for newline</span>
          {chat.streaming ? (
            <Button type="button" variant="secondary" onClick={chat.cancel}>
              Stop
            </Button>
          ) : (
            <Button type="submit" disabled={!draft.trim()}>
              <Send className="h-4 w-4" />
              Send
            </Button>
          )}
        </div>
      </form>
    </div>
  );
}

function ChatBubble({
  role,
  content,
  isLastAssistant,
}: {
  role: "user" | "assistant";
  content: string;
  isLastAssistant: boolean;
}) {
  const isUser = role === "user";
  return (
    <div className={cn("flex flex-col gap-1", isUser ? "items-end" : "items-start")}>
      <span className="text-[10px] uppercase tracking-wide text-zinc-600">
        {isUser ? "you" : "codeleon"}
      </span>
      <div
        className={cn(
          "max-w-[85%] whitespace-pre-wrap rounded-lg px-3 py-2 text-sm leading-5",
          isUser ? "bg-signature text-white" : "border border-zinc-800 bg-zinc-900 text-zinc-100",
        )}
      >
        {content}
        {isLastAssistant && (
          <span className="ml-1 inline-block h-3 w-1.5 animate-pulse bg-cyan align-middle" />
        )}
      </div>
    </div>
  );
}

function ContextItem({ chunk }: { chunk: ChatContextChunk }) {
  return (
    <li className="rounded border border-zinc-800 bg-zinc-900/60 px-2 py-1.5">
      <div className="mb-1 flex items-center justify-between text-[10px] text-zinc-500">
        <span className="font-mono">
          {chunk.path}#chunk{chunk.chunkIndex}
        </span>
        <span className="font-mono">score {chunk.score.toFixed(2)}</span>
      </div>
      <pre className="overflow-hidden whitespace-pre-wrap font-mono text-[11px] leading-4 text-zinc-300">
        {chunk.preview}
      </pre>
    </li>
  );
}
