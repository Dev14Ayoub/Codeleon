import Editor, { type BeforeMount, type OnMount } from "@monaco-editor/react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  Bot,
  Braces,
  Circle,
  Copy,
  FileCode2,
  Play,
  Users,
  Wifi,
  WifiOff,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { MonacoBinding } from "y-monaco";
import type * as monaco from "monaco-editor";
import { Button } from "@/components/ui/button";
import { fetchRoom } from "@/lib/api";
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
  const bindingRef = useRef<MonacoBinding | null>(null);
  const [editorReady, setEditorReady] = useState(false);

  useEffect(() => {
    if (!editorReady) return;
    const editor = editorRef.current;
    if (!editor) return;
    const model = editor.getModel();
    if (!model) return;
    if (!collab.awareness) return;

    bindingRef.current = new MonacoBinding(
      collab.yText,
      model,
      new Set([editor]),
      collab.awareness,
    );

    return () => {
      bindingRef.current?.destroy();
      bindingRef.current = null;
    };
  }, [editorReady, collab.awareness, collab.yText]);

  if (!roomId) {
    return <Navigate to="/dashboard" replace />;
  }

  const room = roomQuery.data;

  const onEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
    setEditorReady(true);
  };

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
          <Button disabled>
            <Play className="h-4 w-4" />
            Run
          </Button>
        </div>
      </header>

      <section className="grid min-h-[calc(100vh-4rem)] grid-cols-1 lg:grid-cols-[17rem_minmax(0,1fr)_20rem]">
        <aside className="hidden border-r border-zinc-800 bg-surface/70 p-4 lg:block">
          <div className="mb-4 flex items-center gap-2 text-sm font-medium text-zinc-200">
            <FileCode2 className="h-4 w-4 text-cyan" />
            Files
          </div>
          <button className="flex w-full items-center gap-2 rounded-md bg-surfaceRaised px-3 py-2 text-left font-mono text-sm text-zinc-100">
            <Circle className="h-2 w-2 fill-cyan text-cyan" />
            Main.java
          </button>
        </aside>

        <section className="min-h-[34rem] bg-zinc-950">
          <div className="flex h-10 items-center justify-between border-b border-zinc-800 bg-surface px-4">
            <div className="flex items-center gap-2 font-mono text-xs text-zinc-400">
              <FileCode2 className="h-4 w-4 text-zinc-500" />
              Main.java
            </div>
            <span className="text-xs text-zinc-500">
              {collab.isReady ? "Live" : "Connecting..."}
            </span>
          </div>
          <Editor
            beforeMount={configureMonaco}
            onMount={onEditorMount}
            defaultLanguage="java"
            defaultValue=""
            height="calc(100vh - 6.5rem)"
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
            }}
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

          <section className="p-4">
            <div className="mb-4 flex items-center gap-2 text-sm font-medium text-zinc-200">
              <Bot className="h-4 w-4 text-cyan" />
              AI context
            </div>
            <div className="rounded-lg border border-zinc-800 bg-zinc-950 p-4">
              <p className="text-sm leading-6 text-zinc-400">
                The RAG assistant will use this room context once Ollama and Qdrant are connected.
              </p>
            </div>
          </section>
        </aside>
      </section>
    </main>
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

const configureMonaco: BeforeMount = (monacoNs) => {
  monacoNs.editor.defineTheme("codeleon-dark", {
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
  });
};
