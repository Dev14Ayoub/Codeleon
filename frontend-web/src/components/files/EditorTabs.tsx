import { FileCode2, X } from "lucide-react";
import { MouseEvent } from "react";
import { cn } from "@/lib/utils";

interface EditorTabsProps {
  openPaths: string[];
  activePath: string | null;
  onActivate: (path: string) => void;
  onClose: (path: string) => void;
}

/**
 * VS Code-style tab strip displayed above the editor. Each open file
 * gets one chip; the active one is highlighted, all of them carry a
 * close button. Middle-click also closes (matches editor convention).
 */
export function EditorTabs({
  openPaths,
  activePath,
  onActivate,
  onClose,
}: EditorTabsProps) {
  if (openPaths.length === 0) {
    return (
      <div className="flex h-9 items-center border-b border-zinc-800 bg-surface px-4 font-mono text-xs text-zinc-500">
        No file open. Pick one in the sidebar or right-click to create one.
      </div>
    );
  }

  return (
    <div
      role="tablist"
      aria-label="Open files"
      className="flex h-9 items-stretch overflow-x-auto border-b border-zinc-800 bg-surface"
    >
      {openPaths.map((path) => (
        <Tab
          key={path}
          path={path}
          isActive={path === activePath}
          onActivate={() => onActivate(path)}
          onClose={() => onClose(path)}
        />
      ))}
    </div>
  );
}

function Tab({
  path,
  isActive,
  onActivate,
  onClose,
}: {
  path: string;
  isActive: boolean;
  onActivate: () => void;
  onClose: () => void;
}) {
  const onMouseDown = (event: MouseEvent<HTMLDivElement>) => {
    if (event.button === 1) {
      event.preventDefault();
      onClose();
    }
  };

  const onCloseClick = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    onClose();
  };

  return (
    <div
      role="tab"
      aria-selected={isActive}
      tabIndex={0}
      onClick={onActivate}
      onMouseDown={onMouseDown}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onActivate();
        }
      }}
      className={cn(
        "group relative flex shrink-0 cursor-pointer items-center gap-2 border-r border-zinc-800 px-3 font-mono text-xs transition",
        isActive
          ? "bg-zinc-950 text-zinc-100"
          : "bg-surface text-zinc-400 hover:bg-surfaceRaised hover:text-zinc-200",
      )}
    >
      <FileCode2
        className={cn("h-3.5 w-3.5 shrink-0", isActive ? "text-cyan" : "text-zinc-500")}
      />
      <span className="truncate">{path}</span>
      <button
        type="button"
        onClick={onCloseClick}
        aria-label={`Close ${path}`}
        className={cn(
          "ml-1 flex h-4 w-4 items-center justify-center rounded text-zinc-500 transition",
          "opacity-0 hover:bg-zinc-800 hover:text-zinc-200 group-hover:opacity-100",
          isActive && "opacity-100",
        )}
      >
        <X className="h-3 w-3" />
      </button>
      {isActive && (
        <span className="absolute inset-x-0 bottom-0 h-0.5 bg-cyan" aria-hidden />
      )}
    </div>
  );
}
