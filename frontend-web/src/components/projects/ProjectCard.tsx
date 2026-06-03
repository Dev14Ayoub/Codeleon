import * as Dialog from "@radix-ui/react-dialog";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { Archive, ArchiveRestore, Copy, FileCode2, Globe2, Loader2, Lock, MoreHorizontal, Pencil, Pin, PinOff, Trash2, Users, X } from "lucide-react";
import { FormEvent, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Link } from "react-router-dom";
import { archiveRoom, deleteRoom, pinRoom, unarchiveRoom, unpinRoom, updateRoom, type Room } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatRelativeDate } from "@/lib/utils";

interface ProjectCardProps {
  room: Room;
}

const MotionLink = motion(Link);

export function ProjectCard({ room }: ProjectCardProps) {
  const [copied, setCopied] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  // The trigger ref stays inside the card so click-on-trigger does not
  // trigger the "click outside" branch.
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  // The portal menu lives on document.body so the card's overflow-hidden
  // does not clip it; we still need a ref to it so click-on-menu-item
  // does not trigger the "click outside" branch either.
  const portalMenuRef = useRef<HTMLDivElement | null>(null);
  // Position of the floating menu, computed from the trigger's bounding
  // box when the menu opens. Stays null until the first open.
  const [menuPosition, setMenuPosition] = useState<{ top: number; right: number } | null>(null);
  const queryClient = useQueryClient();

  // Close the ⋯ menu on outside click. We listen at the document level
  // and bail out when the click target is inside the trigger OR inside
  // the portal-rendered menu. Also close on scroll/resize because the
  // computed position becomes stale.
  useEffect(() => {
    if (!menuOpen) return;
    function onClick(event: MouseEvent) {
      const target = event.target as Node;
      const inTrigger = triggerRef.current?.contains(target) ?? false;
      const inPortal = portalMenuRef.current?.contains(target) ?? false;
      if (!inTrigger && !inPortal) {
        setMenuOpen(false);
      }
    }
    function onScrollOrResize() {
      setMenuOpen(false);
    }
    document.addEventListener("mousedown", onClick);
    window.addEventListener("scroll", onScrollOrResize, true);
    window.addEventListener("resize", onScrollOrResize);
    return () => {
      document.removeEventListener("mousedown", onClick);
      window.removeEventListener("scroll", onScrollOrResize, true);
      window.removeEventListener("resize", onScrollOrResize);
    };
  }, [menuOpen]);

  const pinMutation = useMutation({
    mutationFn: () => (room.pinned ? unpinRoom(room.id) : pinRoom(room.id)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["rooms"] }),
  });

  const archiveMutation = useMutation({
    mutationFn: () => (room.archived ? unarchiveRoom(room.id) : archiveRoom(room.id)),
    onSuccess: () => {
      setMenuOpen(false);
      void queryClient.invalidateQueries({ queryKey: ["rooms"] });
    },
  });

  // Rename dialog state. We pre-fill with the current values so the
  // user can just adjust without re-typing.
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameName, setRenameName] = useState(room.name);
  const [renameDescription, setRenameDescription] = useState(room.description ?? "");
  const [renameError, setRenameError] = useState<string | null>(null);
  const renameMutation = useMutation({
    mutationFn: () =>
      updateRoom(room.id, {
        name: renameName.trim(),
        description: renameDescription.trim(),
      }),
    onSuccess: () => {
      setRenameOpen(false);
      setRenameError(null);
      void queryClient.invalidateQueries({ queryKey: ["rooms"] });
    },
    onError: (err) => {
      const msg = err instanceof Error ? err.message : "Could not update the project";
      setRenameError(msg);
    },
  });

  // Delete dialog state. We require the user to type the project name
  // verbatim to confirm — a 2-step gate against accidental clicks.
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteConfirmText, setDeleteConfirmText] = useState("");
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const deleteMutation = useMutation({
    mutationFn: () => deleteRoom(room.id),
    onSuccess: () => {
      setDeleteOpen(false);
      setDeleteConfirmText("");
      setDeleteError(null);
      void queryClient.invalidateQueries({ queryKey: ["rooms"] });
    },
    onError: (err) => {
      const msg = err instanceof Error ? err.message : "Could not delete the project";
      setDeleteError(msg);
    },
  });

  function handleCopy(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    void navigator.clipboard.writeText(room.inviteCode).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  function handlePinToggle(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    pinMutation.mutate();
  }

  function handleMenuToggle(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (menuOpen) {
      setMenuOpen(false);
      return;
    }
    // Anchor the menu under the trigger using a fixed-position offset
    // from the right edge of the viewport. Using `right` (rather than
    // `left`) keeps the menu nicely aligned with the trigger even on
    // wider screens where the card may sit far from the viewport edge.
    if (triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect();
      setMenuPosition({
        top: rect.bottom + 4,
        right: Math.max(8, window.innerWidth - rect.right),
      });
    }
    setMenuOpen(true);
  }

  function handleArchive(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    archiveMutation.mutate();
  }

  function handleOpenRename(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    setRenameName(room.name);
    setRenameDescription(room.description ?? "");
    setRenameError(null);
    setMenuOpen(false);
    setRenameOpen(true);
  }

  function handleOpenDelete(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    setDeleteConfirmText("");
    setDeleteError(null);
    setMenuOpen(false);
    setDeleteOpen(true);
  }

  function submitRename(e: FormEvent) {
    e.preventDefault();
    const trimmed = renameName.trim();
    if (trimmed.length < 2) {
      setRenameError("Name must be at least 2 characters.");
      return;
    }
    if (trimmed.length > 120) {
      setRenameError("Name must be at most 120 characters.");
      return;
    }
    renameMutation.mutate();
  }

  function submitDelete(e: FormEvent) {
    e.preventDefault();
    if (deleteConfirmText.trim() !== room.name) {
      setDeleteError("Type the project name exactly to confirm.");
      return;
    }
    deleteMutation.mutate();
  }

  const isPublic = room.visibility === "PUBLIC";
  const isOwner = room.currentUserRole === "OWNER";
  const role = room.currentUserRole ?? "guest";

  return (
    <MotionLink
      to={`/rooms/${room.id}`}
      whileHover={{ y: -5, scale: 1.012 }}
      whileTap={{ scale: 0.99 }}
      transition={{ type: "spring", stiffness: 420, damping: 32 }}
      className={
        // Tighter padding (p-4 vs p-5) for a denser card grid.
        room.archived
          ? "group relative flex flex-col overflow-hidden rounded-lg border border-zinc-800 bg-surface/60 p-4 opacity-70 shadow-[0_10px_28px_rgba(0,0,0,0.18)] transition hover:border-signature/60 hover:bg-surfaceRaised hover:opacity-100 hover:shadow-[0_18px_56px_rgba(99,102,241,0.18)]"
          : "group relative flex flex-col overflow-hidden rounded-lg border border-zinc-800 bg-surface p-4 shadow-[0_10px_28px_rgba(0,0,0,0.18)] transition hover:border-signature/60 hover:bg-surfaceRaised hover:shadow-[0_18px_56px_rgba(99,102,241,0.2)]"
      }
    >
      <span className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-cyan/70 to-transparent opacity-0 transition-opacity duration-300 group-hover:opacity-100" />
      <span className="pointer-events-none absolute -right-16 -top-20 h-32 w-32 rotate-12 bg-[linear-gradient(90deg,transparent,rgba(6,182,212,0.12),transparent)] opacity-0 blur-xl transition-opacity duration-300 group-hover:opacity-100" />
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-start gap-2">
            {room.pinned && <Pin className="mt-1 h-3.5 w-3.5 shrink-0 fill-cyan text-cyan" aria-label="Pinned" />}
            {/* line-clamp-2 instead of truncate: lets long project names wrap
                onto a second line rather than collapsing into "Test_..." when
                the card is narrow (3-column grid + activity panel). */}
            <p className="line-clamp-2 break-words font-medium text-zinc-100 group-hover:text-white">{room.name}</p>
          </div>
          <p className="mt-1 truncate text-sm text-zinc-400">Owner: {room.ownerName}</p>
        </div>
        <div className="flex items-center gap-1.5">
          <span
            className={
              isPublic
                ? "inline-flex items-center gap-1 rounded-md border border-emerald-700/40 bg-emerald-900/20 px-2 py-1 text-xs font-medium text-emerald-300"
                : "inline-flex items-center gap-1 rounded-md border border-zinc-700 bg-zinc-900 px-2 py-1 text-xs font-medium text-zinc-400"
            }
          >
            {isPublic ? <Globe2 className="h-3 w-3" /> : <Lock className="h-3 w-3" />}
            {room.visibility.toLowerCase()}
          </span>
          {room.archived && (
            <span className="inline-flex items-center gap-1 rounded-md border border-amber-700/40 bg-amber-900/20 px-2 py-1 text-xs font-medium text-amber-300">
              <Archive className="h-3 w-3" />
              archived
            </span>
          )}
        </div>
      </div>

      {room.description ? (
        <p className="mt-2 line-clamp-2 text-[13px] leading-5 text-zinc-300">{room.description}</p>
      ) : (
        <p className="mt-2 text-[13px] italic text-zinc-500">No description</p>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-zinc-500">
        <span className="inline-flex items-center gap-1">
          <FileCode2 className="h-3 w-3" />
          {room.fileCount} {room.fileCount === 1 ? "file" : "files"}
        </span>
        <span className="inline-flex items-center gap-1">
          <Users className="h-3 w-3" />
          {room.memberCount} {room.memberCount === 1 ? "member" : "members"}
        </span>
        <span>Updated {formatRelativeDate(room.updatedAt)}</span>
      </div>

      {room.lastEditedByName && (
        <p className="mt-1.5 text-[11px] text-zinc-500">
          Last edited by <span className="text-zinc-300">{room.lastEditedByName}</span>
        </p>
      )}

      <div className="mt-3 flex items-center justify-between gap-3 border-t border-zinc-800 pt-3">
        <button
          type="button"
          onClick={handleCopy}
          className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 font-mono text-xs text-zinc-500 transition hover:bg-zinc-900 hover:text-zinc-200"
          title="Copy invite code"
        >
          <Copy className="h-3 w-3" />
          {copied ? "copied!" : room.inviteCode}
        </button>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium uppercase tracking-wide text-cyan">{role.toLowerCase()}</span>
          <button
            type="button"
            onClick={handlePinToggle}
            disabled={pinMutation.isPending}
            className="rounded-md p-1 text-zinc-500 transition hover:-translate-y-0.5 hover:bg-zinc-900 hover:text-zinc-200 disabled:opacity-50"
            title={room.pinned ? "Unpin" : "Pin to top"}
            aria-label={room.pinned ? "Unpin project" : "Pin project"}
          >
            {room.pinned ? <PinOff className="h-3.5 w-3.5" /> : <Pin className="h-3.5 w-3.5" />}
          </button>
          {isOwner && (
            <>
              <button
                ref={triggerRef}
                type="button"
                onClick={handleMenuToggle}
                className="rounded-md p-1 text-zinc-500 transition hover:bg-zinc-900 hover:text-zinc-200"
                title="More actions"
                aria-haspopup="menu"
                aria-expanded={menuOpen}
              >
                <MoreHorizontal className="h-3.5 w-3.5" />
              </button>
              {menuOpen && menuPosition && createPortal(
                /*
                 * The kebab menu is portaled to document.body so the card's
                 * overflow-hidden does not clip it. position:fixed +
                 * computed top/right anchors it to the trigger button.
                 * The portalMenuRef + outside-click handler (useEffect
                 * above) keep the menu dismissible.
                 */
                <div
                  ref={portalMenuRef}
                  role="menu"
                  style={{
                    position: "fixed",
                    top: menuPosition.top,
                    right: menuPosition.right,
                    zIndex: 50,
                  }}
                  className="w-48 rounded-md border border-zinc-700 bg-zinc-950 p-1 shadow-[0_18px_48px_rgba(0,0,0,0.55)]"
                >
                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenRename}
                    className="flex w-full items-center gap-2 rounded-sm px-2.5 py-1.5 text-left text-xs text-zinc-300 transition hover:bg-surfaceRaised hover:text-zinc-100"
                  >
                    <Pencil className="h-3.5 w-3.5" />
                    Rename project
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleArchive}
                    disabled={archiveMutation.isPending}
                    className="flex w-full items-center gap-2 rounded-sm px-2.5 py-1.5 text-left text-xs text-zinc-300 transition hover:bg-surfaceRaised hover:text-zinc-100 disabled:opacity-50"
                  >
                    {room.archived ? (
                      <>
                        <ArchiveRestore className="h-3.5 w-3.5" />
                        Unarchive project
                      </>
                    ) : (
                      <>
                        <Archive className="h-3.5 w-3.5" />
                        Archive project
                      </>
                    )}
                  </button>
                  {/* Visual separator before the destructive action. */}
                  <div className="my-1 h-px bg-zinc-800" aria-hidden />
                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenDelete}
                    className="flex w-full items-center gap-2 rounded-sm px-2.5 py-1.5 text-left text-xs text-rose-400 transition hover:bg-rose-950/40 hover:text-rose-300"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    Delete project
                  </button>
                </div>,
                document.body,
              )}

              {/* Rename dialog. Radix Dialog auto-portals to body, so
                  no overflow-hidden clipping. */}
              <Dialog.Root open={renameOpen} onOpenChange={setRenameOpen}>
                <Dialog.Portal>
                  <Dialog.Overlay
                    onClick={(e) => e.stopPropagation()}
                    className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm data-[state=open]:animate-in data-[state=open]:fade-in"
                  />
                  <Dialog.Content
                    onClick={(e) => e.stopPropagation()}
                    onPointerDownOutside={(e) => e.preventDefault()}
                    className="fixed left-1/2 top-1/2 z-50 w-[min(92vw,28rem)] -translate-x-1/2 -translate-y-1/2 rounded-lg border border-zinc-800 bg-surface p-5 shadow-[0_24px_70px_rgba(0,0,0,0.46)]"
                  >
                    <div className="mb-4 flex items-start justify-between gap-3">
                      <div>
                        <Dialog.Title className="text-base font-semibold text-zinc-50">
                          Rename project
                        </Dialog.Title>
                        <Dialog.Description className="mt-1 text-xs text-zinc-400">
                          Change how this project appears on every member's dashboard.
                        </Dialog.Description>
                      </div>
                      <Dialog.Close className="rounded-md p-1 text-zinc-500 transition hover:bg-zinc-900 hover:text-zinc-200">
                        <X className="h-4 w-4" />
                      </Dialog.Close>
                    </div>
                    <form onSubmit={submitRename} className="space-y-3">
                      <div className="space-y-1">
                        <label className="text-[11px] uppercase tracking-wide text-zinc-500" htmlFor={`rename-name-${room.id}`}>
                          Name
                        </label>
                        <Input
                          id={`rename-name-${room.id}`}
                          value={renameName}
                          onChange={(e) => setRenameName(e.target.value)}
                          maxLength={120}
                          autoFocus
                        />
                      </div>
                      <div className="space-y-1">
                        <label className="text-[11px] uppercase tracking-wide text-zinc-500" htmlFor={`rename-desc-${room.id}`}>
                          Description
                        </label>
                        <textarea
                          id={`rename-desc-${room.id}`}
                          value={renameDescription}
                          onChange={(e) => setRenameDescription(e.target.value)}
                          maxLength={500}
                          rows={3}
                          placeholder="Optional short context"
                          className="w-full resize-none rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-cyan focus:outline-none"
                        />
                      </div>
                      {renameError && (
                        <p className="rounded-md border border-rose-900 bg-rose-950/40 px-2.5 py-1.5 text-[11px] text-rose-300">
                          {renameError}
                        </p>
                      )}
                      <div className="flex items-center justify-end gap-2 pt-1">
                        <Dialog.Close asChild>
                          <Button type="button" variant="secondary">Cancel</Button>
                        </Dialog.Close>
                        <Button type="submit" disabled={renameMutation.isPending}>
                          {renameMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                          Save changes
                        </Button>
                      </div>
                    </form>
                  </Dialog.Content>
                </Dialog.Portal>
              </Dialog.Root>

              {/* Delete dialog with type-to-confirm gate. */}
              <Dialog.Root open={deleteOpen} onOpenChange={setDeleteOpen}>
                <Dialog.Portal>
                  <Dialog.Overlay
                    onClick={(e) => e.stopPropagation()}
                    className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm data-[state=open]:animate-in data-[state=open]:fade-in"
                  />
                  <Dialog.Content
                    onClick={(e) => e.stopPropagation()}
                    onPointerDownOutside={(e) => e.preventDefault()}
                    className="fixed left-1/2 top-1/2 z-50 w-[min(92vw,28rem)] -translate-x-1/2 -translate-y-1/2 rounded-lg border border-rose-900/60 bg-surface p-5 shadow-[0_24px_70px_rgba(0,0,0,0.55)]"
                  >
                    <div className="mb-4 flex items-start justify-between gap-3">
                      <div>
                        <Dialog.Title className="inline-flex items-center gap-2 text-base font-semibold text-rose-300">
                          <Trash2 className="h-4 w-4" />
                          Delete project
                        </Dialog.Title>
                        <Dialog.Description className="mt-1 text-xs text-zinc-300">
                          This permanently deletes <span className="font-medium text-zinc-100">{room.name}</span>,
                          including every file, every chat message, and every member.
                          This action cannot be undone.
                        </Dialog.Description>
                      </div>
                      <Dialog.Close className="rounded-md p-1 text-zinc-500 transition hover:bg-zinc-900 hover:text-zinc-200">
                        <X className="h-4 w-4" />
                      </Dialog.Close>
                    </div>
                    <form onSubmit={submitDelete} className="space-y-3">
                      <div className="space-y-1">
                        <label className="text-[11px] uppercase tracking-wide text-zinc-500" htmlFor={`delete-confirm-${room.id}`}>
                          Type <span className="font-mono text-zinc-300">{room.name}</span> to confirm
                        </label>
                        <Input
                          id={`delete-confirm-${room.id}`}
                          value={deleteConfirmText}
                          onChange={(e) => setDeleteConfirmText(e.target.value)}
                          autoFocus
                          autoComplete="off"
                        />
                      </div>
                      {deleteError && (
                        <p className="rounded-md border border-rose-900 bg-rose-950/40 px-2.5 py-1.5 text-[11px] text-rose-300">
                          {deleteError}
                        </p>
                      )}
                      <div className="flex items-center justify-end gap-2 pt-1">
                        <Dialog.Close asChild>
                          <Button type="button" variant="secondary">Cancel</Button>
                        </Dialog.Close>
                        <Button
                          type="submit"
                          disabled={deleteMutation.isPending || deleteConfirmText.trim() !== room.name}
                          className="!bg-rose-600 !shadow-[0_10px_30px_rgba(244,63,94,0.22)] hover:!bg-rose-500 hover:!shadow-[0_14px_36px_rgba(244,63,94,0.32)]"
                        >
                          {deleteMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                          Delete forever
                        </Button>
                      </div>
                    </form>
                  </Dialog.Content>
                </Dialog.Portal>
              </Dialog.Root>
            </>
          )}
        </div>
      </div>
    </MotionLink>
  );
}
