import { useCallback, useEffect, useRef, useState } from "react";
import { AxiosError } from "axios";
import type * as Y from "yjs";
import {
  fetchIndexState,
  getApiErrorMessage,
  indexRoom,
  indexRoomAll,
  type IndexFile,
} from "@/lib/api";

export type IndexStatus = "idle" | "indexing" | "indexed" | "failed" | "blocked" | "empty";

const MAX_INDEX_FILES = 1_000;
const MAX_INDEX_TEXT_CHARS = 200_000;
// Idle window after the last edit before we re-index. Generous on purpose:
// embeddings run on CPU-only Ollama here, so we only want to fire once the
// user pauses, not on every keystroke batch.
const DEBOUNCE_MS = 2_500;

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
 * embedder's token window. They are still materialized for run/preview — this
 * filter is index-only.
 */
export function isIndexableForRag(file: IndexFile): boolean {
  const segments = file.path.toLowerCase().split("/");
  const base = segments[segments.length - 1] ?? "";
  if (segments.some((s) => INDEX_VENDOR_SEGMENTS.includes(s))) return false;
  if (INDEX_LOCKFILES.has(base)) return false;
  if (base.endsWith(".min.js") || base.endsWith(".min.css") || base.endsWith(".map")) return false;
  let longest = 0;
  for (const line of file.text.split("\n")) {
    if (line.length > longest) longest = line.length;
    if (longest > 5_000) return false;
  }
  return true;
}

/** Lowercase hex SHA-256 of {@code text} — must match the backend's
 *  RoomFileIndexer.sha256 so the two views of "already indexed" agree. */
async function sha256Hex(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** The backend returns 400 with this phrasing when the AI profile is off. */
function isAiDisabled(error: unknown): boolean {
  if (!(error instanceof AxiosError) || error.response?.status !== 400) return false;
  const message = (error.response?.data as { message?: string } | undefined)?.message ?? "";
  return message.toLowerCase().includes("ai features are disabled");
}

export interface RoomAutoIndex {
  status: IndexStatus;
  info: string | null;
  error: string | null;
  indexing: boolean;
  /**
   * Index now. {@code force=true} always re-indexes the whole project;
   * {@code force=false} re-embeds only the files whose content hash changed
   * since the baseline (and clears the ones that disappeared). Used by the
   * manual button (force) and the chat-send path (force=false).
   */
  flush: (force?: boolean) => Promise<void>;
}

/**
 * Owns the room's RAG indexing. Mounted once at the room level — not inside
 * the chat panel — so it keeps the index fresh even when the AI panel is
 * collapsed. On mount it seeds its baseline from the server (so a refresh or
 * a second tab does not re-index an already-indexed project), then re-indexes
 * automatically on a debounced timer whenever the project changes.
 */
export function useRoomAutoIndex(
  roomId: string,
  getAllFiles: () => IndexFile[],
  ydoc: Y.Doc,
  enabled: boolean,
): RoomAutoIndex {
  const [status, setStatus] = useState<IndexStatus>("idle");
  const [info, setInfo] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [indexing, setIndexing] = useState(false);

  // path -> content hash the server currently has indexed.
  const baselineRef = useRef<Map<string, string>>(new Map());
  const baselineLoadedRef = useRef(false);
  const runningRef = useRef(false);
  const queuedRef = useRef(false);
  // Once the backend tells us AI is off, stop trying for the session.
  const aiDisabledRef = useRef(false);
  // Latest getAllFiles, read through a ref so the debounce subscription does
  // not need to re-bind every time the file list changes identity.
  const getAllFilesRef = useRef(getAllFiles);
  getAllFilesRef.current = getAllFiles;

  const flush = useCallback(
    async (force = false): Promise<void> => {
      if (!enabled || !roomId || aiDisabledRef.current) return;
      if (runningRef.current) {
        // Coalesce: remember that more work arrived and run once after.
        queuedRef.current = true;
        return;
      }
      runningRef.current = true;
      setIndexing(true);
      try {
        // Seed the baseline from the server once per mount so we only send
        // the diff, not the whole project, after a refresh.
        if (!baselineLoadedRef.current) {
          try {
            const serverState = await fetchIndexState(roomId);
            const seeded = new Map<string, string>();
            for (const f of serverState.files) seeded.set(f.path, f.hash);
            baselineRef.current = seeded;
          } catch {
            // Treat as empty baseline — a full index will follow.
          }
          baselineLoadedRef.current = true;
        }

        const all = getAllFilesRef.current();
        const files = all.filter(isIndexableForRag);
        const excluded = all.length - files.length;

        if (files.length === 0) {
          if (force) {
            setStatus("empty");
            setError("No files to index yet — write some code first.");
          }
          return;
        }
        if (files.length > MAX_INDEX_FILES) {
          setStatus("blocked");
          setError(
            `Indexing supports up to ${MAX_INDEX_FILES} files; this room has ${files.length}.`,
          );
          return;
        }
        const oversized = files.find((f) => f.text.length > MAX_INDEX_TEXT_CHARS);
        if (oversized) {
          setStatus("blocked");
          setError(
            `Cannot index ${oversized.path}: ${oversized.text.length.toLocaleString()} chars exceeds the ${MAX_INDEX_TEXT_CHARS.toLocaleString()} limit.`,
          );
          return;
        }

        // Current content hashes, computed off the main work in parallel.
        const currentHashes = new Map<string, string>();
        await Promise.all(
          files.map(async (f) => {
            currentHashes.set(f.path, await sha256Hex(f.text));
          }),
        );

        const baseline = baselineRef.current;
        const currentPaths = new Set(files.map((f) => f.path));
        const changed = files.filter((f) => baseline.get(f.path) !== currentHashes.get(f.path));
        const removed = [...baseline.keys()].filter((p) => !currentPaths.has(p));

        if (!force && changed.length === 0 && removed.length === 0) {
          setStatus("indexed");
          return;
        }

        setStatus("indexing");
        setError(null);
        setInfo(null);

        // Full rebuild when forced or when there is no baseline yet (first
        // index of a fresh room); incremental otherwise.
        if (force || baseline.size === 0) {
          const result = await indexRoomAll(roomId, files);
          baselineRef.current = currentHashes;
          const failed = result.failedFiles ?? 0;
          const ok = files.length - failed;
          setStatus("indexed");
          setInfo(
            `Indexed ${ok}/${files.length} file${files.length === 1 ? "" : "s"} · ` +
              `${result.chunks} chunk${result.chunks === 1 ? "" : "s"} (${result.durationMs} ms)` +
              (failed > 0 ? ` · ${failed} skipped` : "") +
              (excluded > 0 ? ` · ${excluded} generated excluded` : ""),
          );
        } else {
          let chunks = 0;
          let durationMs = 0;
          let failed = 0;
          for (const f of changed) {
            try {
              const r = await indexRoom(roomId, { path: f.path, text: f.text });
              chunks += r.chunks;
              durationMs += r.durationMs;
              baselineRef.current.set(f.path, currentHashes.get(f.path)!);
            } catch (ex) {
              if (isAiDisabled(ex)) {
                aiDisabledRef.current = true;
                setStatus("idle");
                return;
              }
              failed += 1;
            }
          }
          for (const path of removed) {
            try {
              await indexRoom(roomId, { path, text: "" });
              baselineRef.current.delete(path);
            } catch {
              // Leave the baseline entry; a later run retries the removal.
            }
          }
          setStatus("indexed");
          setInfo(
            `Re-indexed ${changed.length} changed` +
              (removed.length > 0 ? `, ${removed.length} removed` : "") +
              ` · ${chunks} chunk${chunks === 1 ? "" : "s"} (${durationMs} ms)` +
              (failed > 0 ? ` · ${failed} skipped` : ""),
          );
        }
      } catch (ex) {
        if (isAiDisabled(ex)) {
          aiDisabledRef.current = true;
          setStatus("idle");
          return;
        }
        setStatus("failed");
        setError(getApiErrorMessage(ex, "Indexing failed"));
      } finally {
        runningRef.current = false;
        setIndexing(false);
        if (queuedRef.current) {
          queuedRef.current = false;
          void flush(false);
        }
      }
    },
    [enabled, roomId],
  );

  // Auto-trigger: index shortly after mount and, debounced, on every change
  // to the shared doc (local edits and remote ones alike).
  useEffect(() => {
    if (!enabled || !roomId) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const schedule = () => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => void flush(false), DEBOUNCE_MS);
    };
    schedule();
    const onUpdate = () => schedule();
    ydoc.on("update", onUpdate);
    return () => {
      if (timer) clearTimeout(timer);
      ydoc.off("update", onUpdate);
    };
  }, [enabled, roomId, ydoc, flush]);

  return { status, info, error, indexing, flush };
}
