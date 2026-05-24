import { AlertTriangle, ArrowLeft, Bot, CheckCircle2, ChevronDown, ChevronRight, CircleDashed, Database, Eye, Loader2, Send, Sparkles, Trash2, Users, Wrench, Zap } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { useRoomChat, type ChatContextChunk, type ChatMessage, type ToolCallEntry } from "@/lib/chat/useRoomChat";
import {
  fetchChatHistory,
  fetchChatThreads,
  getApiErrorMessage,
  indexRoomAll,
  type IndexFile,
} from "@/lib/api";
import { cn } from "@/lib/utils";

interface ChatPanelProps {
  roomId: string;
  /** Returns the current editor text — sent as live context with each chat turn. */
  getEditorText: () => string;
  /** Snapshots every file in the project — used to index the whole room for RAG. */
  getAllFiles: () => IndexFile[];
  /** Path of the file the user currently has open, attached to each chat turn. */
  activeFilePath: string | null;
  /** stderr of the most recent Run, if any — lets the assistant debug the actual error. */
  lastRunStderr: string | null;
  /** Whether the caller is the room owner — drives the privacy disclosure. */
  isOwner: boolean;
}

/** Cheap change-detection key for a project snapshot. */
function signatureOf(files: IndexFile[]): string {
  return JSON.stringify(files.map((file) => [file.path, file.text]));
}

const MAX_INDEX_FILES = 1_000;
const MAX_INDEX_TEXT_CHARS = 200_000;
type IndexStatus = "idle" | "indexing" | "indexed" | "failed" | "blocked" | "empty";

export function ChatPanel({ roomId, getEditorText, getAllFiles, activeFilePath, lastRunStderr, isOwner }: ChatPanelProps) {
  const chat = useRoomChat(roomId);
  const [draft, setDraft] = useState("");
  const [contextOpen, setContextOpen] = useState(false);
  const [indexing, setIndexing] = useState(false);
  const [indexStatus, setIndexStatus] = useState<IndexStatus>("idle");
  const [indexInfo, setIndexInfo] = useState<string | null>(null);
  const [indexError, setIndexError] = useState<string | null>(null);
  // Agent mode is opt-in: it routes each turn through the tool-using
  // loop instead of the one-shot RAG pipeline. Default off so existing
  // muscle memory is preserved.
  const [agentMode, setAgentMode] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  // Signature of the project the last successful index covered. Lets us
  // skip re-indexing on a chat send when nothing changed since last time.
  const lastIndexedSignatureRef = useRef<string>("");

  // Owner-only review state: when non-null, the panel renders the
  // chosen member's thread in read-only mode instead of the caller's
  // own conversation. Always null for non-owners; the dropdown that
  // sets it is gated by isOwner so an invited member can never end up
  // in this state.
  const [reviewingUserId, setReviewingUserId] = useState<string | null>(null);
  const [reviewMessages, setReviewMessages] = useState<ChatMessage[]>([]);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [reviewUserName, setReviewUserName] = useState<string>("");

  // Threads picker (owner-only). Refreshed when the panel mounts and
  // whenever the chat-history query key changes; we deliberately do not
  // refetch per token to keep the list stable while typing.
  const threadsQuery = useQuery({
    queryKey: ["chat-threads", roomId],
    queryFn: () => fetchChatThreads(roomId),
    enabled: isOwner,
    staleTime: 30_000,
  });

  // Fetch the foreign thread whenever the owner picks a different
  // member. Clearing the selection (reviewingUserId=null) goes back to
  // the streaming chat — managed by useRoomChat — so we only need to
  // load when the id is set.
  useEffect(() => {
    if (!reviewingUserId) return;
    let cancelled = false;
    setReviewLoading(true);
    fetchChatHistory(roomId, reviewingUserId)
      .then((entries) => {
        if (cancelled) return;
        setReviewMessages(
          entries.map((entry) => ({
            role: entry.role === "USER" ? "user" : "assistant",
            content: entry.content,
          })),
        );
      })
      .catch(() => {
        if (!cancelled) setReviewMessages([]);
      })
      .finally(() => {
        if (!cancelled) setReviewLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [reviewingUserId, roomId]);

  // Auto-scroll on new messages or token arrival — including the
  // moment a reviewed thread finishes loading so the owner lands at
  // the latest message rather than the top.
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [chat.messages, chat.streaming, reviewMessages]);

  const isReviewing = reviewingUserId !== null;
  const visibleMessages = isReviewing ? reviewMessages : chat.messages;

  /**
   * Indexes every file in the project. With force=false it is a no-op
   * when nothing changed since the last index (the chat-send path), so a
   * back-and-forth conversation does not re-embed the project every turn.
   * With force=true (the button) it always re-indexes.
   */
  const indexProject = async (force: boolean): Promise<void> => {
    if (indexing) return;
    const files = getAllFiles();
    if (files.length === 0) {
      if (force) {
        setIndexStatus("empty");
        setIndexError("No files to index yet — write some code first.");
      }
      return;
    }
    if (files.length > MAX_INDEX_FILES) {
      setIndexStatus("blocked");
      setIndexError(
        `Indexing supports up to ${MAX_INDEX_FILES} files; this room has ${files.length}. Remove generated/vendor files or split the project before indexing.`,
      );
      return;
    }
    const oversized = files.find((file) => file.text.length > MAX_INDEX_TEXT_CHARS);
    if (oversized) {
      setIndexStatus("blocked");
      setIndexError(
        `Cannot index ${oversized.path}: file is ${oversized.text.length.toLocaleString()} characters, above the ${MAX_INDEX_TEXT_CHARS.toLocaleString()} character limit.`,
      );
      return;
    }
    const signature = signatureOf(files);
    if (!force && signature === lastIndexedSignatureRef.current) return;

    setIndexing(true);
    setIndexStatus("indexing");
    setIndexError(null);
    setIndexInfo(null);
    try {
      const result = await indexRoomAll(roomId, files);
      lastIndexedSignatureRef.current = signature;
      setIndexStatus("indexed");
      setIndexInfo(
        `Indexed ${files.length} file${files.length === 1 ? "" : "s"} · ` +
          `${result.chunks} chunk${result.chunks === 1 ? "" : "s"} (${result.durationMs} ms)`,
      );
    } catch (ex) {
      setIndexStatus("failed");
      setIndexError(getApiErrorMessage(ex, "Indexing failed"));
    } finally {
      setIndexing(false);
    }
  };

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!draft.trim() || chat.streaming || indexing || chat.loadingHistory) return;
    const query = draft;
    setDraft("");
    // Make sure the RAG index reflects the current project before asking —
    // a no-op if nothing changed since the last index.
    await indexProject(false);
    // Attach the live editor context: the open file + its content, and
    // the last run's error. This is what the RAG index can't give us —
    // it lets the assistant answer about unsaved edits and run failures.
    const editorText = getEditorText();
    void chat.send(query, {
      activeFilePath: activeFilePath ?? undefined,
      activeFileContent: editorText.trim() ? editorText : undefined,
      lastRunStderr: lastRunStderr ?? undefined,
      mode: agentMode ? "agent" : "chat",
    });
  };

  const onTextareaKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      if (!chat.streaming && !indexing && !chat.loadingHistory) {
        event.currentTarget.form?.requestSubmit();
      }
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-medium text-zinc-200">
          <Bot className="h-4 w-4 text-cyan" />
          AI assistant
        </div>
        <div className="flex items-center gap-3">
          {/* Agent mode toggle: routes turns through the tool-using loop.
              Hidden when the panel is in read-only review mode below. */}
          <label
            className={cn(
              "inline-flex cursor-pointer items-center gap-1.5 text-[11px]",
              agentMode ? "text-cyan" : "text-zinc-500 hover:text-zinc-300",
            )}
            title="When on, the assistant uses tools (search, read_file, list_files) to ground its answer."
          >
            <input
              type="checkbox"
              className="sr-only"
              checked={agentMode}
              onChange={(e) => setAgentMode(e.target.checked)}
            />
            <Zap className={cn("h-3.5 w-3.5", agentMode ? "fill-cyan text-cyan" : "")} />
            agent
          </label>
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
      </div>

      {/* Privacy disclosure — invited members must know the room owner
          can read their conversation. The owner sees nothing here. */}
      {!isOwner && (
        <p className="mb-3 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-1.5 text-[11px] text-zinc-500">
          The room owner can read this conversation.
        </p>
      )}

      {/* Owner-only thread picker: review any member's chat read-only. */}
      {isOwner && (threadsQuery.data?.length ?? 0) > 0 && (
        <div className="mb-3 flex items-center gap-2">
          <Users className="h-3.5 w-3.5 text-zinc-500" />
          <label htmlFor="thread-picker" className="text-[11px] text-zinc-500">
            Viewing
          </label>
          <select
            id="thread-picker"
            value={reviewingUserId ?? ""}
            onChange={(e) => {
              const id = e.target.value || null;
              setReviewingUserId(id);
              const match = threadsQuery.data!.find((t) => t.userId === id);
              setReviewUserName(match?.userName ?? "");
            }}
            className="flex-1 rounded-md border border-zinc-800 bg-zinc-950 px-2 py-1 text-xs text-zinc-200 focus:border-cyan focus:outline-none"
          >
            <option value="">My chat</option>
            {threadsQuery.data!.map((t) => (
              <option key={t.userId} value={t.userId}>
                {t.userName} · {t.messageCount} msg{t.messageCount === 1 ? "" : "s"}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Index button + status. Indexing also runs automatically before a
          chat send when the project changed — the button is for an
          explicit refresh. */}
      <div className="mb-3 flex flex-col gap-1">
        <IndexStatusLine status={indexStatus} />
        <Button
          type="button"
          variant="secondary"
          onClick={() => void indexProject(true)}
          disabled={indexing}
          className="w-full"
        >
          {indexing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Database className="h-4 w-4" />}
          {indexing ? "Indexing project..." : "Index project"}
        </Button>
        {indexInfo && <p className="text-[11px] text-emerald-400">{indexInfo}</p>}
        {indexError && <p className="text-[11px] text-rose-400">{indexError}</p>}
      </div>

      {/* Messages */}
      <div
        ref={scrollRef}
        className="mb-3 flex-1 min-h-0 space-y-3 overflow-auto rounded-lg border border-zinc-800 bg-zinc-950 p-3"
      >
        {/* Loading state — branches on which thread is being shown. */}
        {(isReviewing ? reviewLoading : chat.loadingHistory) && (
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <Loader2 className="h-5 w-5 animate-spin text-zinc-600" />
            <p className="mt-2 text-xs text-zinc-500">
              {isReviewing ? `Loading ${reviewUserName}'s chat...` : "Loading your previous conversation..."}
            </p>
          </div>
        )}

        {/* Empty-state copy — different message for review vs own chat. */}
        {!chat.loadingHistory && !reviewLoading && visibleMessages.length === 0 && !chat.streaming && (
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <Sparkles className="h-6 w-6 text-zinc-600" />
            <p className="mt-2 text-xs text-zinc-500">
              {isReviewing
                ? `${reviewUserName} has no messages in this room yet.`
                : "Ask about your open file or your last run's error — the assistant sees both. The whole project is indexed automatically before each question."}
            </p>
          </div>
        )}

        <AnimatePresence initial={false}>
          {visibleMessages.map((msg, idx) => (
            <ChatBubble
              key={`${idx}-${msg.role}`}
              role={msg.role}
              content={msg.content}
              isLastAssistant={
                !isReviewing && idx === visibleMessages.length - 1 && msg.role === "assistant" && chat.streaming
              }
            />
          ))}
        </AnimatePresence>

        {/* Agent-mode tool trace — rendered between the user's question
            and the eventual assistant answer, in the order calls arrived. */}
        {!isReviewing && chat.toolCalls.length > 0 && (
          <div className="space-y-1.5">
            {chat.toolCalls.map((tc) => (
              <ToolCallBubble key={tc.id} call={tc} />
            ))}
          </div>
        )}

        {chat.error && !isReviewing && (
          <div className="rounded-md border border-rose-900 bg-rose-950/40 px-3 py-2 text-xs text-rose-300">
            {chat.error}
          </div>
        )}
      </div>

      {/* Context drawer */}
      {!isReviewing && chat.context.length > 0 && (
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

      {/* Read-only banner when the owner is reviewing another member's
          chat. The input form below is hidden in this mode — the owner
          cannot post into someone else's thread, only read it. */}
      {isReviewing && (
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          className="flex items-center justify-between gap-2 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2"
        >
          <span className="inline-flex items-center gap-1.5 text-xs text-zinc-400">
            <Eye className="h-3.5 w-3.5 text-cyan" />
            Read-only · viewing <span className="font-medium text-zinc-200">{reviewUserName}</span>
          </span>
          <Button
            type="button"
            variant="secondary"
            onClick={() => {
              setReviewingUserId(null);
              setReviewUserName("");
              setReviewMessages([]);
            }}
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            Back to my chat
          </Button>
        </motion.div>
      )}

      {/* Input */}
      {!isReviewing && (
      <form onSubmit={onSubmit} className="flex flex-col gap-2">
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onTextareaKey}
          placeholder="Ask about your code, or paste an error..."
          rows={3}
          disabled={chat.streaming || indexing || chat.loadingHistory}
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
            <Button type="submit" disabled={!draft.trim() || indexing || chat.loadingHistory}>
              {indexing || chat.loadingHistory ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Send className="h-4 w-4" />
              )}
              {chat.loadingHistory ? "Loading..." : indexing ? "Indexing..." : "Send"}
            </Button>
          )}
        </div>
      </form>
      )}
    </div>
  );
}

function IndexStatusLine({ status }: { status: IndexStatus }) {
  const copy: Record<IndexStatus, { icon: JSX.Element; text: string; className: string }> = {
    idle: {
      icon: <CircleDashed className="h-3.5 w-3.5" />,
      text: "Project index is ready to refresh",
      className: "border-zinc-800 bg-zinc-950 text-zinc-500",
    },
    indexing: {
      icon: <Loader2 className="h-3.5 w-3.5 animate-spin" />,
      text: "Indexing project for private AI context",
      className: "border-cyan/40 bg-cyan/10 text-cyan",
    },
    indexed: {
      icon: <CheckCircle2 className="h-3.5 w-3.5" />,
      text: "Indexed and ready for grounded answers",
      className: "border-emerald-800 bg-emerald-950/40 text-emerald-300",
    },
    failed: {
      icon: <AlertTriangle className="h-3.5 w-3.5" />,
      text: "Indexing failed",
      className: "border-rose-900 bg-rose-950/40 text-rose-300",
    },
    blocked: {
      icon: <AlertTriangle className="h-3.5 w-3.5" />,
      text: "Indexing blocked by project limits",
      className: "border-amber-900 bg-amber-950/40 text-amber-300",
    },
    empty: {
      icon: <CircleDashed className="h-3.5 w-3.5" />,
      text: "No files available to index",
      className: "border-zinc-800 bg-zinc-950 text-zinc-500",
    },
  };
  const item = copy[status];
  return (
    <p className={cn("inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-[11px]", item.className)}>
      {item.icon}
      {item.text}
    </p>
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
    <motion.div
      layout
      initial={{ opacity: 0, y: 10, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: -6, scale: 0.98 }}
      transition={{ duration: 0.18 }}
      className={cn("flex flex-col gap-1", isUser ? "items-end" : "items-start")}
    >
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
    </motion.div>
  );
}

/**
 * Inline trace of one agent tool call. Renders the function name + a
 * one-line summary of the arguments, with a status icon that flips from
 * spinner → check (or alert) when the {@code tool_result} event arrives.
 * Expanding the bubble shows the full arguments + the tool's returned
 * text so a curious user (or a jury) can audit what the agent actually saw.
 */
function ToolCallBubble({ call }: { call: ToolCallEntry }) {
  const [open, setOpen] = useState(false);
  const isError = call.status === "error";
  const isRunning = call.status === "running";
  const argPreview = formatArgsPreview(call.arguments);
  return (
    <motion.div
      initial={{ opacity: 0, y: 6, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      className={cn(
        "rounded-md border px-2.5 py-1.5 text-[11px] font-mono",
        isError
          ? "border-rose-900 bg-rose-950/30 text-rose-300"
          : isRunning
            ? "border-cyan/40 bg-cyan/5 text-cyan"
            : "border-zinc-800 bg-zinc-950 text-zinc-400",
      )}
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-2 text-left"
      >
        <span className="flex min-w-0 items-center gap-1.5 truncate">
          {isRunning ? (
            <Loader2 className="h-3 w-3 shrink-0 animate-spin" />
          ) : isError ? (
            <AlertTriangle className="h-3 w-3 shrink-0" />
          ) : (
            <Wrench className="h-3 w-3 shrink-0" />
          )}
          <span className="font-semibold">{call.name}</span>
          {argPreview && <span className="truncate text-zinc-500">({argPreview})</span>}
        </span>
        {open ? <ChevronDown className="h-3 w-3 shrink-0" /> : <ChevronRight className="h-3 w-3 shrink-0" />}
      </button>
      {open && (
        <div className="mt-2 space-y-1.5 border-t border-zinc-800 pt-2 text-zinc-400">
          <div>
            <span className="text-zinc-600">arguments</span>
            <pre className="mt-0.5 max-h-32 overflow-auto whitespace-pre-wrap text-[10px] text-zinc-300">
              {JSON.stringify(call.arguments, null, 2)}
            </pre>
          </div>
          {call.result !== undefined && (
            <div>
              <span className="text-zinc-600">result</span>
              <pre className="mt-0.5 max-h-48 overflow-auto whitespace-pre-wrap text-[10px] text-zinc-300">
                {call.result}
              </pre>
            </div>
          )}
        </div>
      )}
    </motion.div>
  );
}

function formatArgsPreview(args: Record<string, unknown>): string {
  const entries = Object.entries(args).slice(0, 2);
  if (entries.length === 0) return "";
  return entries
    .map(([k, v]) => {
      const value = typeof v === "string" ? `"${v.length > 40 ? v.slice(0, 40) + "..." : v}"` : String(v);
      return `${k}: ${value}`;
    })
    .join(", ");
}

/**
 * Compact two-letter badge advertising which retrieval back-ends found
 * the chunk. "V+B" (both vector and BM25 agreed) is a strong signal and
 * is styled accent; "V" or "B" alone use neutral colours.
 */
function ProvenanceBadge({ source }: { source: string[] }) {
  const hasVector = source.includes("vector");
  const hasBm25 = source.includes("bm25");
  const label = hasVector && hasBm25 ? "V+B" : hasVector ? "V" : hasBm25 ? "B" : null;
  if (!label) return null;
  const both = hasVector && hasBm25;
  const title = both
    ? "Surfaced by both vector and BM25 — strong signal"
    : hasVector
      ? "Semantic match (vector embedding)"
      : "Lexical match (BM25 keyword)";
  return (
    <span
      title={title}
      className={cn(
        "rounded px-1 py-0.5 text-[9px] font-semibold uppercase tracking-wider",
        both ? "bg-cyan/15 text-cyan" : "bg-zinc-800 text-zinc-400",
      )}
    >
      {label}
    </span>
  );
}

function ContextItem({ chunk }: { chunk: ChatContextChunk }) {
  // Prefer the symbol-attributed label when the AST chunker found one
  // ("AuthService.refreshToken · L87–L112"); fall back to the legacy
  // path#chunkN label so older payloads keep rendering as before.
  const hasRange = typeof chunk.startLine === "number" && typeof chunk.endLine === "number";
  const lineLabel = hasRange
    ? chunk.startLine === chunk.endLine
      ? `L${chunk.startLine}`
      : `L${chunk.startLine}–L${chunk.endLine}`
    : null;
  return (
    <motion.li
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -2 }}
      className="rounded border border-zinc-800 bg-zinc-900/60 px-2 py-1.5 transition-colors hover:border-cyan/40"
    >
      <div className="mb-1 flex items-center justify-between gap-2 text-[10px] text-zinc-500">
        <span className="truncate font-mono">
          {chunk.symbol ? (
            <>
              <span className="text-zinc-300">{chunk.symbol}</span>
              <span className="text-zinc-600"> · {chunk.path}</span>
              {lineLabel && <span className="text-zinc-600"> · {lineLabel}</span>}
            </>
          ) : (
            <>
              {chunk.path}
              {lineLabel ? <span className="text-zinc-600"> · {lineLabel}</span> : `#chunk${chunk.chunkIndex}`}
            </>
          )}
        </span>
        <span className="flex shrink-0 items-center gap-1 font-mono">
          {chunk.source && chunk.source.length > 0 && (
            <ProvenanceBadge source={chunk.source} />
          )}
          <span>score {chunk.score.toFixed(2)}</span>
        </span>
      </div>
      <pre className="overflow-hidden whitespace-pre-wrap font-mono text-[11px] leading-4 text-zinc-300">
        {chunk.preview}
      </pre>
    </motion.li>
  );
}
