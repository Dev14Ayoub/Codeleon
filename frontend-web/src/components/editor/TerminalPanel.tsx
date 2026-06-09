import { useMemo } from "react";
import { Play, RotateCw, TerminalSquare } from "lucide-react";
import { useRoomTerminal, type TerminalFile, type TerminalStatus } from "@/lib/terminal/useRoomTerminal";

/**
 * Maps the active file to the shell command that runs it. The default sandbox
 * image (python:3.12-slim) only ships bash + python, so non-python files map
 * to a best-effort command that will surface a clear error if the runtime is
 * missing — or null to disable the button when there's nothing to run.
 */
function runCommandForPath(path: string | null): string | null {
  if (!path) return null;
  const lower = path.toLowerCase();
  if (lower.endsWith(".py")) return `python ${path}`;
  if (lower.endsWith(".sh")) return `bash ${path}`;
  if (lower.endsWith(".js") || lower.endsWith(".mjs")) return `node ${path}`;
  return null;
}

const STATUS_LABEL: Record<TerminalStatus, string> = {
  idle: "idle",
  connecting: "connecting…",
  ready: "ready",
  exited: "exited",
  error: "error",
};

const STATUS_TONE: Record<TerminalStatus, string> = {
  idle: "text-zinc-500",
  connecting: "text-cyan",
  ready: "text-emerald-400",
  exited: "text-zinc-500",
  error: "text-rose-400",
};

export function TerminalPanel({
  roomId,
  active,
  getFiles,
  activePath,
}: {
  roomId: string | undefined;
  /** Only mount/connect the terminal while its tab is visible. */
  active: boolean;
  getFiles: () => TerminalFile[];
  activePath: string | null;
}) {
  const { containerRef, status, runCommand, restart } = useRoomTerminal({ roomId, active, getFiles });

  const runCommand_ = useMemo(() => runCommandForPath(activePath), [activePath]);
  const canRun = Boolean(runCommand_) && status === "ready";

  return (
    <div className="flex h-full flex-col bg-[#09090b]">
      <div className="flex h-8 shrink-0 items-center justify-between border-b border-zinc-800 bg-surface px-3">
        <div className="flex items-center gap-2 font-mono text-[11px] uppercase tracking-[0.12em] text-zinc-500">
          <TerminalSquare className="h-3.5 w-3.5 text-zinc-500" />
          <span>Terminal</span>
          <span className={STATUS_TONE[status]}>{STATUS_LABEL[status]}</span>
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => runCommand_ && runCommand(runCommand_)}
            disabled={!canRun}
            title={runCommand_ ? `Run ${runCommand_}` : "Open a runnable file to enable this"}
            className="inline-flex items-center gap-1 rounded px-2 py-1 font-mono text-[11px] text-zinc-300 transition hover:bg-surfaceRaised disabled:cursor-not-allowed disabled:text-zinc-700"
          >
            <Play className="h-3 w-3" />
            Run active file
          </button>
          <button
            type="button"
            onClick={restart}
            title="Restart the shell"
            className="inline-flex items-center gap-1 rounded px-2 py-1 font-mono text-[11px] text-zinc-400 transition hover:bg-surfaceRaised"
          >
            <RotateCw className="h-3 w-3" />
            Restart
          </button>
        </div>
      </div>
      <div ref={containerRef} className="min-h-0 flex-1 overflow-hidden px-2 py-1" />
    </div>
  );
}
