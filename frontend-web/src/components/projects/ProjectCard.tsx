import { Copy, FileCode2, Globe2, Lock, Users } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { type Room } from "@/lib/api";
import { formatRelativeDate } from "@/lib/utils";

interface ProjectCardProps {
  room: Room;
}

export function ProjectCard({ room }: ProjectCardProps) {
  const [copied, setCopied] = useState(false);

  function handleCopy(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    void navigator.clipboard.writeText(room.inviteCode).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  const isPublic = room.visibility === "PUBLIC";
  const role = room.currentUserRole ?? "guest";

  return (
    <Link
      to={`/rooms/${room.id}`}
      className="group flex flex-col rounded-lg border border-zinc-800 bg-surface p-5 transition hover:border-signature/60 hover:bg-surfaceRaised"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium text-zinc-100 group-hover:text-white">{room.name}</p>
          <p className="mt-1 truncate text-sm text-zinc-500">Owner: {room.ownerName}</p>
        </div>
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
        <span className="text-xs font-medium uppercase tracking-wide text-cyan">{role.toLowerCase()}</span>
      </div>
    </Link>
  );
}
