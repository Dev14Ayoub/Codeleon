import { useMutation, useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { AnimatePresence, motion } from "framer-motion";
import {
  ArrowLeft,
  Bot,
  Check,
  Copy,
  Database,
  Eye,
  FileText,
  Loader2,
  Play,
  ShieldCheck,
  Terminal,
  Users,
  Wifi,
  WifiOff,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState, type PointerEvent, type ReactNode } from "react";
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
  runProject,
  type GithubImportResponse,
  type IndexFile,
  detectProjectRun,
  type ProjectRunResult,
  type RoomFile,
  type RunLanguage,
  type RunProjectFile,
  type RunResult,
} from "@/lib/api";
import { prepareLocalImport } from "@/lib/files/local-import";
import { languageDisplayName, languageFromPath } from "@/lib/files/file-language";
import { useCollabRoom, type CollabPeer } from "@/lib/collab/useCollabRoom";
import { useAuthStore } from "@/stores/auth-store";

type RightPanelTab = "ai" | "participants" | "review";

interface ProjectRunEnvironment {
  label: string;
  defaultCommand: string;
}

interface RunContext {
  kind: "file" | "project";
  label: string;
  command?: string;
  environment?: string;
}

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
  const setCollabActivePath = collab.setActivePath;
  const currentUser = useAuthStore((state) => state.user);

  const editorRef = useRef<CodeMirrorEditorHandle | null>(null);
  const workspaceRef = useRef<HTMLElement | null>(null);
  const fileExplorerRef = useRef<FileExplorerHandle | null>(null);
  const [editorReady, setEditorReady] = useState(false);
  const [openPaths, setOpenPaths] = useState<string[]>([]);
  const [activePath, setActivePath] = useState<string | null>(null);
  const [runStdin, setRunStdin] = useState("");
  const [projectRunCommand, setProjectRunCommand] = useState("");
  const [runResult, setRunResult] = useState<RunResult | null>(null);
  const [projectRunResult, setProjectRunResult] = useState<ProjectRunResult | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const [runContext, setRunContext] = useState<RunContext | null>(null);
  const [showFileExplorer, setShowFileExplorer] = useState(true);
  const [showAiPanel, setShowAiPanel] = useState(true);
  const [importing, setImporting] = useState(false);
  const [importStatus, setImportStatus] = useState<string | null>(null);
  const [githubDialogOpen, setGithubDialogOpen] = useState(false);
  const [inviteCopied, setInviteCopied] = useState(false);
  const [rightPanelTab, setRightPanelTab] = useState<RightPanelTab>("ai");
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
  const activeRunLanguage = runLanguageFromPath(activePath);
  const activeRunLanguageLabel = activeRunLanguage ? runLanguageLabel(activeRunLanguage) : null;
  const canRunActiveFile = Boolean(activePath && editorReady && activeRunLanguage);

  const getAllFiles = useCallback((): IndexFile[] => {
    const files = roomFilesQuery.data ?? [];
    return files.map((file) => ({
      path: file.path,
      text: collab.ydoc.getText(file.path).toString(),
    }));
  }, [roomFilesQuery.data, collab.ydoc]);
  const localProjectEnvironment = detectProjectRunEnvironment(getAllFiles());
  const projectDetectionQuery = useQuery({
    queryKey: [
      "rooms",
      roomId,
      "run-project-detect",
      roomFilesQuery.data?.map((file) => `${file.path}:${file.updatedAt}`).join("|") ?? "",
    ],
    queryFn: () =>
      detectProjectRun(roomId ?? "", {
        files: buildProjectDetectionFiles(getAllFiles()),
      }),
    enabled: Boolean(roomId && canEdit && roomFilesQuery.data && roomFilesQuery.data.length > 0),
    staleTime: 5_000,
  });
  const backendProjectEnvironment =
    projectDetectionQuery.data?.runnable &&
    projectDetectionQuery.data.environment &&
    projectDetectionQuery.data.command
      ? {
          label: projectDetectionQuery.data.environment,
          defaultCommand: projectDetectionQuery.data.command,
        }
      : null;
  const projectEnvironment =
    projectDetectionQuery.data && !projectDetectionQuery.data.runnable
      ? null
      : backendProjectEnvironment ?? localProjectEnvironment;
  const projectDetectionMessage = projectDetectionQuery.data?.message ?? null;
  const projectCommandForDisplay =
    projectRunCommand.trim() || projectEnvironment?.defaultCommand || "";
  const canRunProject = Boolean(projectEnvironment && getAllFiles().length > 0);

  const runMutation = useMutation({
    mutationFn: () => {
      const language = runLanguageFromPath(activePath);
      if (!language) {
        throw new Error("Codeleon can run Python and Java files right now.");
      }
      const code = editorRef.current?.getValue() ?? "";
      setRunContext({
        kind: "file",
        label: activeRunLanguage ? runLanguageLabel(activeRunLanguage) : "File",
      });
      return runCode(roomId ?? "", {
        language,
        code,
        filename: activePath ?? undefined,
        stdin: runStdin,
        files: language === "JAVA" ? buildRunProjectFiles(getAllFiles(), activePath, code) : undefined,
      });
    },
    onSuccess: (data) => {
      setRunResult(data);
      setProjectRunResult(null);
      setRunError(null);
    },
    onError: (error: unknown) => {
      setRunResult(null);
      setProjectRunResult(null);
      if (error instanceof AxiosError) {
        const message =
          (error.response?.data as { message?: string } | undefined)?.message ?? error.message;
        setRunError(message);
      } else {
        setRunError(error instanceof Error ? error.message : "Failed to run code");
      }
    },
  });

  const projectRunMutation = useMutation({
    mutationFn: () => {
      if (!projectEnvironment) {
        throw new Error("No Nix-compatible project environment detected.");
      }
      const activeCode = editorRef.current?.getValue() ?? "";
      const command = projectRunCommand.trim();
      setRunContext({
        kind: "project",
        label: "Project",
        command: command || projectEnvironment.defaultCommand,
        environment: projectEnvironment.label,
      });
      return runProject(roomId ?? "", {
        command: command || undefined,
        files: buildRunProjectFiles(getAllFiles(), activePath, activeCode),
      });
    },
    onSuccess: (data) => {
      setRunResult(data);
      setProjectRunResult(data);
      setRunContext({
        kind: "project",
        label: "Project",
        command: data.command,
        environment: data.environment,
      });
      setRunError(null);
    },
    onError: (error: unknown) => {
      setRunResult(null);
      setProjectRunResult(null);
      if (error instanceof AxiosError) {
        const message =
          (error.response?.data as { message?: string } | undefined)?.message ?? error.message;
        setRunError(message);
      } else {
        setRunError(error instanceof Error ? error.message : "Failed to run project");
      }
    },
  });

  const isRunPending = runMutation.isPending || projectRunMutation.isPending;

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
  const onRunFile = useCallback(() => {
    if (!activeRunLanguage) {
      setRunResult(null);
      setProjectRunResult(null);
      setRunError("Codeleon can run Python and Java files right now.");
      setRunContext(null);
      return;
    }
    runMutation.mutate();
  }, [activeRunLanguage, runMutation]);
  const onRunProject = useCallback(() => {
    projectRunMutation.mutate();
  }, [projectRunMutation]);
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

  useEffect(() => {
    setCollabActivePath(activePath);
  }, [activePath, setCollabActivePath]);

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
            disabled={!canRunActiveFile || isRunPending}
            title={
              activePath && !activeRunLanguage
                ? "Codeleon can run Python and Java files right now."
                : activeRunLanguageLabel
                  ? `Run ${activeRunLanguageLabel} file`
                  : "Open a Python or Java file to run it"
            }
          >
            {runMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Play className="h-4 w-4" />
            )}
            Run file
          </Button>
          <Button
            variant="secondary"
            onClick={() => projectRunMutation.mutate()}
            disabled={!canRunProject || isRunPending}
            title={
              projectEnvironment
                ? `Run project with ${projectEnvironment.label}`
                : "Add a flake.nix, pom.xml, package.json, requirements.txt, or pyproject.toml"
            }
          >
            {projectRunMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Terminal className="h-4 w-4" />
            )}
            Run project
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
        onRunProject={onRunProject}
        isFileExplorerVisible={showFileExplorer}
        isAiPanelVisible={showAiPanel}
        hasActiveTab={activePath !== null}
        hasOpenTabs={openPaths.length > 0}
        canRunActiveFile={canRunActiveFile && !isRunPending}
        canRunProject={canRunProject && !isRunPending}
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
          <WorkspaceStatusStrip
            connected={collab.isConnected}
            isReady={collab.isReady}
            activePath={activePath}
            peersCount={collab.peers.length}
            runPending={isRunPending}
            runLanguageLabel={activeRunLanguageLabel}
            projectEnvironmentLabel={projectEnvironment?.label ?? null}
            projectDetectionMessage={projectDetectionMessage}
          />
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
            isPending={isRunPending}
            result={runResult}
            projectResult={projectRunResult}
            error={runError}
            activePath={activePath}
            languageLabel={activeRunLanguageLabel}
            runContext={runContext}
            stdin={runStdin}
            onStdinChange={setRunStdin}
            projectEnvironment={projectEnvironment}
            projectDetectionMessage={projectDetectionMessage}
            projectCommand={projectRunCommand}
            projectCommandForDisplay={projectCommandForDisplay}
            onProjectCommandChange={setProjectRunCommand}
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
          <aside className="min-h-0 overflow-hidden border-l border-zinc-800 bg-surface/70">
            <RoomRightPanel
              tab={rightPanelTab}
              onTabChange={setRightPanelTab}
              peers={collab.peers}
              currentUserId={currentUser?.id}
              roomId={roomId}
              activePath={activePath}
              getEditorText={getEditorText}
              getAllFiles={getAllFiles}
              lastRunStderr={runResult?.stderr?.trim() ? runResult.stderr : runError}
              isOwner={room?.currentUserRole === "OWNER"}
            />
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
          <aside className="absolute inset-y-0 right-0 z-30 w-[min(88vw,24rem)] min-h-0 overflow-hidden border-l border-zinc-800 bg-surface shadow-glow">
            <RoomRightPanel
              tab={rightPanelTab}
              onTabChange={setRightPanelTab}
              peers={collab.peers}
              currentUserId={currentUser?.id}
              roomId={roomId}
              activePath={activePath}
              getEditorText={getEditorText}
              getAllFiles={getAllFiles}
              lastRunStderr={runResult?.stderr?.trim() ? runResult.stderr : runError}
              isOwner={room?.currentUserRole === "OWNER"}
            />
          </aside>
        )}
      </section>
    </main>
  );
}

function runLanguageFromPath(path: string | null): RunLanguage | null {
  if (!path) {
    return null;
  }

  switch (languageFromPath(path)) {
    case "python":
      return "PYTHON";
    case "java":
      return "JAVA";
    default:
      return null;
  }
}

function runLanguageLabel(language: RunLanguage) {
  return languageDisplayName(language.toLowerCase());
}

function buildRunProjectFiles(
  files: IndexFile[],
  activePath: string | null,
  activeCode: string,
): RunProjectFile[] {
  const byPath = new Map(files.map((file) => [file.path, file.text]));
  if (activePath) {
    byPath.set(activePath, activeCode);
  }
  return Array.from(byPath, ([path, text]) => ({ path, text }));
}

function buildProjectDetectionFiles(files: IndexFile[]): RunProjectFile[] {
  return files.map((file) => {
    const path = normalizeProjectPath(file.path);
    const needsContent = path === "package.json" || path === "pyproject.toml";
    return { path: file.path, text: needsContent ? file.text : "" };
  });
}

function detectProjectRunEnvironment(files: IndexFile[]): ProjectRunEnvironment | null {
  const normalizedPaths = new Set(files.map((file) => normalizeProjectPath(file.path)));
  if (normalizedPaths.has("flake.nix")) {
    return { label: "Nix flake", defaultCommand: "true" };
  }
  if (normalizedPaths.has("pom.xml")) {
    return { label: "Generated Java/Maven", defaultCommand: "mvn test" };
  }
  const packageJson = files.find((file) => normalizeProjectPath(file.path) === "package.json")?.text;
  if (packageJson !== undefined) {
    const scripts = readPackageScripts(packageJson);
    const installCommand = normalizedPaths.has("package-lock.json") ? "npm ci" : "npm install";
    if (scripts?.test) {
      return { label: "Generated Node", defaultCommand: `${installCommand} && npm test` };
    }
    if (scripts?.build) {
      return { label: "Generated Node", defaultCommand: `${installCommand} && npm run build` };
    }
    return { label: "Generated Node", defaultCommand: installCommand };
  }
  if (normalizedPaths.has("requirements.txt") || normalizedPaths.has("pyproject.toml")) {
    const pyproject = files.find((file) => normalizeProjectPath(file.path) === "pyproject.toml")?.text;
    const installCommand = normalizedPaths.has("requirements.txt")
      ? "python -m pip install -r requirements.txt"
      : pyproject && (pyproject.includes("[project]") || pyproject.includes("[build-system]"))
        ? "python -m pip install ."
        : null;
    const hasTests = Array.from(normalizedPaths).some(
      (path) => path.startsWith("test_") || path.includes("/test_") || path.startsWith("tests/"),
    );
    if (hasTests) {
      return {
        label: "Generated Python",
        defaultCommand: pythonVenvCommand(installCommand, "python -m pytest", true),
      };
    }
    if (normalizedPaths.has("main.py")) {
      return {
        label: "Generated Python",
        defaultCommand: installCommand
          ? pythonVenvCommand(installCommand, "python main.py", false)
          : "python main.py",
      };
    }
    const compileCommand = "python -m py_compile $(find . -name '*.py' -type f)";
    return {
      label: "Generated Python",
      defaultCommand: installCommand
        ? pythonVenvCommand(installCommand, compileCommand, false)
        : compileCommand,
    };
  }
  return null;
}

function pythonVenvCommand(installCommand: string | null, runCommand: string, installPytest: boolean) {
  return [
    "python -m venv .codeleon-venv",
    ". .codeleon-venv/bin/activate",
    installCommand,
    installPytest ? "python -m pip install pytest" : null,
    runCommand,
  ]
    .filter(Boolean)
    .join(" && ");
}

function normalizeProjectPath(path: string) {
  return path.replace(/\\/g, "/").trim().toLowerCase();
}

function readPackageScripts(packageJson: string): Record<string, string> | null {
  try {
    const parsed = JSON.parse(packageJson) as { scripts?: unknown };
    return parsed.scripts && typeof parsed.scripts === "object"
      ? (parsed.scripts as Record<string, string>)
      : null;
  } catch {
    return null;
  }
}

function OutputPanel({
  activePath,
  isPending,
  languageLabel,
  runContext,
  stdin,
  onStdinChange,
  projectEnvironment,
  projectDetectionMessage,
  projectCommand,
  projectCommandForDisplay,
  onProjectCommandChange,
  result,
  projectResult,
  error,
}: {
  activePath: string | null;
  isPending: boolean;
  languageLabel: string | null;
  runContext: RunContext | null;
  stdin: string;
  onStdinChange: (value: string) => void;
  projectEnvironment: ProjectRunEnvironment | null;
  projectDetectionMessage: string | null;
  projectCommand: string;
  projectCommandForDisplay: string;
  onProjectCommandChange: (value: string) => void;
  result: RunResult | null;
  projectResult: ProjectRunResult | null;
  error: string | null;
}) {
  const exitTone = result
    ? result.timedOut
      ? "text-amber-400"
      : result.exitCode === 0
        ? "text-emerald-400"
        : "text-rose-400"
    : "text-zinc-500";
  const isProjectPending = isPending && runContext?.kind === "project";
  const pendingMessage = isProjectPending
    ? `Preparing ${runContext.environment ?? projectEnvironment?.label ?? "Nix environment"} and running ${
        (runContext.command ?? projectCommandForDisplay) || "the project command"
      }...`
    : "Running...";

  return (
    <div className="h-64 border-t border-zinc-800 bg-zinc-950">
      <div className="flex h-9 items-center justify-between border-b border-zinc-800 bg-surface px-4">
        <div className="flex items-center gap-2 font-mono text-xs text-zinc-400">
          <Terminal className="h-3.5 w-3.5 text-zinc-500" />
          {runContext?.kind === "project" ? "Run project" : "Run file"}
        </div>
        <div className={`font-mono text-xs ${exitTone}`}>
          {isPending
            ? isProjectPending
              ? "Preparing Nix..."
              : "Running..."
            : result
              ? result.timedOut
                ? `timed out after ${result.durationMs} ms`
                : `exit ${result.exitCode} - ${result.durationMs} ms`
              : error
                ? "error"
                : "idle"}
        </div>
      </div>
      <div className="grid h-[calc(100%-2.25rem)] min-h-0 grid-cols-[minmax(12rem,0.85fr)_minmax(0,1.5fr)] divide-x divide-zinc-800">
        <div className="flex min-h-0 flex-col">
          <div className="border-b border-zinc-800">
            <label className="flex h-8 items-center px-4 font-mono text-[11px] uppercase tracking-[0.12em] text-zinc-500">
              Project command
            </label>
            <input
              value={projectCommand}
              onChange={(event) => onProjectCommandChange(event.target.value)}
              placeholder={projectEnvironment?.defaultCommand ?? "Detect a project manifest first"}
              spellCheck={false}
              className="h-9 w-full border-t border-zinc-900 bg-zinc-950 px-4 font-mono text-xs text-zinc-200 outline-none placeholder:text-zinc-700 focus:bg-zinc-950/80"
            />
          </div>
          <div className="flex min-h-0 flex-1 flex-col">
            <label className="flex h-8 items-center border-b border-zinc-800 px-4 font-mono text-[11px] uppercase tracking-[0.12em] text-zinc-500">
              Stdin
            </label>
            <textarea
              value={stdin}
              onChange={(event) => onStdinChange(event.target.value)}
              placeholder="Input for Scanner, input(), etc."
              spellCheck={false}
              className="min-h-0 flex-1 resize-none bg-zinc-950 px-4 py-3 font-mono text-xs leading-5 text-zinc-200 outline-none placeholder:text-zinc-700 focus:bg-zinc-950/80"
            />
          </div>
        </div>
        <div className="flex min-h-0 flex-col">
          <div className="flex h-8 items-center justify-between gap-3 border-b border-zinc-800 px-4 font-mono text-[11px] uppercase tracking-[0.12em] text-zinc-500">
            <span>Output</span>
            <span className="min-w-0 truncate normal-case tracking-normal text-zinc-600">
              {projectResult
                ? `${projectResult.environment} / ${projectResult.command}`
                : runContext?.kind === "project"
                  ? `${runContext.environment ?? "Project"} / ${runContext.command ?? projectCommandForDisplay}`
                : projectEnvironment
                  ? `${projectEnvironment.label} / ${projectCommandForDisplay}`
                  : projectDetectionMessage ?? "No project environment"}
            </span>
          </div>
          <pre className="min-h-0 flex-1 overflow-auto whitespace-pre-wrap px-4 py-3 font-mono text-xs leading-5 text-zinc-200">
            {error ? (
              <span className="text-rose-400">{error}</span>
            ) : isPending ? (
              <span className="text-zinc-500">
                {pendingMessage}
                {isProjectPending &&
                  "\nFirst run may download the Nix toolchain and project dependencies. Later runs reuse Docker cache volumes."}
              </span>
            ) : result ? (
              <>
                {projectResult && (
                  <span className="text-cyan">
                    {`Environment: ${projectResult.environment}${projectResult.generatedEnvironment ? " (generated)" : ""}\nCommand: ${projectResult.command}\nRunner: ${projectResult.runnerImage}\nFiles: ${projectResult.fileCount} | Timeout: ${projectResult.timeoutMs} ms\nCaches: ${projectResult.cacheVolumes.join(", ")}\n\n`}
                  </span>
                )}
                {result.stdout}
                {result.stderr && <span className="text-rose-400">{result.stderr}</span>}
              </>
            ) : (
              <span className="text-zinc-600">
                {!activePath
                  ? projectEnvironment
                    ? `Press Run project to execute ${projectCommandForDisplay}.`
                    : projectDetectionMessage ?? "Open a Python or Java file to run it."
                  : languageLabel
                    ? `Press Run file for the current ${languageLabel} file, or Run project for the detected environment.`
                    : projectEnvironment
                      ? `Press Run project to execute ${projectCommandForDisplay}.`
                      : projectDetectionMessage ?? "Codeleon can run Python and Java files right now."}
              </span>
            )}
          </pre>
        </div>
      </div>
    </div>
  );
}

function WorkspaceStatusStrip({
  connected,
  isReady,
  activePath,
  peersCount,
  runPending,
  runLanguageLabel,
  projectEnvironmentLabel,
  projectDetectionMessage,
}: {
  connected: boolean;
  isReady: boolean;
  activePath: string | null;
  peersCount: number;
  runPending: boolean;
  runLanguageLabel: string | null;
  projectEnvironmentLabel: string | null;
  projectDetectionMessage: string | null;
}) {
  return (
    <div className="flex h-9 items-center gap-2 overflow-x-auto border-b border-zinc-800 bg-background/80 px-4 text-[11px] text-zinc-500">
      <StatusItem tone={connected ? "success" : "muted"} icon={connected ? <Wifi className="h-3 w-3" /> : <WifiOff className="h-3 w-3" />}>
        {isReady ? "Live room" : "Connecting"}
      </StatusItem>
      <StatusItem tone="cyan" icon={<Users className="h-3 w-3" />}>
        {peersCount} online
      </StatusItem>
      <StatusItem tone="muted" icon={<FileText className="h-3 w-3" />}>
        {activePath ?? "No file open"}
      </StatusItem>
      <StatusItem tone="cyan" icon={<Database className="h-3 w-3" />}>
        Private AI
      </StatusItem>
      <StatusItem tone={projectEnvironmentLabel ? "success" : "muted"} icon={<Terminal className="h-3 w-3" />}>
        {projectEnvironmentLabel ?? projectDetectionMessage ?? "No project env"}
      </StatusItem>
      <StatusItem tone={runPending ? "warning" : runLanguageLabel ? "success" : "muted"} icon={<ShieldCheck className="h-3 w-3" />}>
        {runPending
          ? `${runLanguageLabel ?? "Sandbox"} running`
          : runLanguageLabel
            ? `${runLanguageLabel} sandbox ready`
            : "Python/Java runner"}
      </StatusItem>
    </div>
  );
}

function StatusItem({
  children,
  icon,
  tone,
}: {
  children: ReactNode;
  icon: JSX.Element;
  tone: "success" | "warning" | "cyan" | "muted";
}) {
  const toneClass = {
    success: "border-emerald-800/60 bg-emerald-950/30 text-emerald-300",
    warning: "border-amber-800/60 bg-amber-950/30 text-amber-300",
    cyan: "border-cyan/30 bg-cyan/10 text-cyan",
    muted: "border-zinc-800 bg-zinc-950 text-zinc-400",
  }[tone];
  return (
    <motion.span
      layout
      className={`inline-flex h-6 shrink-0 items-center gap-1.5 rounded-md border px-2 ${toneClass}`}
    >
      {icon}
      <span className="max-w-[15rem] truncate">{children}</span>
    </motion.span>
  );
}

function RoomRightPanel({
  tab,
  onTabChange,
  peers,
  currentUserId,
  roomId,
  getEditorText,
  getAllFiles,
  activePath,
  lastRunStderr,
  isOwner,
}: {
  tab: RightPanelTab;
  onTabChange: (tab: RightPanelTab) => void;
  peers: CollabPeer[];
  currentUserId: string | undefined;
  roomId: string;
  getEditorText: () => string;
  getAllFiles: () => IndexFile[];
  activePath: string | null;
  lastRunStderr: string | null;
  isOwner: boolean;
}) {
  const tabs: { id: RightPanelTab; label: string; icon: JSX.Element }[] = [
    { id: "ai", label: "AI", icon: <Bot className="h-3.5 w-3.5" /> },
    { id: "participants", label: "People", icon: <Users className="h-3.5 w-3.5" /> },
    ...(isOwner ? [{ id: "review" as const, label: "Review", icon: <Eye className="h-3.5 w-3.5" /> }] : []),
  ];

  return (
    <div className="grid h-full min-h-0 grid-rows-[auto_1fr]">
      <div className="border-b border-zinc-800 bg-surface/90 p-2">
        <div className="grid gap-1 rounded-md border border-zinc-800 bg-zinc-950 p-1" style={{ gridTemplateColumns: `repeat(${tabs.length}, minmax(0, 1fr))` }}>
          {tabs.map((item) => {
            const active = item.id === tab;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => onTabChange(item.id)}
                className={active ? "relative inline-flex h-8 items-center justify-center gap-1.5 rounded-sm text-xs font-medium text-zinc-50" : "inline-flex h-8 items-center justify-center gap-1.5 rounded-sm text-xs text-zinc-500 transition hover:bg-surface hover:text-zinc-200"}
              >
                {active && (
                  <motion.span
                    layoutId="room-right-panel-active-tab"
                    className="absolute inset-0 rounded-sm bg-surfaceRaised shadow-[0_8px_24px_rgba(99,102,241,0.16)]"
                    transition={{ type: "spring", stiffness: 420, damping: 34 }}
                  />
                )}
                <span className="relative z-10 inline-flex items-center gap-1.5">
                  {item.icon}
                  {item.label}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      <div className="min-h-0 overflow-hidden">
        <AnimatePresence mode="wait">
          {tab === "participants" ? (
            <motion.section
              key="participants"
              initial={{ opacity: 0, x: 16 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -16 }}
              transition={{ duration: 0.2 }}
              className="h-full overflow-y-auto p-4"
            >
              <ParticipantsList peers={peers} currentUserId={currentUserId} />
            </motion.section>
          ) : (
            <motion.section
              key={tab}
              initial={{ opacity: 0, x: 16 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -16 }}
              transition={{ duration: 0.2 }}
              className="flex h-full min-h-0 flex-col p-4"
            >
              {tab === "review" && (
                <p className="mb-3 rounded-md border border-cyan/30 bg-cyan/10 px-3 py-2 text-xs text-cyan">
                  Owner review mode: use the chat picker to inspect member AI threads.
                </p>
              )}
              <ChatPanel
                roomId={roomId}
                getEditorText={getEditorText}
                getAllFiles={getAllFiles}
                activeFilePath={activePath}
                lastRunStderr={lastRunStderr}
                isOwner={isOwner}
              />
            </motion.section>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}

function ParticipantsList({
  peers,
  currentUserId,
}: {
  peers: CollabPeer[];
  currentUserId: string | undefined;
}) {
  return (
    <div>
      <div className="mb-4 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-sm font-medium text-zinc-200">
          <Users className="h-4 w-4 text-cyan" />
          Participants
        </div>
        <span className="rounded-md border border-cyan/30 bg-cyan/10 px-2 py-1 text-xs text-cyan">
          {peers.length} online
        </span>
      </div>
      <div className="space-y-3">
        {peers.length === 0 ? (
          <p className="rounded-md border border-zinc-800 bg-zinc-950 px-3 py-4 text-center text-xs text-zinc-500">
            No live participants yet.
          </p>
        ) : (
          peers.map((peer) => (
            <motion.div
              key={peer.clientId}
              layout
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.2 }}
            >
              <Participant
                name={peer.name}
                color={peer.color}
                activePath={peer.activePath}
                isMe={peer.userId === currentUserId}
              />
            </motion.div>
          ))
        )}
      </div>
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
