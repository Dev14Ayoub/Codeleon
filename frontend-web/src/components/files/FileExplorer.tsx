import * as ContextMenu from "@radix-ui/react-context-menu";
import { Edit3, FileCode2, FilePlus2, FolderOpen, Loader2, Trash2 } from "lucide-react";
import {
  FormEvent,
  KeyboardEvent,
  useEffect,
  useRef,
  useState,
} from "react";
import { useRoomFiles } from "@/lib/files/useRoomFiles";
import type { RoomFile } from "@/lib/api";
import { cn } from "@/lib/utils";

interface FileExplorerProps {
  roomId: string;
  activePath: string | null;
  onActivePathChange: (path: string) => void;
  canEdit: boolean;
}

type Pending =
  | { kind: "new" }
  | { kind: "rename"; fileId: string; currentPath: string }
  | null;

export function FileExplorer({
  roomId,
  activePath,
  onActivePathChange,
  canEdit,
}: FileExplorerProps) {
  const { files, loading, error, create, rename, remove } = useRoomFiles(roomId);
  const [pending, setPending] = useState<Pending>(null);

  // Auto-pick the first file as active when the list loads or the
  // active file gets deleted under us.
  useEffect(() => {
    if (files.length === 0) return;
    if (!activePath || !files.some((f) => f.path === activePath)) {
      onActivePathChange(files[0].path);
    }
  }, [files, activePath, onActivePathChange]);

  const handleCreate = async (path: string) => {
    const file = await create(path);
    setPending(null);
    if (file) onActivePathChange(file.path);
  };

  const handleRename = async (fileId: string, newPath: string) => {
    const file = await rename(fileId, newPath);
    setPending(null);
    if (file) onActivePathChange(file.path);
  };

  const handleDelete = async (file: RoomFile) => {
    const ok = await remove(file.id);
    if (ok && file.path === activePath) {
      const next = files.find((f) => f.id !== file.id);
      if (next) onActivePathChange(next.path);
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-medium text-zinc-200">
          <FolderOpen className="h-4 w-4 text-cyan" />
          Files {files.length > 0 && (
            <span className="text-xs text-zinc-500">({files.length})</span>
          )}
        </div>
        {canEdit && (
          <button
            type="button"
            onClick={() => setPending({ kind: "new" })}
            className="rounded p-1 text-zinc-500 hover:bg-surfaceRaised hover:text-zinc-200"
            aria-label="New file"
            title="New file"
          >
            <FilePlus2 className="h-4 w-4" />
          </button>
        )}
      </div>

      {error && (
        <p className="mb-2 rounded border border-rose-900 bg-rose-950/40 px-2 py-1 text-[11px] text-rose-300">
          {error}
        </p>
      )}

      <ContextMenu.Root>
        <ContextMenu.Trigger asChild>
          <ul className="-mx-1 flex-1 space-y-0.5 overflow-y-auto px-1">
            {pending?.kind === "new" && (
              <li>
                <FileNameInput
                  initial=""
                  placeholder="filename.ext"
                  onConfirm={handleCreate}
                  onCancel={() => setPending(null)}
                />
              </li>
            )}

            {loading && files.length === 0 && (
              <li className="flex items-center gap-2 px-2 py-1.5 text-xs text-zinc-500">
                <Loader2 className="h-3 w-3 animate-spin" /> Loading files...
              </li>
            )}

            {!loading && files.length === 0 && pending?.kind !== "new" && (
              <li className="px-2 py-2 text-xs text-zinc-500">
                No files yet. Right-click to create one.
              </li>
            )}

            {files.map((file) =>
              pending?.kind === "rename" && pending.fileId === file.id ? (
                <li key={file.id}>
                  <FileNameInput
                    initial={file.path}
                    placeholder={file.path}
                    onConfirm={(newPath) => handleRename(file.id, newPath)}
                    onCancel={() => setPending(null)}
                  />
                </li>
              ) : (
                <ContextMenu.Root key={file.id}>
                  <ContextMenu.Trigger asChild>
                    <li>
                      <FileRow
                        file={file}
                        active={file.path === activePath}
                        onClick={() => onActivePathChange(file.path)}
                      />
                    </li>
                  </ContextMenu.Trigger>
                  {canEdit && (
                    <FileContextMenuContent
                      onRename={() =>
                        setPending({
                          kind: "rename",
                          fileId: file.id,
                          currentPath: file.path,
                        })
                      }
                      onDelete={() => void handleDelete(file)}
                      onNew={() => setPending({ kind: "new" })}
                    />
                  )}
                </ContextMenu.Root>
              ),
            )}
          </ul>
        </ContextMenu.Trigger>
        {/*
         * Empty-area context menu: only shown when right-click lands
         * on the <ul> outside any file row. Radix dispatches the inner
         * trigger first, so this only fires for the empty space.
         */}
        {canEdit && (
          <ContextMenu.Portal>
            <ContextMenu.Content
              className="z-50 min-w-[180px] rounded-md border border-zinc-800 bg-surface p-1 shadow-glow"
            >
              <ContextMenuItem
                icon={<FilePlus2 className="h-3.5 w-3.5" />}
                label="New file..."
                onSelect={() => setPending({ kind: "new" })}
              />
            </ContextMenu.Content>
          </ContextMenu.Portal>
        )}
      </ContextMenu.Root>
    </div>
  );
}

function FileRow({
  file,
  active,
  onClick,
}: {
  file: RoomFile;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left font-mono text-[13px] transition",
        active
          ? "bg-surfaceRaised text-zinc-50"
          : "text-zinc-400 hover:bg-surface hover:text-zinc-100",
      )}
    >
      <FileCode2
        className={cn("h-3.5 w-3.5 shrink-0", active ? "text-cyan" : "text-zinc-500")}
      />
      <span className="truncate">{file.path}</span>
    </button>
  );
}

function FileContextMenuContent({
  onRename,
  onDelete,
  onNew,
}: {
  onRename: () => void;
  onDelete: () => void;
  onNew: () => void;
}) {
  return (
    <ContextMenu.Portal>
      <ContextMenu.Content
        className="z-50 min-w-[180px] rounded-md border border-zinc-800 bg-surface p-1 shadow-glow"
      >
        <ContextMenuItem
          icon={<Edit3 className="h-3.5 w-3.5" />}
          label="Rename"
          onSelect={onRename}
        />
        <ContextMenuItem
          icon={<Trash2 className="h-3.5 w-3.5" />}
          label="Delete"
          onSelect={onDelete}
          tone="danger"
        />
        <ContextMenu.Separator className="my-1 h-px bg-zinc-800" />
        <ContextMenuItem
          icon={<FilePlus2 className="h-3.5 w-3.5" />}
          label="New file..."
          onSelect={onNew}
        />
      </ContextMenu.Content>
    </ContextMenu.Portal>
  );
}

function ContextMenuItem({
  icon,
  label,
  onSelect,
  tone = "default",
}: {
  icon: React.ReactNode;
  label: string;
  onSelect: () => void;
  tone?: "default" | "danger";
}) {
  return (
    <ContextMenu.Item
      onSelect={onSelect}
      className={cn(
        "flex cursor-pointer select-none items-center gap-2 rounded px-2 py-1.5 text-xs outline-none",
        tone === "danger"
          ? "text-rose-300 focus:bg-rose-900/40 focus:text-rose-200"
          : "text-zinc-200 focus:bg-surfaceRaised focus:text-zinc-50",
      )}
    >
      {icon}
      {label}
    </ContextMenu.Item>
  );
}

function FileNameInput({
  initial,
  placeholder,
  onConfirm,
  onCancel,
}: {
  initial: string;
  placeholder: string;
  onConfirm: (path: string) => void | Promise<void>;
  onCancel: () => void;
}) {
  const [value, setValue] = useState(initial);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    if (initial) {
      // Select up to the extension dot to make rename easier.
      const dot = initial.lastIndexOf(".");
      if (dot > 0) inputRef.current?.setSelectionRange(0, dot);
      else inputRef.current?.select();
    }
  }, [initial]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = value.trim();
    if (!trimmed) {
      onCancel();
      return;
    }
    void onConfirm(trimmed);
  };

  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      onCancel();
    }
  };

  return (
    <form onSubmit={submit} className="flex items-center gap-2 px-2 py-1">
      <FileCode2 className="h-3.5 w-3.5 shrink-0 text-cyan" />
      <input
        ref={inputRef}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={onKey}
        onBlur={onCancel}
        placeholder={placeholder}
        className="flex-1 rounded border border-cyan/40 bg-zinc-950 px-1.5 py-1 font-mono text-[13px] text-zinc-100 outline-none focus:border-cyan"
      />
    </form>
  );
}
