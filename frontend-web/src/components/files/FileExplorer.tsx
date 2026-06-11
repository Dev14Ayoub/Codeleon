import * as ContextMenu from "@radix-ui/react-context-menu";
import { AnimatePresence, motion } from "framer-motion";
import {
  ChevronDown,
  ChevronRight,
  Edit3,
  FileCode2,
  FilePlus2,
  Folder,
  FolderOpen,
  FolderUp,
  Image,
  Loader2,
  Trash2,
} from "lucide-react";
import {
  ChangeEvent,
  forwardRef,
  FormEvent,
  KeyboardEvent,
  useMemo,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import { useRoomFiles } from "@/lib/files/useRoomFiles";
import type { RoomAssetMeta, RoomFile } from "@/lib/api";
import { cn } from "@/lib/utils";

interface FileExplorerProps {
  roomId: string;
  activePath: string | null;
  onActivePathChange: (path: string) => void;
  canEdit: boolean;
  onFileCreated?: (file: RoomFile) => void;
  onFileRenamed?: (oldPath: string, file: RoomFile) => void;
  onFileDeleted?: (file: RoomFile) => void;
  /** Called when the user picks a folder to import from disk. */
  onImportLocal?: (files: FileList) => void;
  /** Indicates an in-flight bulk import; disables the upload button. */
  importing?: boolean;
  /** Binary assets (images, fonts…) shown alongside text files in the tree. */
  assets?: RoomAssetMeta[];
  activeAsset?: string | null;
  onAssetSelected?: (path: string) => void;
}

export interface FileExplorerHandle {
  /** Opens the inline "new file" input. Used by the menubar's File > New File item. */
  openNewFileInput: () => void;
  /** Re-fetch the file list from the backend. Used after bulk imports. */
  refresh: () => Promise<void>;
}

type Pending =
  | { kind: "new" }
  | { kind: "rename"; fileId: string; currentPath: string }
  | null;

type FileTreeNode =
  | { kind: "folder"; name: string; path: string; children: FileTreeNode[] }
  | { kind: "file"; name: string; path: string; file: RoomFile }
  | { kind: "asset"; name: string; path: string; asset: RoomAssetMeta };

export const FileExplorer = forwardRef<FileExplorerHandle, FileExplorerProps>(function FileExplorer(
  {
    roomId,
    activePath,
    onActivePathChange,
    canEdit,
    onFileCreated,
    onFileRenamed,
    onFileDeleted,
    onImportLocal,
    importing,
    assets,
    activeAsset,
    onAssetSelected,
  },
  ref,
) {
  const { files, loading, error, create, rename, remove, refresh } = useRoomFiles(roomId);
  const [pending, setPending] = useState<Pending>(null);
  const [collapsedFolders, setCollapsedFolders] = useState<Set<string>>(() => new Set());
  const importInputRef = useRef<HTMLInputElement>(null);
  const tree = useMemo(() => buildFileTree(files, assets ?? []), [files, assets]);

  const handleImportClick = () => {
    if (importing) return;
    importInputRef.current?.click();
  };

  const handleImportChange = (event: ChangeEvent<HTMLInputElement>) => {
    const files = event.target.files;
    if (files && files.length > 0 && onImportLocal) {
      onImportLocal(files);
    }
    // Reset so picking the same folder twice in a row still fires onChange.
    if (event.target) event.target.value = "";
  };

  useImperativeHandle(
    ref,
    () => ({
      openNewFileInput: () => {
        if (canEdit) setPending({ kind: "new" });
      },
      refresh,
    }),
    [canEdit, refresh],
  );

  // Auto-pick the first file as active when the list loads or the
  // active file gets deleted under us.
  useEffect(() => {
    if (files.length === 0) return;
    if (!activePath || !files.some((f) => f.path === activePath)) {
      onActivePathChange(files[0].path);
    }
  }, [files, activePath, onActivePathChange]);

  useEffect(() => {
    if (!activePath) return;
    const ancestors = folderAncestors(activePath);
    if (ancestors.length === 0) return;
    setCollapsedFolders((current) => {
      const next = new Set(current);
      let changed = false;
      for (const ancestor of ancestors) {
        if (next.delete(ancestor)) changed = true;
      }
      return changed ? next : current;
    });
  }, [activePath]);

  const handleCreate = async (path: string) => {
    const file = await create(path);
    setPending(null);
    if (file) {
      onFileCreated?.(file);
      onActivePathChange(file.path);
    }
  };

  const handleRename = async (fileId: string, newPath: string) => {
    const oldPath =
      pending?.kind === "rename" && pending.fileId === fileId
        ? pending.currentPath
        : newPath;
    const file = await rename(fileId, newPath);
    setPending(null);
    if (file) {
      onFileRenamed?.(oldPath, file);
      onActivePathChange(file.path);
    }
  };

  const handleDelete = async (file: RoomFile) => {
    const ok = await remove(file.id);
    if (ok) {
      onFileDeleted?.(file);
    }
    if (ok && file.path === activePath) {
      const next = files.find((f) => f.id !== file.id);
      if (next) onActivePathChange(next.path);
    }
  };

  const toggleFolder = (path: string) => {
    setCollapsedFolders((current) => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const renderTreeNode = (node: FileTreeNode, depth: number): React.ReactNode => {
    if (node.kind === "folder") {
      const collapsed = collapsedFolders.has(node.path);
      return (
        <motion.li
          key={`folder:${node.path}`}
          layout
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.16 }}
        >
          <FolderRow
            name={node.name}
            path={node.path}
            depth={depth}
            collapsed={collapsed}
            onClick={() => toggleFolder(node.path)}
          />
          <AnimatePresence initial={false}>
            {!collapsed && (
              <motion.ul
                key={`${node.path}:children`}
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: "auto" }}
                exit={{ opacity: 0, height: 0 }}
                transition={{ duration: 0.16 }}
                className="overflow-hidden"
              >
                {node.children.map((child) => renderTreeNode(child, depth + 1))}
              </motion.ul>
            )}
          </AnimatePresence>
        </motion.li>
      );
    }

    if (node.kind === "asset") {
      return (
        <motion.li
          key={`asset:${node.path}`}
          layout
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.16 }}
        >
          <AssetRow
            name={node.name}
            path={node.path}
            depth={depth}
            active={node.path === activeAsset}
            onClick={() => onAssetSelected?.(node.path)}
          />
        </motion.li>
      );
    }

    if (pending?.kind === "rename" && pending.fileId === node.file.id) {
      return (
        <motion.li
          key={node.file.id}
          layout
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          style={{ paddingLeft: depth * TREE_INDENT }}
        >
          <FileNameInput
            initial={node.file.path}
            placeholder={node.file.path}
            onConfirm={(newPath) => handleRename(node.file.id, newPath)}
            onCancel={() => setPending(null)}
          />
        </motion.li>
      );
    }

    return (
      <ContextMenu.Root key={node.file.id}>
        <ContextMenu.Trigger asChild>
          <motion.li layout initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.16 }}>
            <FileRow
              file={node.file}
              name={node.name}
              depth={depth}
              active={node.file.path === activePath}
              onClick={() => onActivePathChange(node.file.path)}
            />
          </motion.li>
        </ContextMenu.Trigger>
        {canEdit && (
          <FileContextMenuContent
            onRename={() =>
              setPending({
                kind: "rename",
                fileId: node.file.id,
                currentPath: node.file.path,
              })
            }
            onDelete={() => void handleDelete(node.file)}
            onNew={() => setPending({ kind: "new" })}
          />
        )}
      </ContextMenu.Root>
    );
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
          <div className="flex items-center gap-1">
            {onImportLocal && (
              <button
                type="button"
                onClick={handleImportClick}
                disabled={importing}
                className="rounded p-1 text-zinc-500 hover:bg-surfaceRaised hover:text-zinc-200 disabled:opacity-50"
                aria-label="Import folder from disk"
                title="Import folder from disk"
              >
                {importing ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <FolderUp className="h-4 w-4" />
                )}
              </button>
            )}
            <button
              type="button"
              onClick={() => setPending({ kind: "new" })}
              className="rounded p-1 text-zinc-500 hover:bg-surfaceRaised hover:text-zinc-200"
              aria-label="New file"
              title="New file"
            >
              <FilePlus2 className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>

      {/*
       * Hidden folder picker. webkitdirectory is a non-standard but
       * widely supported attribute (Chrome / Edge / Firefox) that turns
       * the picker into a directory selector. The selected FileList
       * carries each File's webkitRelativePath used by prepareLocalImport.
       */}
      <input
        ref={importInputRef}
        type="file"
        multiple
        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
        // @ts-ignore — webkitdirectory is missing from the standard React types.
        webkitdirectory=""
        directory=""
        className="hidden"
        onChange={handleImportChange}
      />

      {error && (
        <p className="mb-2 rounded border border-rose-900 bg-rose-950/40 px-2 py-1 text-[11px] text-rose-300">
          {error}
        </p>
      )}

      <ContextMenu.Root>
        <ContextMenu.Trigger asChild>
          <ul className="-mx-1 flex-1 space-y-0.5 overflow-y-auto px-1">
            <AnimatePresence initial={false}>
            {pending?.kind === "new" && (
              <motion.li initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}>
                <FileNameInput
                  initial=""
                  placeholder="filename.ext"
                  onConfirm={handleCreate}
                  onCancel={() => setPending(null)}
                />
              </motion.li>
            )}
            </AnimatePresence>

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

            {tree.map((node) => renderTreeNode(node, 0))}
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
});

function FolderRow({
  name,
  path,
  depth,
  collapsed,
  onClick,
}: {
  name: string;
  path: string;
  depth: number;
  collapsed: boolean;
  onClick: () => void;
}) {
  return (
    <motion.button
      type="button"
      whileHover={{ x: 3 }}
      whileTap={{ scale: 0.99 }}
      onClick={onClick}
      title={path}
      className="relative flex w-full items-center gap-1.5 rounded-md py-1.5 pr-2 text-left font-mono text-[13px] text-zinc-300 transition hover:bg-surface hover:text-zinc-100"
      style={{ paddingLeft: 8 + depth * TREE_INDENT }}
    >
      {collapsed ? (
        <ChevronRight className="h-3.5 w-3.5 shrink-0 text-zinc-500" />
      ) : (
        <ChevronDown className="h-3.5 w-3.5 shrink-0 text-zinc-500" />
      )}
      {collapsed ? (
        <Folder className="h-3.5 w-3.5 shrink-0 text-cyan/80" />
      ) : (
        <FolderOpen className="h-3.5 w-3.5 shrink-0 text-cyan" />
      )}
      <span className="truncate">{name}</span>
    </motion.button>
  );
}

function FileRow({
  file,
  name,
  depth,
  active,
  onClick,
}: {
  file: RoomFile;
  name: string;
  depth: number;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <motion.button
      type="button"
      whileHover={{ x: 3 }}
      whileTap={{ scale: 0.99 }}
      onClick={onClick}
      title={file.path}
      className={cn(
        "relative flex w-full items-center gap-2 rounded-md py-1.5 pr-2 text-left font-mono text-[13px] transition",
        active
          ? "bg-surfaceRaised text-zinc-50 shadow-[inset_2px_0_0_rgba(6,182,212,0.95)]"
          : "text-zinc-400 hover:bg-surface hover:text-zinc-100",
      )}
      style={{ paddingLeft: 26 + depth * TREE_INDENT }}
    >
      <FileCode2
        className={cn("h-3.5 w-3.5 shrink-0", active ? "text-cyan" : "text-zinc-500")}
      />
      <span className="truncate">{name}</span>
    </motion.button>
  );
}

function AssetRow({
  name,
  path,
  depth,
  active,
  onClick,
}: {
  name: string;
  path: string;
  depth: number;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <motion.button
      type="button"
      whileHover={{ x: 3 }}
      whileTap={{ scale: 0.99 }}
      onClick={onClick}
      title={path}
      className={cn(
        "relative flex w-full items-center gap-2 rounded-md py-1.5 pr-2 text-left font-mono text-[13px] transition",
        active
          ? "bg-surfaceRaised text-zinc-50 shadow-[inset_2px_0_0_rgba(6,182,212,0.95)]"
          : "text-zinc-400 hover:bg-surface hover:text-zinc-100",
      )}
      style={{ paddingLeft: 26 + depth * TREE_INDENT }}
    >
      <Image className={cn("h-3.5 w-3.5 shrink-0", active ? "text-cyan" : "text-violet-400/80")} />
      <span className="truncate">{name}</span>
    </motion.button>
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

function buildFileTree(files: RoomFile[], assets: RoomAssetMeta[]): FileTreeNode[] {
  type MutableFolder = {
    kind: "folder";
    name: string;
    path: string;
    folders: Map<string, MutableFolder>;
    files: FileTreeNode[];
  };

  const root: MutableFolder = {
    kind: "folder",
    name: "",
    path: "",
    folders: new Map(),
    files: [],
  };

  // Merge text files and binary assets into one ordered list of leaves.
  const leaves: { path: string; node: FileTreeNode }[] = [];
  for (const file of files) {
    const name = file.path.split("/").filter(Boolean).pop() ?? file.path;
    leaves.push({ path: file.path, node: { kind: "file", name, path: file.path, file } });
  }
  for (const asset of assets) {
    const name = asset.path.split("/").filter(Boolean).pop() ?? asset.path;
    leaves.push({ path: asset.path, node: { kind: "asset", name, path: asset.path, asset } });
  }
  leaves.sort((a, b) => a.path.localeCompare(b.path));

  for (const { path, node } of leaves) {
    const parts = path.split("/").filter(Boolean);
    if (parts.length === 0) continue;

    let current = root;
    for (let i = 0; i < parts.length - 1; i += 1) {
      const name = parts[i];
      const folderPath = current.path ? `${current.path}/${name}` : name;
      let folder = current.folders.get(name);
      if (!folder) {
        folder = {
          kind: "folder",
          name,
          path: folderPath,
          folders: new Map(),
          files: [],
        };
        current.folders.set(name, folder);
      }
      current = folder;
    }

    current.files.push(node);
  }

  const freezeFolder = (folder: MutableFolder): FileTreeNode[] => {
    const folders = Array.from(folder.folders.values())
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((child) => ({
        kind: "folder" as const,
        name: child.name,
        path: child.path,
        children: freezeFolder(child),
      }));
    const leafFiles = folder.files.sort((a, b) => a.name.localeCompare(b.name));
    return [...folders, ...leafFiles];
  };

  return freezeFolder(root);
}

function folderAncestors(path: string): string[] {
  const parts = path.split("/").filter(Boolean);
  const ancestors: string[] = [];
  for (let i = 1; i < parts.length; i += 1) {
    ancestors.push(parts.slice(0, i).join("/"));
  }
  return ancestors;
}

const TREE_INDENT = 16;
