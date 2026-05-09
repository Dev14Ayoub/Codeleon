import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Archive, ArchiveRestore, Copy, FileCode2, Globe2, Lock, MoreHorizontal, Pin, PinOff, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { archiveRoom, pinRoom, unarchiveRoom, unpinRoom, type Room } from "@/lib/api";
import { formatRelativeDate } from "@/lib/utils";

interface ProjectCardProps {
  room: Room;
}

export function ProjectCard({ room }: ProjectCardProps) {
  const [copied, setCopied] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const queryClient = useQueryClient();

  // Close the ⋯ menu on outside click. We listen at the document level
  // and bail out when the click target is inside our menu container.
  useEffect(() => {
    if (!menuOpen) return;
    function onClick(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
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
    setMenuOpen((v) => !v);
  }

  function handleArchive(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    archiveMutation.mutate();
  }

  const isPublic = room.visibility === "PUBLIC";
  const isOwner = room.currentUserRole === "OWNER";
  const role = room.currentUserRole ?? "guest";

  return (
    <Link
      to={`/rooms/${room.id}`}
      className={
        room.archived
          ? "group relative flex flex-col rounded-lg border border-zinc-800 bg-surface/60 p-5 opacity-70 transition hover:border-signature/60 hover:bg-surfaceRaised hover:opacity-100"
          : "group relative flex flex-col rounded-lg border border-zinc-800 bg-surface p-5 transition hover:border-signature/60 hover:bg-surfaceRaised"
      }
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            {room.pinned && <Pin className="h-3.5 w-3.5 shrink-0 fill-cyan text-cyan" aria-label="Pinned" />}
            <p className="truncate font-medium text-zinc-100 group-hover:text-white">{room.name}</p>
          </div>
          <p className="mt-1 truncate text-sm text-zinc-500">Owner: {room.ownerName}</p>
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
        <p className="mt-3 line-clamp-2 text-sm leading-6 text-zinc-400">{room.description}</p>
      ) : (
        <p className="mt-3 text-sm italic text-zinc-600">No description</p>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-zinc-500">
        <span className="inline-flex items-center gap-1.5">
          <FileCode2 className="h-3.5 w-3.5" />
          {room.fileCount} {room.fileCount === 1 ? "file" : "files"}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <Users className="h-3.5 w-3.5" />
          {room.memberCount} {room.memberCount === 1 ? "member" : "members"}
        </span>
        <span>Updated {formatRelativeDate(room.updatedAt)}</span>
      </div>

      <div className="mt-5 flex items-center justify-between gap-3 border-t border-zinc-800 pt-4">
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
            className="rounded-md p-1 text-zinc-500 transition hover:bg-zinc-900 hover:text-zinc-200 disabled:opacity-50"
            title={room.pinned ? "Unpin" : "Pin to top"}
            aria-label={room.pinned ? "Unpin project" : "Pin project"}
          >
            {room.pinned ? <PinOff className="h-3.5 w-3.5" /> : <Pin className="h-3.5 w-3.5" />}
          </button>
          {isOwner && (
            <div className="relative" ref={menuRef}>
              <button
                type="button"
                onClick={handleMenuToggle}
                className="rounded-md p-1 text-zinc-500 transition hover:bg-zinc-900 hover:text-zinc-200"
                title="More actions"
                aria-haspopup="menu"
                aria-expanded={menuOpen}
              >
                <MoreHorizontal className="h-3.5 w-3.5" />
              </button>
              {menuOpen && (
                <div
                  role="menu"
                  className="absolute right-0 top-full z-10 mt-1 w-48 rounded-md border border-zinc-700 bg-zinc-950 p-1 shadow-lg"
                >
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
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </Link>
  );
}
