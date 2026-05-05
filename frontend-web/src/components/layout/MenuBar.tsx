import * as Menubar from "@radix-ui/react-menubar";
import * as Dialog from "@radix-ui/react-dialog";
import { ChevronRight, X } from "lucide-react";
import { ReactNode, useState } from "react";
import { cn } from "@/lib/utils";

export interface MenuBarActions {
  onNewFile: () => void;
  onCloseTab: () => void;
  onCloseAllTabs: () => void;
  onFind: () => void;
  onReplace: () => void;
  onFormatDocument: () => void;
  onToggleFileExplorer: () => void;
  onToggleAiPanel: () => void;
  onRunFile: () => void;
  isFileExplorerVisible: boolean;
  isAiPanelVisible: boolean;
  hasActiveTab: boolean;
  hasOpenTabs: boolean;
  canEdit: boolean;
}

/**
 * VS Code-style menubar for the room workspace. Top-level menus are
 * laid out horizontally; each opens a styled Radix Menubar.Content
 * with items wired to the handlers in {@link MenuBarActions}.
 *
 * "About" and "Keyboard Shortcuts" are hosted here as Radix dialogs so
 * the menubar component owns its own modal state.
 */
export function MenuBar(actions: MenuBarActions) {
  const [aboutOpen, setAboutOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  return (
    <>
      <Menubar.Root className="flex h-9 items-center gap-0.5 border-b border-zinc-800 bg-background px-2 text-xs">
        <FileMenu {...actions} />
        <EditMenu {...actions} />
        <ViewMenu {...actions} />
        <RunMenu {...actions} />
        <HelpMenu
          onAboutClick={() => setAboutOpen(true)}
          onShortcutsClick={() => setShortcutsOpen(true)}
        />
      </Menubar.Root>

      <AboutDialog open={aboutOpen} onOpenChange={setAboutOpen} />
      <ShortcutsDialog open={shortcutsOpen} onOpenChange={setShortcutsOpen} />
    </>
  );
}

function FileMenu({
  onNewFile,
  onCloseTab,
  onCloseAllTabs,
  hasActiveTab,
  hasOpenTabs,
  canEdit,
}: MenuBarActions) {
  return (
    <Menubar.Menu>
      <MenuTrigger>File</MenuTrigger>
      <MenuContent>
        <MenuItem
          onSelect={onNewFile}
          shortcut="Ctrl+N"
          disabled={!canEdit}
        >
          New File...
        </MenuItem>
        <MenuItem disabled>
          Save
          <span className="ml-2 rounded bg-emerald-900/40 px-1.5 py-0.5 text-[9px] font-medium uppercase tracking-wide text-emerald-400">
            auto
          </span>
        </MenuItem>
        <MenuSeparator />
        <MenuItem onSelect={onCloseTab} shortcut="Ctrl+W" disabled={!hasActiveTab}>
          Close Tab
        </MenuItem>
        <MenuItem onSelect={onCloseAllTabs} disabled={!hasOpenTabs}>
          Close All Tabs
        </MenuItem>
      </MenuContent>
    </Menubar.Menu>
  );
}

function EditMenu({
  onFind,
  onReplace,
  onFormatDocument,
  hasActiveTab,
  canEdit,
}: MenuBarActions) {
  return (
    <Menubar.Menu>
      <MenuTrigger>Edit</MenuTrigger>
      <MenuContent>
        <MenuItem onSelect={onFind} shortcut="Ctrl+F" disabled={!hasActiveTab}>
          Find
        </MenuItem>
        <MenuItem
          onSelect={onReplace}
          shortcut="Ctrl+H"
          disabled={!hasActiveTab || !canEdit}
        >
          Replace
        </MenuItem>
        <MenuSeparator />
        <MenuItem
          onSelect={onFormatDocument}
          shortcut="Shift+Alt+F"
          disabled={!hasActiveTab || !canEdit}
        >
          Format Document
        </MenuItem>
      </MenuContent>
    </Menubar.Menu>
  );
}

function ViewMenu({
  onToggleFileExplorer,
  onToggleAiPanel,
  isFileExplorerVisible,
  isAiPanelVisible,
}: MenuBarActions) {
  return (
    <Menubar.Menu>
      <MenuTrigger>View</MenuTrigger>
      <MenuContent>
        <MenuCheckboxItem
          checked={isFileExplorerVisible}
          onSelect={onToggleFileExplorer}
        >
          File Explorer
        </MenuCheckboxItem>
        <MenuCheckboxItem checked={isAiPanelVisible} onSelect={onToggleAiPanel}>
          AI Assistant
        </MenuCheckboxItem>
      </MenuContent>
    </Menubar.Menu>
  );
}

function RunMenu({ onRunFile, hasActiveTab }: MenuBarActions) {
  return (
    <Menubar.Menu>
      <MenuTrigger>Run</MenuTrigger>
      <MenuContent>
        <MenuItem
          onSelect={onRunFile}
          shortcut="Ctrl+Enter"
          disabled={!hasActiveTab}
        >
          Run Active File
        </MenuItem>
        <MenuSeparator />
        <MenuLabel>Supported runtimes</MenuLabel>
        <MenuItem disabled>Python 3.12 (sandbox)</MenuItem>
      </MenuContent>
    </Menubar.Menu>
  );
}

function HelpMenu({
  onAboutClick,
  onShortcutsClick,
}: {
  onAboutClick: () => void;
  onShortcutsClick: () => void;
}) {
  return (
    <Menubar.Menu>
      <MenuTrigger>Help</MenuTrigger>
      <MenuContent>
        <MenuItem onSelect={onShortcutsClick}>Keyboard Shortcuts</MenuItem>
        <MenuItem onSelect={onAboutClick}>About Codeleon</MenuItem>
      </MenuContent>
    </Menubar.Menu>
  );
}

// =============================================================================
// Styled Radix primitives.
// =============================================================================

function MenuTrigger({ children }: { children: ReactNode }) {
  return (
    <Menubar.Trigger
      className={cn(
        "select-none rounded px-2.5 py-1 text-zinc-300 outline-none transition",
        "data-[state=open]:bg-surfaceRaised data-[state=open]:text-zinc-50",
        "hover:bg-surface hover:text-zinc-100 focus:bg-surface focus:text-zinc-100",
      )}
    >
      {children}
    </Menubar.Trigger>
  );
}

function MenuContent({ children }: { children: ReactNode }) {
  return (
    <Menubar.Portal>
      <Menubar.Content
        align="start"
        sideOffset={4}
        className="z-50 min-w-[220px] rounded-md border border-zinc-800 bg-surface p-1 text-xs shadow-glow"
      >
        {children}
      </Menubar.Content>
    </Menubar.Portal>
  );
}

function MenuItem({
  children,
  onSelect,
  shortcut,
  disabled,
}: {
  children: ReactNode;
  onSelect?: () => void;
  shortcut?: string;
  disabled?: boolean;
}) {
  return (
    <Menubar.Item
      onSelect={onSelect}
      disabled={disabled}
      className={cn(
        "flex cursor-pointer select-none items-center justify-between gap-3 rounded px-2 py-1.5 outline-none",
        "data-[disabled]:cursor-not-allowed data-[disabled]:opacity-40",
        "focus:bg-surfaceRaised focus:text-zinc-50",
        "text-zinc-200",
      )}
    >
      <span className="flex items-center gap-2">{children}</span>
      {shortcut && (
        <span className="font-mono text-[10px] text-zinc-500">{shortcut}</span>
      )}
    </Menubar.Item>
  );
}

function MenuCheckboxItem({
  children,
  checked,
  onSelect,
}: {
  children: ReactNode;
  checked: boolean;
  onSelect: () => void;
}) {
  return (
    <Menubar.CheckboxItem
      checked={checked}
      onSelect={(event) => {
        // Radix toggles `checked` automatically, but we also call our handler
        // to flip the parent state. Prevent the menu from auto-closing so the
        // user can toggle several views in one open.
        event.preventDefault();
        onSelect();
      }}
      className={cn(
        "flex cursor-pointer select-none items-center justify-between gap-3 rounded px-2 py-1.5 outline-none",
        "focus:bg-surfaceRaised focus:text-zinc-50",
        "text-zinc-200",
      )}
    >
      <span className="flex items-center gap-2">
        <span
          className={cn(
            "flex h-3 w-3 items-center justify-center rounded-sm border",
            checked
              ? "border-cyan bg-cyan/20 text-cyan"
              : "border-zinc-700 text-transparent",
          )}
          aria-hidden
        >
          <ChevronRight className="h-2 w-2" />
        </span>
        {children}
      </span>
    </Menubar.CheckboxItem>
  );
}

function MenuSeparator() {
  return <Menubar.Separator className="my-1 h-px bg-zinc-800" />;
}

function MenuLabel({ children }: { children: ReactNode }) {
  return (
    <div className="px-2 pb-1 pt-2 text-[10px] uppercase tracking-wide text-zinc-500">
      {children}
    </div>
  );
}

// =============================================================================
// Dialogs.
// =============================================================================

function AboutDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[90vw] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border border-zinc-800 bg-surface p-6 shadow-glow">
          <div className="flex items-start justify-between gap-4">
            <div>
              <Dialog.Title className="text-lg font-semibold text-zinc-50">
                Codeleon
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-sm text-zinc-400">
                Code + caméléon — a collaborative coding platform with a local
                RAG AI assistant.
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button
                aria-label="Close"
                className="rounded p-1 text-zinc-500 hover:bg-surfaceRaised hover:text-zinc-200"
              >
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>

          <dl className="mt-6 space-y-3 text-xs">
            <Row label="Frontend">React 18 · Vite 5 · TypeScript · Tailwind</Row>
            <Row label="Editor">Monaco + Yjs (multi-cursor)</Row>
            <Row label="Backend">Spring Boot 3 · PostgreSQL · Flyway</Row>
            <Row label="AI">Ollama · Qdrant · local CPU inference</Row>
            <Row label="Sandbox">Docker (Python 3.12)</Row>
          </dl>

          <p className="mt-6 text-[11px] text-zinc-500">
            PFE final-year project — built with Claude Code.
          </p>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function ShortcutsDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const shortcuts: Array<[string, string]> = [
    ["Ctrl + N", "New file (when File Explorer focused)"],
    ["Ctrl + W", "Close active tab"],
    ["Ctrl + F", "Find"],
    ["Ctrl + H", "Replace"],
    ["Shift + Alt + F", "Format document"],
    ["Ctrl + Enter", "Run active file"],
    ["Ctrl + Shift + P", "Monaco command palette"],
    ["Right click", "File context menu (rename / delete)"],
    ["Middle click", "Close tab"],
    ["Enter", "Send chat message"],
    ["Shift + Enter", "New line in chat"],
  ];

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[90vw] max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-lg border border-zinc-800 bg-surface p-6 shadow-glow">
          <div className="flex items-start justify-between gap-4">
            <Dialog.Title className="text-lg font-semibold text-zinc-50">
              Keyboard Shortcuts
            </Dialog.Title>
            <Dialog.Close asChild>
              <button
                aria-label="Close"
                className="rounded p-1 text-zinc-500 hover:bg-surfaceRaised hover:text-zinc-200"
              >
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>

          <ul className="mt-4 space-y-1.5 text-xs">
            {shortcuts.map(([keys, label]) => (
              <li
                key={keys}
                className="flex items-center justify-between gap-3 rounded border border-zinc-800 bg-zinc-950 px-3 py-2"
              >
                <span className="text-zinc-300">{label}</span>
                <kbd className="rounded bg-surfaceRaised px-2 py-0.5 font-mono text-[10px] text-zinc-200">
                  {keys}
                </kbd>
              </li>
            ))}
          </ul>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <dt className="w-24 shrink-0 text-zinc-500">{label}</dt>
      <dd className="flex-1 text-zinc-300">{children}</dd>
    </div>
  );
}
