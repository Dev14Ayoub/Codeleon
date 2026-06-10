import { useMutation, useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { AnimatePresence, motion } from "framer-motion";
import {
  ArrowLeft,
  Bot,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  Database,
  Eye,
  FileText,
  Globe,
  Loader2,
  Maximize2,
  Minimize2,
  PanelLeft,
  PanelRight,
  Play,
  ShieldCheck,
  SquareTerminal,
  Terminal,
  Users,
  Wifi,
  WifiOff,
  X,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState, type PointerEvent, type ReactNode } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import type * as Y from "yjs";
import { Button } from "@/components/ui/button";
import { Logo } from "@/components/brand/Logo";
import { ChatPanel } from "@/components/chat/ChatPanel";
import { RoomChat } from "@/components/chat/RoomChat";
import {
  CodeMirrorEditor,
  type CodeMirrorEditorHandle,
} from "@/components/editor/CodeMirrorEditor";
import { EditorTabs } from "@/components/files/EditorTabs";
import { FileExplorer, type FileExplorerHandle } from "@/components/files/FileExplorer";
import { TerminalPanel } from "@/components/editor/TerminalPanel";
import { PreviewPanel } from "@/components/editor/PreviewPanel";
import { ImportGithubDialog } from "@/components/files/ImportGithubDialog";
import { MenuBar } from "@/components/layout/MenuBar";
import {
  createRoomFile,
  uploadRoomAsset,
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
  services?: string[];
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
    // Wider default (420px) for the AI panel — at the previous 320px the
    // chat bubbles were cramped on laptop screens. Min stays at 280 so
    // users on smaller laptops can still drag it narrower. Existing
    // localStorage values are honored as long as they fall in [min,max].
    readStoredWidth("codeleon.rightSidebarWidth", 420, 280, 640),
  );
  const [isCompactWorkspace, setIsCompactWorkspace] = useState(false);
  // Fullscreen mode for the AI panel — when true the panel detaches
  // from the right sidebar and renders as a fixed overlay covering
  // the viewport. The component instance stays mounted (just its
  // container className changes) so the chat history and streaming
  // state survive the toggle. Esc closes.
  const [aiPanelFullscreen, setAiPanelFullscreen] = useState(false);
  useEffect(() => {
    if (!aiPanelFullscreen) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setAiPanelFullscreen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [aiPanelFullscreen]);

  // Output panel (run terminal). Closed by default — the user opens it
  // explicitly via the strip toggle, AND it auto-opens as soon as a run
  // starts so they see the result. Height persists in localStorage.
  const [outputPanelOpen, setOutputPanelOpen] = useState(false);
  // Which tab the bottom panel shows: the run "Output" or the interactive
  // "Terminal" (xterm.js + sandboxed bash). The terminal only connects while
  // its tab is the visible one (see `active` prop below).
  const [bottomTab, setBottomTab] = useState<"output" | "terminal" | "preview">("output");
  const [outputPanelHeight, setOutputPanelHeight] = useState<number>(() => {
    try {
      const stored = window.localStorage.getItem("codeleon.outputPanelHeight");
      if (stored !== null) {
        const n = Number(stored);
        if (Number.isFinite(n) && n >= 120 && n <= 600) return n;
      }
    } catch {
      // localStorage can throw in some private-mode contexts — fall through.
    }
    return 220;
  });
  useEffect(() => {
    try {
      window.localStorage.setItem("codeleon.outputPanelHeight", String(outputPanelHeight));
    } catch {
      // localStorage access can throw in private mode — ignore.
    }
  }, [outputPanelHeight]);

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

  /**
   * Opens a file in the editor and (optionally) scrolls to a given line.
   * Wired to the chat panel's context drawer so a citation chip jumps
   * the editor straight to the cited code. The scroll runs in a
   * {@code requestAnimationFrame} because the editor remounts when the
   * active path changes — we need the new EditorView to be ready
   * before we issue the dispatch.
   */
  const jumpToFile = useCallback(
    (path: string, line?: number) => {
      if (!path) return;
      setOpenPaths((prev) => (prev.includes(path) ? prev : [...prev, path]));
      setActivePath(path);
      if (line && line > 0) {
        const tryScroll = (retries: number) => {
          const handle = editorRef.current;
          if (handle) handle.gotoLine(line);
          else if (retries > 0) requestAnimationFrame(() => tryScroll(retries - 1));
        };
        requestAnimationFrame(() => tryScroll(10));
      }
    },
    [],
  );

  /**
   * Applies an agent-proposed {find → replace} patch to the Y.Text bound
   * to {@code path}. Re-validates that {@code find} is still present and
   * unambiguous at click time — the file may have changed between the
   * agent's proposal and the user's click. On success the CRDT update
   * propagates to every collaborator via the existing y-websocket
   * provider; no separate save is required.
   */
  const applyPatch = useCallback(
    (path: string, find: string, replace: string): { ok: boolean; reason?: string } => {
      if (!canEdit) {
        return { ok: false, reason: "You do not have edit rights in this room." };
      }
      const yText = collab.ydoc.getText(path);
      const current = yText.toString();
      const first = current.indexOf(find);
      if (first < 0) {
        return { ok: false, reason: "The target text is no longer in the file — the agent's proposal is stale." };
      }
      const last = current.lastIndexOf(find);
      if (first !== last) {
        return { ok: false, reason: "The target text now appears multiple times — refusing to apply." };
      }
      // Single CRDT transaction so collaborators see one atomic update,
      // not a delete followed by an insert at the same position.
      collab.ydoc.transact(() => {
        yText.delete(first, find.length);
        yText.insert(first, replace);
      });
      return { ok: true };
    },
    [canEdit, collab.ydoc],
  );
  const staticPreviewHtml = buildStaticPreviewHtml(getAllFiles());
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
          services: projectDetectionQuery.data.services,
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

  // Auto-open the output panel when a run starts so the user sees the
  // result without having to click. We only OPEN on rising edge — the
  // user can still close the panel mid-run if they want; we won't
  // re-open it unless a new run starts.
  useEffect(() => {
    if (isRunPending) {
      setOutputPanelOpen(true);
      setBottomTab("output");
    }
  }, [isRunPending]);

  // Drag-resize the output panel vertically. Mirrors beginSidebarResize
  // but on the Y axis. Bounded to [120, 600] so the editor always keeps
  // at least ~120px of breathing room.
  const beginOutputResize = useCallback((event: PointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = outputPanelHeight;
    const onMove = (moveEvent: globalThis.PointerEvent) => {
      const delta = startY - moveEvent.clientY;
      setOutputPanelHeight((current) => {
        const next = startHeight + delta;
        if (next < 120) return 120;
        if (next > 600) return 600;
        return next;
      });
    };
    const onUp = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }, [outputPanelHeight]);

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
        if (report.prepared.length === 0 && report.binary.length === 0) {
          setImportStatus(
            `No file imported (${report.skipped.length} skipped). Check size, type, and folder filters.`,
          );
          return;
        }

        let success = 0;
        let conflicts = 0;
        let failed = 0;
        let assets = 0;

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

        // Upload binary assets (images, fonts…) to the room's asset store —
        // these are materialized into the workspace at run/preview time.
        for (let i = 0; i < report.binary.length; i += 1) {
          const { path, file } = report.binary[i];
          setImportStatus(`Importing asset ${i + 1}/${report.binary.length}: ${path}`);
          try {
            await uploadRoomAsset(roomId, path, file);
            assets += 1;
          } catch (ex) {
            failed += 1;
            console.warn(`Asset import failed for ${path}:`, ex);
          }
        }

        const parts = [
          `${success} imported`,
          assets > 0 ? `${assets} assets` : null,
          conflicts > 0 ? `${conflicts} already existed` : null,
          report.skipped.length > 0 ? `${report.skipped.length} skipped` : null,
          report.truncated ? "list truncated at 200 files" : null,
          failed > 0 ? `${failed} failed` : null,
        ].filter(Boolean);
        setImportStatus(parts.join(" · "));

        if (success > 0 || assets > 0) {
          // Re-fetch the file list so the new files actually show up,
          // then open the first one as a tab.
          await fileExplorerRef.current?.refresh();
          await roomFilesQuery.refetch();
          if (report.prepared.length > 0) {
            openFile(report.prepared[0].path);
          }
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
      <header className="flex min-h-12 items-center justify-between gap-2 border-b border-zinc-800 bg-background/95 px-3 backdrop-blur sm:min-h-13 sm:px-4">
        <div className="flex min-w-0 items-center gap-2 sm:gap-3">
          <Button asChild variant="ghost" className="h-8 w-8 shrink-0 p-0">
            <Link to="/dashboard" aria-label="Back to dashboard">
              <ArrowLeft className="h-4 w-4" />
            </Link>
          </Button>
          <Link to="/" className="hidden items-center gap-2 sm:flex">
            <Logo size={28} />
          </Link>
          <div className="min-w-0">
            {/* Title only — the "Room workspace" sub-label was redundant
                with the page context and added vertical noise. */}
            <h1 className="truncate text-sm font-semibold text-zinc-50">
              {roomQuery.isLoading ? "Loading room…" : room?.name ?? "Room unavailable"}
            </h1>
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          {/* Panel toggles — compact icon buttons replace the menu-bar
              entries that used to live in View > Toggle File Explorer /
              Toggle AI Panel. Always-visible on every screen size: on
              mobile/tablet (<1024px) they open the overlay drawers. */}
          <button
            type="button"
            onClick={() => setShowFileExplorer((v) => !v)}
            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-zinc-800 text-zinc-400 transition hover:border-zinc-700 hover:text-zinc-100"
            title={showFileExplorer ? "Hide file explorer" : "Show file explorer"}
            aria-pressed={showFileExplorer}
          >
            <PanelLeft className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={() => setShowAiPanel((v) => !v)}
            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-zinc-800 text-zinc-400 transition hover:border-zinc-700 hover:text-zinc-100"
            title={showAiPanel ? "Hide AI panel" : "Show AI panel"}
            aria-pressed={showAiPanel}
          >
            <PanelRight className="h-4 w-4" />
          </button>
          <div className="mx-1 hidden md:block h-6 w-px bg-zinc-800" />
          {/* Tiny dot only — the verbose "Live/Offline" pill is now part
              of the bottom status bar. Keep a subtle indicator here so
              the user can see at a glance the connection state without
              looking down. */}
          <span
            className={`hidden md:block h-2 w-2 rounded-full ${
              collab.isConnected ? "bg-emerald-400" : "bg-zinc-600"
            }`}
            aria-label={collab.isConnected ? "Live" : "Disconnected"}
            title={collab.isConnected ? "Live (real-time collab)" : "Disconnected"}
          />
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
            {/* Labels collapse to icon-only on phones to fit the top bar.
                The full label reappears at sm: (>= 640px). */}
            <span className="hidden sm:inline">{inviteCopied ? "Copied!" : "Invite"}</span>
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
            <span className="hidden sm:inline">Run file</span>
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
            {/* Hide "Run project" label on small + medium — it's the secondary
                action so saving space here is fine. Reappears at lg+. */}
            <span className="hidden lg:inline">Run project</span>
          </Button>
        </div>
      </header>

      {/* MenuBar (File/Edit/View/Run/Help) intentionally hidden — its
          actions are now reachable via the top-bar icon buttons (panel
          toggles, Run), the file explorer (+ to create a file), the
          tab close buttons, and standard editor keyboard shortcuts
          (Ctrl/Cmd+F for Find, Ctrl/Cmd+S is auto-saved by Yjs). Keeping
          the MenuBar import in case it is reintroduced behind a setting. */}
      {false && (
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
      )}

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
          // When the AI panel is in fullscreen mode it detaches from the
          // grid and becomes a modal overlay — treat it as absent here so
          // the editor reclaims the right column.
          gridTemplateColumns: isCompactWorkspace
            ? "minmax(0, 1fr)"
            : [
            showFileExplorer ? `${leftSidebarWidth}px` : null,
            showFileExplorer ? "4px" : null,
            "minmax(0, 1fr)",
            (showAiPanel && !aiPanelFullscreen) ? "4px" : null,
            (showAiPanel && !aiPanelFullscreen) ? `${rightSidebarWidth}px` : null,
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
          {/* Tabs only — the redundant "Live/Connecting" pill is now part
              of the status bar at the bottom of this column (VSCode-style). */}
          <div className="flex items-center border-b border-zinc-800 bg-surface">
            <div className="flex-1 min-w-0">
              <EditorTabs
                openPaths={openPaths}
                activePath={activePath}
                onActivate={setActivePath}
                onClose={closeTab}
              />
            </div>
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
          {/* Output strip — always visible at the bottom of the editor
              column. Click to toggle the panel open/closed. Shows the
              last exit code or "running" status as a quick glance.
              Auto-opens when isRunPending flips true (see useEffect). */}
          <OutputStrip
            open={outputPanelOpen}
            onToggle={() => setOutputPanelOpen((v) => !v)}
            isPending={isRunPending}
            exitCode={runResult?.exitCode ?? projectRunResult?.exitCode ?? null}
            timedOut={runResult?.timedOut ?? projectRunResult?.timedOut ?? false}
            durationMs={runResult?.durationMs ?? projectRunResult?.durationMs ?? null}
          />
          {outputPanelOpen && (
            <>
              <div
                onPointerDown={beginOutputResize}
                role="separator"
                aria-orientation="horizontal"
                aria-label="Resize output panel"
                tabIndex={0}
                onKeyDown={(e) => {
                  // Up/Down arrows nudge the panel by 24px so keyboard
                  // users can size it without a mouse.
                  if (e.key === "ArrowUp") {
                    e.preventDefault();
                    setOutputPanelHeight((h) => Math.min(600, h + 24));
                  } else if (e.key === "ArrowDown") {
                    e.preventDefault();
                    setOutputPanelHeight((h) => Math.max(120, h - 24));
                  }
                }}
                className="h-1 shrink-0 cursor-row-resize bg-zinc-800 transition-colors hover:bg-cyan/60 focus:bg-cyan focus:outline-none"
              />
              <div
                style={{ height: outputPanelHeight }}
                className="flex shrink-0 flex-col overflow-hidden border-t border-zinc-800 bg-zinc-950"
              >
                {/* Tab bar — switch between the batch run Output and the
                    interactive Terminal (xterm.js + sandboxed bash). */}
                <div className="flex h-8 shrink-0 items-center gap-1 border-b border-zinc-800 bg-surface px-2">
                  <BottomTabButton
                    active={bottomTab === "output"}
                    onClick={() => setBottomTab("output")}
                    icon={<Terminal className="h-3 w-3" />}
                    label="Output"
                  />
                  <BottomTabButton
                    active={bottomTab === "terminal"}
                    onClick={() => setBottomTab("terminal")}
                    icon={<SquareTerminal className="h-3 w-3" />}
                    label="Terminal"
                  />
                  <BottomTabButton
                    active={bottomTab === "preview"}
                    onClick={() => setBottomTab("preview")}
                    icon={<Globe className="h-3 w-3" />}
                    label="Preview"
                  />
                </div>
                <div className="min-h-0 flex-1">
                  {bottomTab === "output" ? (
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
                      staticPreviewHtml={staticPreviewHtml}
                    />
                  ) : bottomTab === "terminal" ? (
                    <TerminalPanel
                      roomId={roomId}
                      active={outputPanelOpen && bottomTab === "terminal"}
                      getFiles={getAllFiles}
                      activePath={activePath}
                    />
                  ) : (
                    <PreviewPanel
                      roomId={roomId}
                      active={outputPanelOpen && bottomTab === "preview"}
                      getFiles={getAllFiles}
                    />
                  )}
                </div>
              </div>
            </>
          )}
          {/* Status bar at the bottom of the editor column — VSCode pattern.
              Was floating above the editor as a noisy row of chips. */}
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
        </section>

        {/* Resize handle: only visible when the panel is docked in the
            sidebar (not fullscreen, not on compact viewport). */}
        {showAiPanel && !isCompactWorkspace && !aiPanelFullscreen && (
          <ResizeHandle
            label="Resize AI assistant"
            onPointerDown={beginSidebarResize("right")}
            onKeyboardResize={(delta) => resizeRightSidebarBy(-delta)}
          />
        )}

        {/* Docked (non-fullscreen, wide viewport) AI panel sits in the
            grid as a regular column. */}
        {showAiPanel && !isCompactWorkspace && !aiPanelFullscreen && (
          <aside className="min-h-0 overflow-hidden border-l border-zinc-800 bg-surface/70">
            <RoomRightPanel
              tab={rightPanelTab}
              onTabChange={setRightPanelTab}
              peers={collab.peers}
              currentUserId={currentUser?.id}
              currentUserName={currentUser?.fullName}
              ydoc={collab.ydoc}
              roomId={roomId}
              activePath={activePath}
              getEditorText={getEditorText}
              getAllFiles={getAllFiles}
              lastRunStderr={runResult?.stderr?.trim() ? runResult.stderr : runError}
              isOwner={room?.currentUserRole === "OWNER"}
              onApplyPatch={applyPatch}
              onJumpToFile={jumpToFile}
              fullscreen={false}
              onToggleFullscreen={() => setAiPanelFullscreen(true)}
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

        {/* Compact (mobile/tablet) drawer — slides in from the right
            when the user taps the panel toggle. Hidden in fullscreen
            mode because the fullscreen modal already covers everything. */}
        {showAiPanel && isCompactWorkspace && !aiPanelFullscreen && (
          <aside className="absolute inset-y-0 right-0 z-30 w-[min(88vw,24rem)] min-h-0 overflow-hidden border-l border-zinc-800 bg-surface shadow-glow">
            <RoomRightPanel
              tab={rightPanelTab}
              onTabChange={setRightPanelTab}
              peers={collab.peers}
              currentUserId={currentUser?.id}
              currentUserName={currentUser?.fullName}
              ydoc={collab.ydoc}
              roomId={roomId}
              activePath={activePath}
              getEditorText={getEditorText}
              getAllFiles={getAllFiles}
              lastRunStderr={runResult?.stderr?.trim() ? runResult.stderr : runError}
              isOwner={room?.currentUserRole === "OWNER"}
              onApplyPatch={applyPatch}
              onJumpToFile={jumpToFile}
              fullscreen={false}
              onToggleFullscreen={() => setAiPanelFullscreen(true)}
            />
          </aside>
        )}

        {/* Fullscreen overlay — panel detaches from the layout and
            covers the viewport as a modal. Backdrop click closes, Esc
            also closes (wired up in the useEffect above). */}
        {showAiPanel && aiPanelFullscreen && (
          <>
            <button
              type="button"
              aria-label="Close AI assistant"
              onClick={() => setAiPanelFullscreen(false)}
              className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm"
            />
            <aside className="fixed inset-2 z-50 overflow-hidden rounded-lg border border-zinc-800 bg-zinc-950 shadow-[0_24px_80px_rgba(0,0,0,0.6)] sm:inset-6 lg:inset-10">
              <RoomRightPanel
                tab={rightPanelTab}
                onTabChange={setRightPanelTab}
                peers={collab.peers}
                currentUserId={currentUser?.id}
                currentUserName={currentUser?.fullName}
                ydoc={collab.ydoc}
                roomId={roomId}
                activePath={activePath}
                getEditorText={getEditorText}
                getAllFiles={getAllFiles}
                lastRunStderr={runResult?.stderr?.trim() ? runResult.stderr : runError}
                isOwner={room?.currentUserRole === "OWNER"}
                onApplyPatch={applyPatch}
                onJumpToFile={jumpToFile}
                fullscreen={true}
                onToggleFullscreen={() => setAiPanelFullscreen(false)}
              />
            </aside>
          </>
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
    const needsContent =
      path === "package.json" ||
      path === "pyproject.toml" ||
      path === "composer.json" ||
      path === "Gemfile" ||
      path.endsWith(".csproj");
    return { path: file.path, text: needsContent ? file.text : "" };
  });
}

function buildStaticPreviewHtml(files: IndexFile[]): string | null {
  const byPath = new Map(files.map((file) => [normalizeProjectPath(file.path), file.text]));
  const htmlPath = byPath.has("index.html")
    ? "index.html"
    : Array.from(byPath.keys()).find((path) => path.endsWith("/index.html"));
  if (!htmlPath) return null;

  const baseDir = htmlPath.includes("/") ? htmlPath.slice(0, htmlPath.lastIndexOf("/") + 1) : "";
  let html = byPath.get(htmlPath) ?? "";

  // Non-capturing groups for the surrounding attributes — the callback
  // only needs `match` (for the no-replace fallback) and the href/src,
  // never the bytes before or after. Capturing them would keep three
  // positional args alive and trip @typescript-eslint/no-unused-vars.
  html = html.replace(
    /<link\b(?:[^>]*?)href=["']([^"']+\.css)["'](?:[^>]*)>/gi,
    (match, href: string) => {
      const css = byPath.get(resolvePreviewPath(baseDir, href));
      return css === undefined ? match : `<style data-codeleon-href="${escapeHtml(href)}">\n${css}\n</style>`;
    },
  );

  html = html.replace(
    /<script\b(?:[^>]*?)src=["']([^"']+\.js)["'](?:[^>]*)><\/script>/gi,
    (match, src: string) => {
      const js = byPath.get(resolvePreviewPath(baseDir, src));
      return js === undefined ? match : `<script data-codeleon-src="${escapeHtml(src)}">\n${js}\n</script>`;
    },
  );

  return html;
}

function resolvePreviewPath(baseDir: string, rawPath: string) {
  if (/^(https?:)?\/\//i.test(rawPath) || rawPath.startsWith("data:")) return rawPath;
  const normalized = rawPath.startsWith("/") ? rawPath.slice(1) : `${baseDir}${rawPath}`;
  const parts: string[] = [];
  for (const part of normalized.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") parts.pop();
    else parts.push(part);
  }
  return parts.join("/").toLowerCase();
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function detectProjectRunEnvironment(files: IndexFile[]): ProjectRunEnvironment | null {
  const normalizedPaths = new Set(files.map((file) => normalizeProjectPath(file.path)));
  if (normalizedPaths.has("flake.nix")) {
    return { label: "Nix flake", defaultCommand: "true" };
  }
  if (normalizedPaths.has("pom.xml")) {
    return { label: "Generated Java/Maven", defaultCommand: "mvn test" };
  }
  if (normalizedPaths.has("build.gradle") || normalizedPaths.has("build.gradle.kts")) {
    return { label: "Generated Java/Gradle", defaultCommand: "gradle test" };
  }
  const packageJson = files.find((file) => normalizeProjectPath(file.path) === "package.json")?.text;
  if (packageJson !== undefined) {
    const scripts = readPackageScripts(packageJson);
    const installCommand = normalizedPaths.has("package-lock.json") ? "npm ci" : "npm install";
    const services = detectPackageServices(packageJson);
    if (scripts?.test) {
      return { label: "Generated Node", defaultCommand: `${installCommand} && npm test`, services };
    }
    if (scripts?.build) {
      return { label: "Generated Node", defaultCommand: `${installCommand} && npm run build`, services };
    }
    return { label: "Generated Node", defaultCommand: installCommand, services };
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
  if (normalizedPaths.has("cargo.toml")) {
    return { label: "Generated Rust/Cargo", defaultCommand: "cargo test" };
  }
  if (normalizedPaths.has("go.mod")) {
    return { label: "Generated Go", defaultCommand: "go test ./..." };
  }
  if (normalizedPaths.has("cmakelists.txt")) {
    return { label: "Generated C/C++ CMake", defaultCommand: "cmake -S . -B build && cmake --build build" };
  }
  if (normalizedPaths.has("composer.json")) {
    return { label: "Generated PHP/Composer", defaultCommand: "composer install && composer test" };
  }
  if (normalizedPaths.has("gemfile")) {
    return { label: "Generated Ruby/Bundler", defaultCommand: "bundle install && ruby app.rb" };
  }
  if (Array.from(normalizedPaths).some((path) => path.endsWith(".csproj") || path.endsWith(".sln"))) {
    return { label: "Generated .NET", defaultCommand: "dotnet run" };
  }
  const firstSql = Array.from(normalizedPaths).find((path) => path.endsWith(".sql"));
  if (firstSql) {
    return {
      label: "Generated SQLite",
      defaultCommand: normalizedPaths.has("queries.sql") ? "sqlite3 :memory: < queries.sql" : `sqlite3 :memory: < ${firstSql}`,
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

function detectPackageServices(packageJson: string): string[] {
  const lower = packageJson.toLowerCase();
  const services: string[] = [];
  if (lower.includes("\"pg\"") || lower.includes("postgres")) services.push("postgres");
  if (lower.includes("\"mysql2\"") || lower.includes("\"mysql\"") || lower.includes("mariadb")) services.push("mysql");
  if (lower.includes("\"mongodb\"")) services.push("mongodb");
  if (lower.includes("\"redis\"")) services.push("redis");
  return services;
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
  staticPreviewHtml,
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
  staticPreviewHtml: string | null;
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
    <div className="flex h-full flex-col bg-zinc-950">
      <div className="flex h-9 shrink-0 items-center justify-between border-b border-zinc-800 bg-surface px-4">
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
      <div className="grid min-h-0 flex-1 grid-cols-[minmax(12rem,0.85fr)_minmax(0,1.5fr)] divide-x divide-zinc-800">
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
            <span>{staticPreviewHtml && !result && !error && !isPending ? "Preview" : "Output"}</span>
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
          {staticPreviewHtml && !result && !error && !isPending ? (
            <iframe
              title="Static preview"
              sandbox="allow-scripts allow-forms"
              srcDoc={staticPreviewHtml}
              className="min-h-0 flex-1 border-0 bg-white"
            />
          ) : (
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
                    {`Environment: ${projectResult.environment}${projectResult.generatedEnvironment ? " (generated)" : ""}\nCommand: ${projectResult.command}\nRunner: ${projectResult.runnerImage}\nFiles: ${projectResult.fileCount} | Timeout: ${projectResult.timeoutMs} ms\nCaches: ${projectResult.cacheVolumes.join(", ")}\nServices: ${projectResult.services.length > 0 ? projectResult.services.join(", ") : "none"}\n\n`}
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
          )}
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
  // Bottom status bar — VSCode style: thin, flat, separator-delimited.
  // No pill backgrounds, no per-item borders, color is only used to
  // communicate state (green = healthy, amber = pending, red = error).
  return (
    <div className="flex h-7 items-center overflow-x-auto border-t border-zinc-800 bg-surface/80 px-3 text-[11px] text-zinc-400">
      <StatusItem tone={connected ? "success" : "muted"} icon={connected ? <Wifi className="h-3 w-3" /> : <WifiOff className="h-3 w-3" />}>
        {isReady ? "Live" : "Connecting"}
      </StatusItem>
      <StatusSeparator />
      <StatusItem tone="default" icon={<Users className="h-3 w-3" />}>
        {peersCount} {peersCount === 1 ? "online" : "online"}
      </StatusItem>
      <StatusSeparator />
      <StatusItem tone="default" icon={<FileText className="h-3 w-3" />}>
        {activePath ?? "No file"}
      </StatusItem>
      <StatusSeparator />
      <StatusItem tone="default" icon={<Database className="h-3 w-3" />}>
        Private AI
      </StatusItem>
      <StatusSeparator />
      <StatusItem tone={projectEnvironmentLabel ? "success" : "muted"} icon={<Terminal className="h-3 w-3" />}>
        {projectEnvironmentLabel ?? projectDetectionMessage ?? "No project env"}
      </StatusItem>
      <StatusSeparator />
      <StatusItem tone={runPending ? "warning" : runLanguageLabel ? "success" : "muted"} icon={<ShieldCheck className="h-3 w-3" />}>
        {runPending
          ? `${runLanguageLabel ?? "Sandbox"} running`
          : runLanguageLabel
            ? `${runLanguageLabel} ready`
            : "Sandbox idle"}
      </StatusItem>
    </div>
  );
}

function StatusSeparator() {
  return <span className="mx-2 h-3 w-px shrink-0 bg-zinc-800" aria-hidden />;
}

/**
 * Always-visible thin strip at the bottom of the editor area that
 * toggles the output panel open/closed and surfaces the last run's
 * exit status at a glance. Click anywhere on the strip to toggle.
 */
function OutputStrip({
  open,
  onToggle,
  isPending,
  exitCode,
  timedOut,
  durationMs,
}: {
  open: boolean;
  onToggle: () => void;
  isPending: boolean;
  exitCode: number | null;
  timedOut: boolean;
  durationMs: number | null;
}) {
  // The little badge on the right summarises the last run:
  //   • running  — while a mutation is pending
  //   • exit 0 · 295ms (green) — successful run
  //   • exit N · 295ms (red) — non-zero exit
  //   • timeout (amber)
  //   • nothing — no run yet this session
  let badge: { text: string; tone: string } | null = null;
  if (isPending) {
    badge = { text: "running…", tone: "text-cyan" };
  } else if (timedOut) {
    badge = { text: "timed out", tone: "text-amber-400" };
  } else if (exitCode !== null) {
    const tone = exitCode === 0 ? "text-emerald-400" : "text-rose-400";
    const duration = durationMs !== null ? ` · ${durationMs}ms` : "";
    badge = { text: `exit ${exitCode}${duration}`, tone };
  }
  return (
    <button
      type="button"
      onClick={onToggle}
      className="flex h-7 shrink-0 items-center justify-between border-t border-zinc-800 bg-surface/80 px-3 text-[11px] transition hover:bg-surfaceRaised"
      aria-expanded={open}
      aria-controls="output-panel"
      title={open ? "Hide output panel" : "Show output panel"}
    >
      <span className="inline-flex items-center gap-1.5 text-zinc-400">
        {open ? <ChevronDown className="h-3 w-3" /> : <ChevronUp className="h-3 w-3" />}
        <Terminal className="h-3 w-3" />
        <span className="font-medium">Output</span>
        {badge && (
          <>
            <span className="text-zinc-700">·</span>
            <span className={badge.tone}>{badge.text}</span>
          </>
        )}
      </span>
      {open && (
        <span className="text-zinc-500">drag border to resize</span>
      )}
    </button>
  );
}

function BottomTabButton({
  active,
  onClick,
  icon,
  label,
}: {
  active: boolean;
  onClick: () => void;
  icon: ReactNode;
  label: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`inline-flex items-center gap-1.5 rounded px-2.5 py-1 font-mono text-[11px] uppercase tracking-[0.12em] transition ${
        active
          ? "bg-surfaceRaised text-zinc-200"
          : "text-zinc-500 hover:text-zinc-300"
      }`}
    >
      {icon}
      {label}
    </button>
  );
}

function StatusItem({
  children,
  icon,
  tone,
}: {
  children: ReactNode;
  icon: JSX.Element;
  tone: "success" | "warning" | "default" | "muted";
}) {
  // Only foreground color changes per tone — no chip background, no border.
  // This keeps the bar visually flat and lets the editor get the focus.
  const toneClass = {
    success: "text-emerald-400",
    warning: "text-amber-400",
    default: "text-zinc-300",
    muted: "text-zinc-500",
  }[tone];
  return (
    <span className={`inline-flex h-7 shrink-0 items-center gap-1.5 ${toneClass}`}>
      {icon}
      <span className="max-w-[18rem] truncate">{children}</span>
    </span>
  );
}

function RoomRightPanel({
  tab,
  onTabChange,
  peers,
  currentUserId,
  currentUserName,
  ydoc,
  roomId,
  getEditorText,
  getAllFiles,
  activePath,
  lastRunStderr,
  isOwner,
  onApplyPatch,
  onJumpToFile,
  fullscreen,
  onToggleFullscreen,
}: {
  tab: RightPanelTab;
  onTabChange: (tab: RightPanelTab) => void;
  peers: CollabPeer[];
  currentUserId: string | undefined;
  /** Display name of the current user — used by RoomChat to label
   *  outgoing messages. */
  currentUserName: string | undefined;
  /** Shared Y.Doc — RoomChat stores its messages in a Y.Array inside
   *  this same Doc so they ride the existing WS sync + snapshot persistence. */
  ydoc: Y.Doc;
  roomId: string;
  getEditorText: () => string;
  getAllFiles: () => IndexFile[];
  activePath: string | null;
  lastRunStderr: string | null;
  isOwner: boolean;
  onApplyPatch?: (path: string, find: string, replace: string) => { ok: boolean; reason?: string };
  onJumpToFile?: (path: string, line?: number) => void;
  /** True when the panel is rendered as a fullscreen modal (vs docked
   *  in the right sidebar). The header button label and icon flip
   *  accordingly. */
  fullscreen: boolean;
  /** Toggle between docked and fullscreen. The parent owns the state. */
  onToggleFullscreen: () => void;
}) {
  const tabs: { id: RightPanelTab; label: string; icon: JSX.Element }[] = [
    { id: "ai", label: "AI", icon: <Bot className="h-3.5 w-3.5" /> },
    { id: "participants", label: "People", icon: <Users className="h-3.5 w-3.5" /> },
    ...(isOwner ? [{ id: "review" as const, label: "Review", icon: <Eye className="h-3.5 w-3.5" /> }] : []),
  ];

  return (
    <div className="grid h-full min-h-0 grid-rows-[auto_1fr]">
      <div className="flex items-center gap-2 border-b border-zinc-800 bg-surface/90 p-2">
        <div className="flex-1 grid gap-1 rounded-md border border-zinc-800 bg-zinc-950 p-1" style={{ gridTemplateColumns: `repeat(${tabs.length}, minmax(0, 1fr))` }}>
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
        {/* Fullscreen toggle — Maximize2 when docked, Minimize2 when
            already expanded. Same height as the tabs row so it lines
            up cleanly. */}
        <button
          type="button"
          onClick={onToggleFullscreen}
          className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-zinc-800 bg-zinc-950 text-zinc-400 transition hover:border-zinc-700 hover:text-zinc-100"
          title={fullscreen ? "Collapse panel (Esc)" : "Expand panel to full screen"}
          aria-pressed={fullscreen}
          aria-label={fullscreen ? "Collapse AI panel" : "Expand AI panel"}
        >
          {fullscreen ? <Minimize2 className="h-3.5 w-3.5" /> : <Maximize2 className="h-3.5 w-3.5" />}
        </button>
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
              className="flex h-full min-h-0 flex-col gap-3 p-3"
            >
              {/* People tab is split: a compact participants list at the
                  top (capped to ~12rem so the chat gets most of the
                  vertical space), then the human-↔-human room chat that
                  fills the rest. RoomChat stores its messages in a
                  Y.Array inside the shared Y.Doc so it rides on the
                  existing WebSocket sync and snapshot persistence. */}
              <div className="max-h-48 shrink-0 overflow-y-auto rounded-md border border-zinc-800 bg-zinc-950 p-2.5">
                <ParticipantsList peers={peers} currentUserId={currentUserId} />
              </div>
              <div className="flex-1 min-h-0">
                <RoomChat
                  ydoc={ydoc}
                  roomId={roomId}
                  currentUserId={currentUserId}
                  currentUserName={currentUserName}
                  canSend={true}
                />
              </div>
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
                onApplyPatch={onApplyPatch}
                onJumpToFile={onJumpToFile}
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
const RIGHT_SIDEBAR_MIN = 280;
const RIGHT_SIDEBAR_MAX = 640;
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
