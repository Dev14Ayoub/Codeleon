import { AlertTriangle, ArrowLeft, Bot, CheckCircle2, ChevronDown, ChevronRight, CircleDashed, Database, Eye, Loader2, RefreshCw, Send, Sparkles, Trash2, Users, Wrench, X, Zap } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { useRoomChat, type ChatContextChunk, type ChatMessage, type PatchProposal, type ToolCallEntry } from "@/lib/chat/useRoomChat";
import {
  fetchChatHistory,
  fetchChatThreads,
  getApiErrorMessage,
  indexRoom,
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
  /**
   * Applies a {find → replace} patch to the Y.Text bound to {@code path}.
   * Returns {@code {ok:true}} on success, or {@code {ok:false, reason}}
   * when the find string is missing/ambiguous (e.g. the file changed
   * between proposal and apply). Optional — the chat panel hides the
   * Apply button when not provided.
   */
  onApplyPatch?: (path: string, find: string, replace: string) => { ok: boolean; reason?: string };
  /**
   * Opens {@code path} in the editor and (when provided) scrolls to
   * {@code line}. Wired in by RoomPage so a citation chip becomes a
   * one-click jump to the cited code. Optional — the chip falls back
   * to a non-interactive label when missing.
   */
  onJumpToFile?: (path: string, line?: number) => void;
}

const MAX_INDEX_FILES = 1_000;
const MAX_INDEX_TEXT_CHARS = 200_000;
type IndexStatus = "idle" | "indexing" | "indexed" | "failed" | "blocked" | "empty";

const INDEX_VENDOR_SEGMENTS = [
  "node_modules", "dist", "build", "out", ".next", ".nuxt", "vendor",
  "target", "__pycache__", ".git", "coverage",
];
const INDEX_LOCKFILES = new Set([
  "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock",
  "cargo.lock", "poetry.lock", "gemfile.lock",
]);

/**
 * Keeps generated/vendor and minified files OUT of the RAG index. They bloat
 * the index, slow embedding, and minified/non-Latin bundles overflow the
 * embedder's token window (e.g. assets/js/translation.js). They are still
 * materialized for run/preview — this filter is index-only.
 */
function isIndexableForRag(file: IndexFile): boolean {
  const segments = file.path.toLowerCase().split("/");
  const base = segments[segments.length - 1] ?? "";
  if (segments.some((s) => INDEX_VENDOR_SEGMENTS.includes(s))) return false;
  if (INDEX_LOCKFILES.has(base)) return false;
  if (base.endsWith(".min.js") || base.endsWith(".min.css") || base.endsWith(".map")) return false;
  // Minified/bundled heuristic: a very long longest-line means it is not
  // human-written source and would overflow the embedder.
  let longest = 0;
  for (const line of file.text.split("\n")) {
    if (line.length > longest) longest = line.length;
    if (longest > 5_000) return false;
  }
  return true;
}

/**
 * Slash-command shortcuts shown above the input when it is empty. Each
 * one is a thin template — the textarea is pre-filled with the prompt,
 * and the user can edit it before sending. Templates reference the
 * active file by path so the assistant has a concrete target.
 */
interface SlashCommand {
  label: string;
  hint: string;
  template: (activeFilePath: string | null) => string;
}

const SLASH_COMMANDS: SlashCommand[] = [
  {
    label: "/explain",
    hint: "what this file does",
    template: (p) => p
      ? `Explain what \`${p}\` does and how it fits into the project. Cite specific lines.`
      : `Explain the purpose of the current code. Cite specific lines.`,
  },
  {
    label: "/fix",
    hint: "fix the last error",
    template: (p) => p
      ? `Find and fix the bug in \`${p}\` based on the last run's error. Propose a precise patch.`
      : `Find and fix the bug based on the last run's error. Propose a precise patch.`,
  },
  {
    label: "/test",
    hint: "write a unit test",
    template: (p) => p
      ? `Write a focused unit test for the most important function in \`${p}\`. Cover the happy path and one edge case.`
      : `Write a focused unit test for the current code. Cover the happy path and one edge case.`,
  },
  {
    label: "/refactor",
    hint: "suggest improvements",
    template: (p) => p
      ? `Review \`${p}\` for clarity, naming, and duplication. Suggest concrete refactors with code excerpts.`
      : `Review the current code for clarity, naming, and duplication. Suggest concrete refactors.`,
  },
];

function buildSlashTemplate(cmd: SlashCommand, activeFilePath: string | null): string {
  return cmd.template(activeFilePath);
}

export function ChatPanel({ roomId, getEditorText, getAllFiles, activeFilePath, lastRunStderr, isOwner, onApplyPatch, onJumpToFile }: ChatPanelProps) {
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
  // path -> last-indexed text, so a re-index only re-embeds the files that
  // actually changed (the "edit one file, ask again" loop stays fast).
  const lastIndexedFilesRef = useRef<Map<string, string>>(new Map());

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
    // Index only real source files; generated/vendor/minified are excluded
    // (they are still materialized for run/preview).
    const allFiles = getAllFiles();
    const files = allFiles.filter(isIndexableForRag);
    const excluded = allFiles.length - files.length;
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
    const prev = lastIndexedFilesRef.current;
    const currentMap = new Map(files.map((file) => [file.path, file.text] as const));
    const changed = files.filter((file) => prev.get(file.path) !== file.text);
    const removed = [...prev.keys()].some((path) => !currentMap.has(path));

    // Nothing changed since the last index → no-op (the chat-send path).
    if (!force && prev.size > 0 && changed.length === 0 && !removed) return;

    // Incremental only when we have a prior baseline, nothing was removed,
    // and the caller did not force a full rebuild. A removal needs the full
    // path because deleteRoomIndex is what clears the stale chunks.
    const incremental = !force && prev.size > 0 && !removed;

    setIndexing(true);
    setIndexStatus("indexing");
    setIndexError(null);
    setIndexInfo(null);
    try {
      if (incremental) {
        let chunks = 0;
        let durationMs = 0;
        let failed = 0;
        for (const file of changed) {
          try {
            const r = await indexRoom(roomId, { path: file.path, text: file.text });
            chunks += r.chunks;
            durationMs += r.durationMs;
          } catch {
            failed += 1;
          }
        }
        lastIndexedFilesRef.current = currentMap;
        setIndexStatus("indexed");
        setIndexInfo(
          `Re-indexed ${changed.length} changed file${changed.length === 1 ? "" : "s"} · ` +
            `${chunks} chunk${chunks === 1 ? "" : "s"} (${durationMs} ms)` +
            (failed > 0 ? ` · ${failed} skipped` : ""),
        );
      } else {
        const result = await indexRoomAll(roomId, files);
        lastIndexedFilesRef.current = currentMap;
        const failed = result.failedFiles ?? 0;
        const ok = files.length - failed;
        setIndexStatus("indexed");
        setIndexInfo(
          `Indexed ${ok}/${files.length} file${files.length === 1 ? "" : "s"} · ` +
            `${result.chunks} chunk${result.chunks === 1 ? "" : "s"} (${result.durationMs} ms)` +
            (failed > 0 ? ` · ${failed} skipped` : "") +
            (excluded > 0 ? ` · ${excluded} generated excluded` : ""),
        );
      }
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
    // Indexing is explicit: the user clicks "Index project" once at the
    // start (or after a big edit). Doing it before every chat blocked the
    // send and loaded the embedder in parallel with the chat model, which
    // tanked the chat path on memory-constrained hosts.
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
      <div className="mb-2 flex items-center justify-between">
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
        <p className="mb-2 truncate rounded-md border border-zinc-800 bg-zinc-950 px-2.5 py-1 text-[11px] text-zinc-500" title="The room owner can read this conversation.">
          🔒 Owner can read this conversation.
        </p>
      )}

      {/* Owner-only thread picker: review any member's chat read-only. */}
      {isOwner && (threadsQuery.data?.length ?? 0) > 0 && (
        <div className="mb-2 flex items-center gap-2">
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

      {/* Index status + manual refresh on a single compact row.
          Indexing already runs automatically before each send when the
          project changed — the button here is for an explicit refresh
          (e.g. after deleting cached data, or to surface a "blocked"
          status to retry). Info/error messages collapse to a single
          line under the row when present. */}
      <div className="mb-2 flex flex-col gap-1">
        <div className="flex items-center gap-2 rounded-md border border-zinc-800 bg-zinc-950 px-2.5 py-1.5">
          <IndexStatusLine status={indexStatus} className="flex-1" />
          <button
            type="button"
            onClick={() => void indexProject(true)}
            disabled={indexing}
            className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded text-zinc-500 transition hover:bg-zinc-900 hover:text-zinc-200 disabled:opacity-40"
            title="Re-index project"
            aria-label="Re-index project"
          >
            {indexing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
          </button>
        </div>
        {(indexInfo || indexError) && (
          <p className={cn("truncate text-[11px]", indexError ? "text-rose-400" : "text-emerald-400")}>
            {indexError ?? indexInfo}
          </p>
        )}
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
            and the eventual assistant answer, in the order calls arrived.
            A propose_patch tool with a parsed proposal renders as a
            dedicated Apply card instead of the generic bubble. */}
        {!isReviewing && chat.toolCalls.length > 0 && (
          <div className="space-y-1.5">
            {chat.toolCalls.map((tc) =>
              tc.patchProposal ? (
                <PatchProposalCard
                  key={tc.id}
                  proposal={tc.patchProposal}
                  onApply={
                    onApplyPatch
                      ? () => {
                          const outcome = onApplyPatch(
                            tc.patchProposal!.path,
                            tc.patchProposal!.find,
                            tc.patchProposal!.replace,
                          );
                          chat.markPatchApplied(
                            tc.patchProposal!.patchId,
                            outcome.ok,
                            outcome.ok ? undefined : outcome.reason,
                          );
                        }
                      : undefined
                  }
                />
              ) : (
                <ToolCallBubble key={tc.id} call={tc} />
              ),
            )}
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
                <ContextItem key={i} chunk={c} onJump={onJumpToFile} />
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
        {/* Slash-command chips: pre-fill the textarea with a templated
            prompt scoped to the file the user has open. Hidden once the
            user starts typing so they don't compete for screen room. */}
        {draft.length === 0 && !chat.streaming && (
          <div className="flex flex-wrap gap-1.5">
            {SLASH_COMMANDS.map((cmd) => (
              <button
                key={cmd.label}
                type="button"
                onClick={() => setDraft(buildSlashTemplate(cmd, activeFilePath))}
                className="rounded-md border border-zinc-800 bg-zinc-950 px-2 py-1 text-[11px] text-zinc-300 transition hover:border-cyan/40 hover:text-cyan"
                title={cmd.hint}
              >
                <span className="font-mono text-cyan">{cmd.label}</span>
                <span className="ml-1 text-zinc-500">{cmd.hint}</span>
              </button>
            ))}
          </div>
        )}
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

function IndexStatusLine({ status, className }: { status: IndexStatus; className?: string }) {
  // Short labels — fit a single chat-panel row width (~280px min sidebar).
  // The previous "Indexed and ready for grounded answers" was 6 words for
  // a status the user just needs to glance at.
  const copy: Record<IndexStatus, { icon: JSX.Element; text: string; className: string }> = {
    idle: {
      icon: <CircleDashed className="h-3 w-3" />,
      text: "Index up to date",
      className: "text-zinc-500",
    },
    indexing: {
      icon: <Loader2 className="h-3 w-3 animate-spin" />,
      text: "Indexing project…",
      className: "text-cyan",
    },
    indexed: {
      icon: <CheckCircle2 className="h-3 w-3" />,
      text: "Indexed",
      className: "text-emerald-400",
    },
    failed: {
      icon: <AlertTriangle className="h-3 w-3" />,
      text: "Index failed",
      className: "text-rose-400",
    },
    blocked: {
      icon: <AlertTriangle className="h-3 w-3" />,
      text: "Index blocked",
      className: "text-amber-400",
    },
    empty: {
      icon: <CircleDashed className="h-3 w-3" />,
      text: "No files to index",
      className: "text-zinc-500",
    },
  };
  const item = copy[status];
  return (
    <span className={cn("inline-flex min-w-0 items-center gap-1.5 text-[11px]", item.className, className)}>
      {item.icon}
      <span className="truncate">{item.text}</span>
    </span>
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
 * Renders an agent-proposed file edit as a reviewable card with Apply /
 * Reject controls. The body shows a minimal red/green diff of the find
 * and replace blocks — no full file context, the agent's job is to
 * propose tight changes the user can audit at a glance.
 *
 * <p>{@code onApply} is optional so the panel can render the card in
 * read-only contexts (e.g. owner-reviewing-a-thread mode) without
 * pretending Apply is wired up.
 */
function PatchProposalCard({
  proposal,
  onApply,
}: {
  proposal: PatchProposal;
  onApply?: () => void;
}) {
  const [dismissed, setDismissed] = useState(false);
  if (dismissed) return null;

  const applied = proposal.applied === true;
  const failed = proposal.applied === false && proposal.applyError !== undefined;
  return (
    <motion.div
      initial={{ opacity: 0, y: 6, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      className={cn(
        "rounded-md border bg-zinc-950 text-[11px]",
        applied
          ? "border-emerald-800"
          : failed
            ? "border-rose-900"
            : "border-amber-700/60",
      )}
    >
      <div className="flex items-center justify-between gap-2 border-b border-zinc-800 px-2.5 py-1.5">
        <span className="flex min-w-0 items-center gap-1.5 truncate font-mono">
          <Wrench className="h-3 w-3 shrink-0 text-amber-400" />
          <span className="font-semibold text-zinc-200">propose_patch</span>
          <span className="truncate text-zinc-500">{proposal.path}</span>
        </span>
        {!applied && !failed && (
          <button
            type="button"
            onClick={() => setDismissed(true)}
            className="text-zinc-600 hover:text-zinc-300"
            aria-label="Dismiss patch"
          >
            <X className="h-3 w-3" />
          </button>
        )}
      </div>
      {proposal.rationale && (
        <p className="border-b border-zinc-900 px-2.5 py-1.5 text-zinc-400">{proposal.rationale}</p>
      )}
      <div className="space-y-0 px-2.5 py-1.5 font-mono">
        {proposal.find && (
          <pre className="max-h-32 overflow-auto whitespace-pre-wrap rounded-sm bg-rose-950/30 px-2 py-1 text-[10px] text-rose-200">
            - {proposal.find}
          </pre>
        )}
        {proposal.replace && (
          <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded-sm bg-emerald-950/30 px-2 py-1 text-[10px] text-emerald-200">
            + {proposal.replace}
          </pre>
        )}
        {!proposal.replace && (
          <p className="mt-1 text-zinc-500 italic">(deletes the matched region)</p>
        )}
      </div>
      <div className="flex items-center justify-between gap-2 border-t border-zinc-800 px-2.5 py-1.5">
        {applied ? (
          <span className="inline-flex items-center gap-1 text-[11px] text-emerald-400">
            <CheckCircle2 className="h-3 w-3" /> applied
          </span>
        ) : failed ? (
          <span className="inline-flex items-center gap-1 text-[11px] text-rose-300">
            <AlertTriangle className="h-3 w-3" /> {proposal.applyError}
          </span>
        ) : (
          <span className="text-[11px] text-zinc-500">awaiting your decision</span>
        )}
        {!applied && onApply && (
          <div className="flex items-center gap-1.5">
            <Button type="button" variant="secondary" onClick={() => setDismissed(true)}>
              Reject
            </Button>
            <Button type="button" onClick={onApply}>
              Apply
            </Button>
          </div>
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

function ContextItem({
  chunk,
  onJump,
}: {
  chunk: ChatContextChunk;
  onJump?: (path: string, line?: number) => void;
}) {
  // Prefer the symbol-attributed label when the AST chunker found one
  // ("AuthService.refreshToken · L87–L112"); fall back to the legacy
  // path#chunkN label so older payloads keep rendering as before.
  const hasRange = typeof chunk.startLine === "number" && typeof chunk.endLine === "number";
  const lineLabel = hasRange
    ? chunk.startLine === chunk.endLine
      ? `L${chunk.startLine}`
      : `L${chunk.startLine}–L${chunk.endLine}`
    : null;
  const jumpable = onJump !== undefined;
  const handleJump = () => {
    if (!onJump) return;
    onJump(chunk.path, chunk.startLine);
  };
  return (
    <motion.li
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -2 }}
      onClick={jumpable ? handleJump : undefined}
      onKeyDown={
        jumpable
          ? (e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                handleJump();
              }
            }
          : undefined
      }
      role={jumpable ? "button" : undefined}
      tabIndex={jumpable ? 0 : undefined}
      title={jumpable ? `Jump to ${chunk.path}${lineLabel ? " · " + lineLabel : ""}` : undefined}
      className={cn(
        "rounded border border-zinc-800 bg-zinc-900/60 px-2 py-1.5 transition-colors hover:border-cyan/40",
        jumpable && "cursor-pointer focus:border-cyan focus:outline-none",
      )}
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
