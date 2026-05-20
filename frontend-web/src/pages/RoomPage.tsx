import { useMutation, useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";
import {
  ArrowLeft,
  Check,
  Copy,
  FileText,
  Loader2,
  Play,
  Terminal,
  Users,
  Wifi,
  WifiOff,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState, type PointerEvent } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Logo } from "@/components/brand/Logo";
import { ChatPanel } from "@/components/chat/ChatPanel";
import {
  CodeMirrorEditor,
  type CodeMirrorEditorHandle,
} from "@/components/editor/CodeMirrorEditor";
import { EditorTabs } from "@/components/files/EditorTabs";
import { FileExplorer, type FileExplorerHandle } from "@/components/files/FileExplorer";
import { ImportGithubDialog } from "@/components/files/ImportGithubDialog";
import { MenuBar } from "@/components/layout/MenuBar";
import {
  createRoomFile,
  fetchRoom,
  listRoomFiles,
  runCode,
  type GithubImportResponse,
  type IndexFile,
  type RoomFile,
  type RunResult,
} from "@/lib/api";
import { prepareLocalImport } from "@/lib/files/local-import";
import { useCollabRoom } from "@/lib/collab/useCollabRoom";
import { useAuthStore } from "@/stores/auth-store";

export function RoomPage() {
  const { roomId } = useParams();

  const roomQuery = useQuery({
    queryKey: ["rooms", roomId],
    queryFn: () => fetchRoom(roomId ?? ""),
    enabled: Boolean(roomId),
  });

  // The full file list (not just open tabs) so the chat panel can index
  // the whole project. Content for each path is pulled from the Y.Doc.
  const roomFilesQuery = useQuery({
    queryKey: ["rooms", roomId, "files"],
    queryFn: () => listRoomFiles(roomId ?? ""),
    enabled: Boolean(roomId),
  });

  const collab = useCollabRoom(roomId);
  const currentUser = useAuthStore((state) => state.user);

  const editorRef = useRef<CodeMirrorEditorHandle | null>(null);
  const workspaceRef = useRef<HTMLElement | null>(null);
  const fileExplorerRef = useRef<FileExplorerHandle | null>(null);
  const [editorReady, setEditorReady] = useState(false);
  const [openPaths, setOpenPaths] = useState<string[]>([]);
  const [activePath, setActivePath] = useState<string | null>(null);
  const [runResult, setRunResult] = useState<RunResult | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const [showFileExplorer, setShowFileExplorer] = useState(true);
  const [showAiPanel, setShowAiPanel] = useState(true);
  const [importing, setImporting] = useState(false);
  const [importStatus, setImportStatus] = useState<string | null>(null);
  const [githubDialogOpen, setGithubDialogOpen] = useState(false);
  const [inviteCopied, setInviteCopied] = useState(false);
  const [leftSidebarWidth, setLeftSidebarWidth] = useState(() =>
    readStoredWidth("codeleon.leftSidebarWidth", 272, 192, 448),
  );
  const [rightSidebarWidth, setRightSidebarWidth] = useState(() =>
    readStoredWidth("codeleon.rightSidebarWidth", 320, 256, 544),
  );
  const [isCompactWorkspace, setIsCompactWorkspace] = useState(false);

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

  // Close a tab and adjust the active selection.
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
      return next;
    });
  }, []);

  // Menubar handlers.
  const closeActiveTab = useCallback(() => {
    if (activePath) closeTab(activePath);
  }, [activePath, closeTab]);

  const closeAllTabs = useCallback(() => {
    setOpenPaths([]);
    setActivePath(null);
  }, []);

  const onFind = useCallback(() => editorRef.current?.openFind(), []);
  const onReplace = useCallback(() => editorRef.current?.openReplace(), []);
  const onFormatDocument = useCallback(() => editorRef.current?.formatDocument(), []);

  const onNewFile = useCallback(() => fileExplorerRef.current?.openNewFileInput(), []);
  const onRunFile = useCallback(() => runMutation.mutate(), [runMutation]);
  const onEditorReadyChange = useCallback((ready: boolean) => {
    setEditorReady(ready);
  }, []);
  const refetchRoomFilesForIndex = useCallback(() => {
    void roomFilesQuery.refetch();
  }, [roomFilesQuery]);

  const beginSidebarResize = useCallback(
    (side: "left" | "right") => (event: PointerEvent<HTMLDivElement>) => {
      event.preventDefault();
      event.currentTarget.setPointerCapture(event.pointerId);
      const bounds = workspaceRef.current?.getBoundingClientRect();
      if (!bounds) return;

      const previousUserSelect = document.body.style.userSelect;
      const previousCursor = document.body.style.cursor;
      document.body.style.userSelect = "none";
      document.body.style.cursor = "col-resize";

      const onPointerMove = (moveEvent: globalThis.PointerEvent) => {
        const availableWidth = bounds.width;
        if (side === "left") {
          const otherWidth = showAiPanel && !isCompactWorkspace ? rightSidebarWidth : 0;
          const maxWidth = Math.min(
            LEFT_SIDEBAR_MAX,
            availableWidth - otherWidth - MIN_EDITOR_WIDTH,
          );
          setLeftSidebarWidth(
            clamp(moveEvent.clientX - bounds.left, LEFT_SIDEBAR_MIN, maxWidth),
          );
        } else {
          const otherWidth = showFileExplorer && !isCompactWorkspace ? leftSidebarWidth : 0;
          const maxWidth = Math.min(
            RIGHT_SIDEBAR_MAX,
            availableWidth - otherWidth - MIN_EDITOR_WIDTH,
          );
          setRightSidebarWidth(
            clamp(bounds.right - moveEvent.clientX, RIGHT_SIDEBAR_MIN, maxWidth),
          );
        }
      };

      const onPointerUp = () => {
        document.body.style.userSelect = previousUserSelect;
        document.body.style.cursor = previousCursor;
        window.removeEventListener("pointermove", onPointerMove);
        window.removeEventListener("pointerup", onPointerUp);
      };

      window.addEventListener("pointermove", onPointerMove);
      window.addEventListener("pointerup", onPointerUp, { once: true });
    },
    [
      isCompactWorkspace,
      leftSidebarWidth,
      rightSidebarWidth,
      showAiPanel,
      showFileExplorer,
    ],
  );
  const resizeLeftSidebarBy = useCallback((delta: number) => {
    setLeftSidebarWidth((current) => clamp(current + delta, LEFT_SIDEBAR_MIN, LEFT_SIDEBAR_MAX));
  }, []);
  const resizeRightSidebarBy = useCallback((delta: number) => {
    setRightSidebarWidth((current) => clamp(current + delta, RIGHT_SIDEBAR_MIN, RIGHT_SIDEBAR_MAX));
  }, []);

  // Bulk-import a folder picked via FileExplorer's Upload button.
  // For every prepared file we (1) create the RoomFile metadata via REST
  // (so it appears in the explorer and gets a stable id + language), then
  // (2) write its content into the Y.Doc's Y.Text(path) so the Yjs sync
  // and snapshot persistence both pick it up automatically.
  const handleImportLocal = useCallback(
    async (fileList: FileList) => {
      if (!roomId || importing) return;
      setImporting(true);
      setImportStatus("Reading files...");

      try {
        const report = await prepareLocalImport(fileList);
        if (report.prepared.length === 0) {
          setImportStatus(
            `No file imported (${report.skipped.length} skipped). Check size, type, and folder filters.`,
          );
          return;
        }

        let success = 0;
        let conflicts = 0;
        let failed = 0;

        for (let i = 0; i < report.prepared.length; i += 1) {
          const { path, content } = report.prepared[i];
          setImportStatus(
            `Importing ${i + 1}/${report.prepared.length}: ${path}`,
          );
          try {
            await createRoomFile(roomId, path);
          } catch (ex) {
            const message = ex instanceof Error ? ex.message : String(ex);
            if (
              ex instanceof AxiosError &&
              ex.response?.status === 400 &&
              /already exists/i.test(
                (ex.response.data as { message?: string })?.message ?? "",
              )
            ) {
              conflicts += 1;
            } else {
              failed += 1;
              console.warn(`Import failed for ${path}: ${message}`);
              continue;
            }
          }
          // Seed the Y.Text with the file's content. Yjs broadcasts the
          // update over the WS and the snapshot debounce in
          // useCollabRoom persists it to Postgres.
          const yText = collab.ydoc.getText(path);
          if (yText.length === 0) {
            yText.insert(0, content);
          }
          success += 1;
        }

        const parts = [
          `${success} imported`,
          conflicts > 0 ? `${conflicts} already existed` : null,
          report.skipped.length > 0 ? `${report.skipped.length} skipped` : null,
          report.truncated ? "list truncated at 200 files" : null,
          failed > 0 ? `${failed} failed` : null,
        ].filter(Boolean);
        setImportStatus(parts.join(" · "));

        if (success > 0) {
          // Re-fetch the file list so the new files actually show up,
          // then open the first one as a tab.
          await fileExplorerRef.current?.refresh();
          await roomFilesQuery.refetch();
          openFile(report.prepared[0].path);
        }
      } finally {
        setImporting(false);
      }
    },
    [roomId, importing, collab.ydoc, openFile, roomFilesQuery],
  );

  // Seed the Y.Doc with the contents the backend extracted from the
  // GitHub archive. Backend already created the RoomFile rows, so we
  // only need to push text into the matching Y.Texts and refresh the
  // explorer.
  const handleGithubImported = useCallback(
    async (response: GithubImportResponse) => {
      for (const file of response.imported) {
        const yText = collab.ydoc.getText(file.path);
        if (yText.length === 0) {
          yText.insert(0, file.content);
        }
      }
      if (response.imported.length > 0) {
        await fileExplorerRef.current?.refresh();
        await roomFilesQuery.refetch();
        openFile(response.imported[0].path);
        setImportStatus(
          `Imported ${response.imported.length} file${response.imported.length === 1 ? "" : "s"} from ${response.owner}/${response.repo}@${response.branchUsed}`,
        );
      }
    },
    [collab.ydoc, openFile, roomFilesQuery],
  );

  const handleFileCreated = useCallback(
    (file: RoomFile) => {
      collab.ydoc.getText(file.path);
      refetchRoomFilesForIndex();
    },
    [collab.ydoc, refetchRoomFilesForIndex],
  );

  const handleFileRenamed = useCallback(
    (oldPath: string, file: RoomFile) => {
      if (oldPath === file.path) {
        refetchRoomFilesForIndex();
        return;
      }

      const oldText = collab.ydoc.getText(oldPath);
      const newText = collab.ydoc.getText(file.path);
      const oldContent = oldText.toString();

      collab.ydoc.transact(() => {
        if (newText.length === 0 && oldContent.length > 0) {
          newText.insert(0, oldContent);
        }
        if (oldText.length > 0) {
          oldText.delete(0, oldText.length);
        }
      }, "file-rename");

      setOpenPaths((paths) =>
        paths.map((path) => (path === oldPath ? file.path : path)),
      );
      setActivePath((path) => (path === oldPath ? file.path : path));
      refetchRoomFilesForIndex();
    },
    [collab.ydoc, refetchRoomFilesForIndex],
  );

  const handleFileDeleted = useCallback(
    (file: RoomFile) => {
      const yText = collab.ydoc.getText(file.path);
      if (yText.length > 0) {
        yText.delete(0, yText.length);
      }
      closeTab(file.path);
      refetchRoomFilesForIndex();
    },
    [closeTab, collab.ydoc, refetchRoomFilesForIndex],
  );

  useEffect(() => {
    localStorage.setItem("codeleon.leftSidebarWidth", String(leftSidebarWidth));
  }, [leftSidebarWidth]);

  useEffect(() => {
    localStorage.setItem("codeleon.rightSidebarWidth", String(rightSidebarWidth));
  }, [rightSidebarWidth]);

  useEffect(() => {
    const syncWorkspaceSize = () => {
      const width = workspaceRef.current?.clientWidth ?? window.innerWidth;
      const compact = width < 1024;
      setIsCompactWorkspace(compact);
      if (compact) return;

      setLeftSidebarWidth((current) => {
        const maxWidth =
          width -
          (showAiPanel ? rightSidebarWidth : 0) -
          MIN_EDITOR_WIDTH;
        return clamp(current, LEFT_SIDEBAR_MIN, Math.min(LEFT_SIDEBAR_MAX, maxWidth));
      });
      setRightSidebarWidth((current) => {
        const maxWidth =
          width -
          (showFileExplorer ? leftSidebarWidth : 0) -
          MIN_EDITOR_WIDTH;
        return clamp(current, RIGHT_SIDEBAR_MIN, Math.min(RIGHT_SIDEBAR_MAX, maxWidth));
      });
    };

    syncWorkspaceSize();
    window.addEventListener("resize", syncWorkspaceSize);
    return () => window.removeEventListener("resize", syncWorkspaceSize);
  }, [leftSidebarWidth, rightSidebarWidth, showAiPanel, showFileExplorer]);

  const getEditorText = useCallback(() => editorRef.current?.getValue() ?? "", []);

  // Snapshot every file's current content for whole-project indexing.
  // Content is read from the Y.Doc. The CodeMirror Yjs binding keeps each
  // Y.Text in sync with its editor in real time, so even files with no
  // open tab carry their up-to-date text here. Empty files are included so
  // the backend can clear stale chunks when a project is renamed, deleted,
  // or emptied after an earlier index.
  const getAllFiles = useCallback((): IndexFile[] => {
    const files = roomFilesQuery.data ?? [];
    return files.map((file) => ({
      path: file.path,
      text: collab.ydoc.getText(file.path).toString(),
    }));
  }, [roomFilesQuery.data, collab.ydoc]);

  useEffect(() => {
    collab.setActivePath(activePath);
  }, [activePath, collab.setActivePath]);

  if (!roomId) {
    return <Navigate to="/dashboard" replace />;
  }

  return (
    <main className="flex h-screen flex-col overflow-hidden bg-background text-zinc-100">
      <header className="flex min-h-16 items-center justify-between border-b border-zinc-800 bg-background/95 px-4 backdrop-blur">
        <div className="flex min-w-0 items-center gap-4">
          <Button asChild variant="ghost" className="px-2">
            <Link to="/dashboard" aria-label="Back to dashboard">
              <ArrowLeft className="h-4 w-4" />
            </Link>
          </Button>
          <Link to="/" className="hidden items-center gap-3 sm:flex">
            <Logo size={36} />
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
            onClick={() => {
              if (!room?.inviteCode) return;
              const fallback = () => {
                const ta = document.createElement("textarea");
                ta.value = room.inviteCode;
                ta.style.position = "fixed";
                ta.style.opacity = "0";
                document.body.appendChild(ta);
                ta.select();
                try {
                  document.execCommand("copy");
                } finally {
                  document.body.removeChild(ta);
                }
              };
              const showCopied = () => {
                setInviteCopied(true);
                window.setTimeout(() => setInviteCopied(false), 1800);
              };
              if (navigator.clipboard?.writeText) {
                navigator.clipboard
                  .writeText(room.inviteCode)
                  .then(showCopied)
                  .catch(() => {
                    fallback();
                    showCopied();
                  });
              } else {
                fallback();
                showCopied();
              }
            }}
            disabled={!room}
            title={room?.inviteCode ? `Copy invite code: ${room.inviteCode}` : "Loading invite code..."}
          >
            {inviteCopied ? <Check className="h-4 w-4 text-success" /> : <Copy className="h-4 w-4" />}
            {inviteCopied ? "Copied!" : "Invite"}
          </Button>
          <Button
            onClick={() => runMutation.mutate()}
            disabled={!activePath || !editorReady || runMutation.isPending}
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

      <MenuBar
        onNewFile={onNewFile}
        onImportGithub={() => setGithubDialogOpen(true)}
        onCloseTab={closeActiveTab}
        onCloseAllTabs={closeAllTabs}
        onFind={onFind}
        onReplace={onReplace}
        onFormatDocument={onFormatDocument}
        onToggleFileExplorer={() => setShowFileExplorer((v) => !v)}
        onToggleAiPanel={() => setShowAiPanel((v) => !v)}
        onRunFile={onRunFile}
        isFileExplorerVisible={showFileExplorer}
        isAiPanelVisible={showAiPanel}
        hasActiveTab={activePath !== null}
        hasOpenTabs={openPaths.length > 0}
        canEdit={canEdit}
      />

      <ImportGithubDialog
        open={githubDialogOpen}
        onOpenChange={setGithubDialogOpen}
        roomId={roomId}
        onImported={handleGithubImported}
      />

      <section
        ref={workspaceRef}
        className="relative grid min-h-0 flex-1 grid-cols-1 overflow-hidden"
        style={{
          gridTemplateColumns: isCompactWorkspace
            ? "minmax(0, 1fr)"
            : [
            showFileExplorer ? `${leftSidebarWidth}px` : null,
            showFileExplorer ? "4px" : null,
            "minmax(0, 1fr)",
            showAiPanel ? "4px" : null,
            showAiPanel ? `${rightSidebarWidth}px` : null,
          ]
            .filter(Boolean)
            .join(" "),
        }}
      >
        {showFileExplorer && !isCompactWorkspace && (
          <aside className="flex min-h-0 flex-col gap-2 overflow-y-auto border-r border-zinc-800 bg-surface/70 p-4">
            <FileExplorer
              ref={fileExplorerRef}
              roomId={roomId}
              activePath={activePath}
              onActivePathChange={openFile}
              canEdit={canEdit}
              onFileCreated={handleFileCreated}
              onFileRenamed={handleFileRenamed}
              onFileDeleted={handleFileDeleted}
              onImportLocal={handleImportLocal}
              importing={importing}
            />
            {importStatus && (
              <p className="rounded border border-zinc-800 bg-zinc-950 px-2 py-1.5 text-[11px] text-zinc-400">
                {importStatus}
              </p>
            )}
          </aside>
        )}

        {showFileExplorer && !isCompactWorkspace && (
          <ResizeHandle
            label="Resize file explorer"
            onPointerDown={beginSidebarResize("left")}
            onKeyboardResize={resizeLeftSidebarBy}
          />
        )}

        <section className="flex min-h-0 flex-col overflow-hidden bg-zinc-950">
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
            <CodeMirrorEditor
              ref={editorRef}
              activePath={activePath}
              ydoc={collab.ydoc}
              awareness={collab.awareness}
              canEdit={canEdit}
              onReadyChange={onEditorReadyChange}
            />
          </div>
          <OutputPanel
            isPending={runMutation.isPending}
            result={runResult}
            error={runError}
          />
        </section>

        {showAiPanel && !isCompactWorkspace && (
          <ResizeHandle
            label="Resize AI assistant"
            onPointerDown={beginSidebarResize("right")}
            onKeyboardResize={(delta) => resizeRightSidebarBy(-delta)}
          />
        )}

        {showAiPanel && !isCompactWorkspace && (
          <aside className="grid min-h-0 overflow-hidden border-l border-zinc-800 bg-surface/70 grid-rows-[auto_1fr]">
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
                      activePath={peer.activePath}
                      isMe={peer.userId === currentUser?.id}
                    />
                  ))
                )}
              </div>
            </section>

            <section className="flex min-h-0 flex-col p-4">
              <ChatPanel
                roomId={roomId}
                getEditorText={getEditorText}
                getAllFiles={getAllFiles}
                activeFilePath={activePath}
                lastRunStderr={runResult?.stderr?.trim() ? runResult.stderr : runError}
                isOwner={room?.currentUserRole === "OWNER"}
              />
            </section>
          </aside>
        )}

        {showFileExplorer && isCompactWorkspace && (
          <aside className="absolute inset-y-0 left-0 z-30 flex w-[min(82vw,22rem)] min-h-0 flex-col gap-2 overflow-y-auto border-r border-zinc-800 bg-surface p-4 shadow-glow">
            <FileExplorer
              ref={fileExplorerRef}
              roomId={roomId}
              activePath={activePath}
              onActivePathChange={openFile}
              canEdit={canEdit}
              onFileCreated={handleFileCreated}
              onFileRenamed={handleFileRenamed}
              onFileDeleted={handleFileDeleted}
              onImportLocal={handleImportLocal}
              importing={importing}
            />
            {importStatus && (
              <p className="rounded border border-zinc-800 bg-zinc-950 px-2 py-1.5 text-[11px] text-zinc-400">
                {importStatus}
              </p>
            )}
          </aside>
        )}

        {showAiPanel && isCompactWorkspace && (
          <aside className="absolute inset-y-0 right-0 z-30 grid w-[min(88vw,24rem)] min-h-0 overflow-hidden border-l border-zinc-800 bg-surface shadow-glow grid-rows-[auto_1fr]">
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
                      activePath={peer.activePath}
                      isMe={peer.userId === currentUser?.id}
                    />
                  ))
                )}
              </div>
            </section>

            <section className="flex min-h-0 flex-col p-4">
              <ChatPanel
                roomId={roomId}
                getEditorText={getEditorText}
                getAllFiles={getAllFiles}
                activeFilePath={activePath}
                lastRunStderr={runResult?.stderr?.trim() ? runResult.stderr : runError}
                isOwner={room?.currentUserRole === "OWNER"}
              />
            </section>
          </aside>
        )}
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

function Participant({
  name,
  color,
  activePath,
  isMe,
}: {
  name: string;
  color: string;
  activePath: string | null;
  isMe?: boolean;
}) {
  return (
    <div className="rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
        <span
          className="h-2.5 w-2.5 rounded-full"
          style={{ backgroundColor: color }}
          aria-hidden
        />
          <span className="truncate text-sm text-zinc-200">{name}</span>
        </div>
        <span className="shrink-0 text-xs text-zinc-500">{isMe ? "you" : "online"}</span>
      </div>
      <p className="mt-1 flex min-w-0 items-center gap-1.5 text-xs text-zinc-500">
        <FileText className="h-3 w-3 shrink-0" />
        <span className="truncate">{activePath ?? "No file open"}</span>
      </p>
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

function ResizeHandle({
  label,
  onPointerDown,
  onKeyboardResize,
}: {
  label: string;
  onPointerDown: (event: PointerEvent<HTMLDivElement>) => void;
  onKeyboardResize: (delta: number) => void;
}) {
  return (
    <div
      role="separator"
      aria-label={label}
      tabIndex={0}
      aria-orientation="vertical"
      onPointerDown={onPointerDown}
      onKeyDown={(event) => {
        if (event.key === "ArrowLeft") {
          event.preventDefault();
          onKeyboardResize(-16);
        }
        if (event.key === "ArrowRight") {
          event.preventDefault();
          onKeyboardResize(16);
        }
      }}
      className="group relative min-h-0 cursor-col-resize bg-zinc-950 outline-none focus-visible:bg-cyan/20"
    >
      <span className="absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-zinc-800 transition group-hover:bg-cyan" />
    </div>
  );
}

const LEFT_SIDEBAR_MIN = 192;
const LEFT_SIDEBAR_MAX = 448;
const RIGHT_SIDEBAR_MIN = 256;
const RIGHT_SIDEBAR_MAX = 544;
const MIN_EDITOR_WIDTH = 420;

function readStoredWidth(
  key: string,
  fallback: number,
  min: number,
  max: number,
): number {
  if (typeof window === "undefined") return fallback;
  const parsed = Number(window.localStorage.getItem(key));
  return Number.isFinite(parsed) ? clamp(parsed, min, max) : fallback;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}
