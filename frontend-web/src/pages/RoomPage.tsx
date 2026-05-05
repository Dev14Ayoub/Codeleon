import Editor, { type BeforeMount, type OnMount } from "@monaco-editor/react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";
import {
  ArrowLeft,
  Braces,
  Copy,
  Loader2,
  Play,
  Terminal,
  Users,
  Wifi,
  WifiOff,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { MonacoBinding } from "y-monaco";
import type * as monaco from "monaco-editor";
import { Button } from "@/components/ui/button";
import { ChatPanel } from "@/components/chat/ChatPanel";
import { EditorTabs } from "@/components/files/EditorTabs";
import { FileExplorer } from "@/components/files/FileExplorer";
import { fetchRoom, runCode, type RunResult } from "@/lib/api";
import { languageFromPath } from "@/lib/files/file-language";
import { useCollabRoom } from "@/lib/collab/useCollabRoom";

export function RoomPage() {
  const { roomId } = useParams();

  const roomQuery = useQuery({
    queryKey: ["rooms", roomId],
    queryFn: () => fetchRoom(roomId ?? ""),
    enabled: Boolean(roomId),
  });

  const collab = useCollabRoom(roomId);

  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const monacoNsRef = useRef<Parameters<BeforeMount>[0] | null>(null);
  // One Monaco model per open file path. Keeping models alive across
  // tab switches preserves each file's cursor, scroll position, undo
  // history and selection independently — the way VS Code does it.
  const modelsRef = useRef<Map<string, monaco.editor.ITextModel>>(new Map());
  // Yjs binding for the *currently active* model only. We tear it down
  // and recreate it on every tab switch instead of holding N bindings,
  // because y-monaco bindings tie themselves to the editor instance and
  // would otherwise fire selection events on the wrong model.
  const bindingRef = useRef<MonacoBinding | null>(null);
  const [editorReady, setEditorReady] = useState(false);
  const [openPaths, setOpenPaths] = useState<string[]>([]);
  const [activePath, setActivePath] = useState<string | null>(null);
  const [runResult, setRunResult] = useState<RunResult | null>(null);
  const [runError, setRunError] = useState<string | null>(null);

  const room = roomQuery.data;
  const canEdit =
    room?.currentUserRole === "OWNER" || room?.currentUserRole === "EDITOR";

  const runMutation = useMutation({
    mutationFn: () => {
      const code = editorRef.current?.getValue() ?? "";
      return runCode(roomId ?? "", { language: "PYTHON", code });
    },
    onSuccess: (data) => {
      setRunResult(data);
      setRunError(null);
    },
    onError: (error: unknown) => {
      setRunResult(null);
      if (error instanceof AxiosError) {
        const message =
          (error.response?.data as { message?: string } | undefined)?.message ?? error.message;
        setRunError(message);
      } else {
        setRunError("Failed to run code");
      }
    },
  });

  // Open a file as a tab (idempotent) and make it active.
  const openFile = useCallback((path: string) => {
    setOpenPaths((prev) => (prev.includes(path) ? prev : [...prev, path]));
    setActivePath(path);
  }, []);

  // Close a tab. Disposes the model + adjusts the active selection.
  const closeTab = useCallback((path: string) => {
    setOpenPaths((prev) => {
      const idx = prev.indexOf(path);
      if (idx < 0) return prev;
      const next = [...prev.slice(0, idx), ...prev.slice(idx + 1)];
      setActivePath((current) => {
        if (current !== path) return current;
        if (next.length === 0) return null;
        return next[Math.min(idx, next.length - 1)];
      });
      const model = modelsRef.current.get(path);
      if (model) {
        model.dispose();
        modelsRef.current.delete(path);
      }
      return next;
    });
  }, []);

  // Switch the editor to the model for `activePath`, creating it on first
  // open. Destroys the previous Yjs binding and creates a fresh one
  // pointing at the new model.
  useEffect(() => {
    if (!editorReady) return;
    const editor = editorRef.current;
    const monacoNs = monacoNsRef.current;
    if (!editor || !monacoNs) return;
    if (!activePath || !collab.awareness) {
      bindingRef.current?.destroy();
      bindingRef.current = null;
      return;
    }

    let model = modelsRef.current.get(activePath);
    if (!model) {
      model = monacoNs.editor.createModel("", languageFromPath(activePath));
      modelsRef.current.set(activePath, model);
    } else {
      monacoNs.editor.setModelLanguage(model, languageFromPath(activePath));
    }
    editor.setModel(model);

    bindingRef.current?.destroy();
    const yText = collab.ydoc.getText(activePath);
    bindingRef.current = new MonacoBinding(
      yText,
      model,
      new Set([editor]),
      collab.awareness,
    );

    return () => {
      bindingRef.current?.destroy();
      bindingRef.current = null;
    };
  }, [editorReady, activePath, collab.awareness, collab.ydoc]);

  // Dispose every cached Monaco model when the page unmounts so we
  // don't leak editor state between rooms.
  useEffect(() => {
    const cache = modelsRef.current;
    return () => {
      cache.forEach((m) => m.dispose());
      cache.clear();
    };
  }, []);

  if (!roomId) {
    return <Navigate to="/dashboard" replace />;
  }

  const onEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
    setEditorReady(true);
  };

  const beforeMount: BeforeMount = (monacoNs) => {
    monacoNsRef.current = monacoNs;
    monacoNs.editor.defineTheme("codeleon-dark", CODELEON_DARK_THEME);
  };

  const getEditorText = useCallback(() => editorRef.current?.getValue() ?? "", []);

  return (
    <main className="flex min-h-screen flex-col bg-background text-zinc-100">
      <header className="flex min-h-16 items-center justify-between border-b border-zinc-800 bg-background/95 px-4 backdrop-blur">
        <div className="flex min-w-0 items-center gap-4">
          <Button asChild variant="ghost" className="px-2">
            <Link to="/dashboard" aria-label="Back to dashboard">
              <ArrowLeft className="h-4 w-4" />
            </Link>
          </Button>
          <Link to="/" className="hidden items-center gap-3 sm:flex">
            <span className="flex h-9 w-9 items-center justify-center rounded-md bg-signature text-white">
              <Braces className="h-4 w-4" />
            </span>
          </Link>
          <div className="min-w-0">
            <p className="truncate text-sm text-zinc-500">Room workspace</p>
            <h1 className="truncate text-lg font-semibold text-zinc-50">
              {roomQuery.isLoading ? "Loading room..." : room?.name ?? "Room unavailable"}
            </h1>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <ConnectionPill connected={collab.isConnected} />
          <Button
            variant="secondary"
            onClick={() => void navigator.clipboard?.writeText(room?.inviteCode ?? "")}
            disabled={!room}
          >
            <Copy className="h-4 w-4" />
            Invite
          </Button>
          <Button
            onClick={() => runMutation.mutate()}
            disabled={!editorReady || runMutation.isPending}
          >
            {runMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Play className="h-4 w-4" />
            )}
            Run
          </Button>
        </div>
      </header>

      <section className="grid min-h-[calc(100vh-4rem)] grid-cols-1 lg:grid-cols-[17rem_minmax(0,1fr)_20rem]">
        <aside className="hidden flex-col border-r border-zinc-800 bg-surface/70 p-4 lg:flex">
          <FileExplorer
            roomId={roomId}
            activePath={activePath}
            onActivePathChange={openFile}
            canEdit={canEdit}
          />
        </aside>

        <section className="flex min-h-[34rem] flex-col bg-zinc-950">
          <div className="flex items-center justify-between border-b border-zinc-800 bg-surface pr-4">
            <div className="flex-1 min-w-0">
              <EditorTabs
                openPaths={openPaths}
                activePath={activePath}
                onActivate={setActivePath}
                onClose={closeTab}
              />
            </div>
            <span className="ml-3 shrink-0 text-xs text-zinc-500">
              {collab.isReady ? "Live" : "Connecting..."}
            </span>
          </div>
          <div className="flex-1 min-h-0">
            <Editor
              beforeMount={beforeMount}
              onMount={onEditorMount}
              defaultLanguage="plaintext"
              defaultValue=""
              height="100%"
              theme="codeleon-dark"
              options={{
                fontFamily: "Geist Mono, ui-monospace, SFMono-Regular, monospace",
                fontSize: 14,
                minimap: { enabled: false },
                padding: { top: 18, bottom: 18 },
                scrollBeyondLastLine: false,
                smoothScrolling: true,
                tabSize: 4,
                wordWrap: "on",
                readOnly: !canEdit,
              }}
            />
          </div>
          <OutputPanel
            isPending={runMutation.isPending}
            result={runResult}
            error={runError}
          />
        </section>

        <aside className="grid border-l border-zinc-800 bg-surface/70 lg:grid-rows-[auto_1fr]">
          <section className="border-b border-zinc-800 p-4">
            <div className="mb-4 flex items-center gap-2 text-sm font-medium text-zinc-200">
              <Users className="h-4 w-4 text-cyan" />
              Participants ({collab.peers.length})
            </div>
            <div className="space-y-3">
              {collab.peers.length === 0 ? (
                <p className="text-xs text-zinc-500">No live participants yet.</p>
              ) : (
                collab.peers.map((peer) => (
                  <Participant
                    key={peer.clientId}
                    name={peer.name}
                    color={peer.color}
                    isMe={peer.userId === room?.ownerId && peer.userId === peer.userId}
                  />
                ))
              )}
            </div>
          </section>

          <section className="flex min-h-0 flex-col p-4">
            <ChatPanel roomId={roomId} getEditorText={getEditorText} />
          </section>
        </aside>
      </section>
    </main>
  );
}

function OutputPanel({
  isPending,
  result,
  error,
}: {
  isPending: boolean;
  result: RunResult | null;
  error: string | null;
}) {
  const exitTone = result
    ? result.timedOut
      ? "text-amber-400"
      : result.exitCode === 0
        ? "text-emerald-400"
        : "text-rose-400"
    : "text-zinc-500";

  return (
    <div className="h-48 border-t border-zinc-800 bg-zinc-950">
      <div className="flex h-9 items-center justify-between border-b border-zinc-800 bg-surface px-4">
        <div className="flex items-center gap-2 font-mono text-xs text-zinc-400">
          <Terminal className="h-3.5 w-3.5 text-zinc-500" />
          Output
        </div>
        <div className={`font-mono text-xs ${exitTone}`}>
          {isPending
            ? "Running..."
            : result
              ? result.timedOut
                ? `timed out after ${result.durationMs} ms`
                : `exit ${result.exitCode} • ${result.durationMs} ms`
              : error
                ? "error"
                : "idle"}
        </div>
      </div>
      <pre className="h-[calc(100%-2.25rem)] overflow-auto whitespace-pre-wrap px-4 py-3 font-mono text-xs leading-5 text-zinc-200">
        {error ? (
          <span className="text-rose-400">{error}</span>
        ) : result ? (
          <>
            {result.stdout}
            {result.stderr && <span className="text-rose-400">{result.stderr}</span>}
          </>
        ) : (
          <span className="text-zinc-600">
            Press Run to execute the current file with Python.
          </span>
        )}
      </pre>
    </div>
  );
}

function Participant({ name, color, isMe }: { name: string; color: string; isMe?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2">
      <div className="flex items-center gap-3">
        <span
          className="h-2.5 w-2.5 rounded-full"
          style={{ backgroundColor: color }}
          aria-hidden
        />
        <span className="text-sm text-zinc-200">{name}</span>
      </div>
      <span className="text-xs text-zinc-500">{isMe ? "you" : "online"}</span>
    </div>
  );
}

function ConnectionPill({ connected }: { connected: boolean }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs ${
        connected
          ? "border-emerald-700/60 bg-emerald-900/20 text-emerald-300"
          : "border-zinc-700 bg-zinc-900 text-zinc-400"
      }`}
    >
      {connected ? <Wifi className="h-3 w-3" /> : <WifiOff className="h-3 w-3" />}
      {connected ? "Live" : "Offline"}
    </span>
  );
}

const CODELEON_DARK_THEME: monaco.editor.IStandaloneThemeData = {
  base: "vs-dark",
  inherit: true,
  rules: [
    { token: "comment", foreground: "71717A" },
    { token: "keyword", foreground: "8B5CF6" },
    { token: "string", foreground: "10B981" },
    { token: "number", foreground: "06B6D4" },
  ],
  colors: {
    "editor.background": "#09090B",
    "editor.foreground": "#FAFAFA",
    "editor.lineHighlightBackground": "#18181B",
    "editorLineNumber.foreground": "#52525B",
    "editorCursor.foreground": "#06B6D4",
    "editor.selectionBackground": "#6366F166",
    "editor.inactiveSelectionBackground": "#27272A",
  },
};
