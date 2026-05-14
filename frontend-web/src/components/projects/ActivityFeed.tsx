import { useQuery } from "@tanstack/react-query";
import { Activity, Bot, FilePlus2, FileX2, LogIn, Pencil, Play } from "lucide-react";
import { fetchActivity, type RoomEvent, type RoomEventType } from "@/lib/api";
import { formatRelativeDate } from "@/lib/utils";

interface ActivityFeedProps {
  /** Current user id, used to render "You" instead of the full name. */
  currentUserId: string | undefined;
}

const ICONS: Record<RoomEventType, JSX.Element> = {
  FILE_CREATED: <FilePlus2 className="h-3.5 w-3.5 text-emerald-400" />,
  FILE_RENAMED: <Pencil className="h-3.5 w-3.5 text-amber-400" />,
  FILE_DELETED: <FileX2 className="h-3.5 w-3.5 text-rose-400" />,
  MEMBER_JOINED: <LogIn className="h-3.5 w-3.5 text-cyan" />,
  CODE_RAN: <Play className="h-3.5 w-3.5 text-violet" />,
  AI_ASKED: <Bot className="h-3.5 w-3.5 text-signature" />,
};

/**
 * Builds the verb phrase for one event. The actor ("You" / a name) and
 * the room name are rendered separately by the caller, so this returns
 * just the middle part: "created main.py", "ran code (exit 0)", etc.
 */
function describe(event: RoomEvent): string {
  const p = event.payload ?? {};
  switch (event.type) {
    case "FILE_CREATED":
      return `created ${p.path ?? "a file"}`;
    case "FILE_RENAMED":
      return p.from && p.to ? `renamed ${p.from} → ${p.to}` : "renamed a file";
    case "FILE_DELETED":
      return `deleted ${p.path ?? "a file"}`;
    case "MEMBER_JOINED":
      return "joined";
    case "CODE_RAN":
      return p.exitCode !== undefined ? `ran code (exit ${p.exitCode})` : "ran code";
    case "AI_ASKED":
      return "asked the AI";
    default:
      return "did something";
  }
}

export function ActivityFeed({ currentUserId }: ActivityFeedProps) {
  // Poll every 30s. We re-pull the whole page (capped at 50 server-side)
  // rather than diffing with ?since= — simpler, and the payload is tiny.
  const { data, isLoading, isError } = useQuery({
    queryKey: ["activity"],
    queryFn: () => fetchActivity(),
    refetchInterval: 30_000,
  });

  return (
    <div className="rounded-lg border border-zinc-800 bg-surface">
      <div className="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
        <Activity className="h-4 w-4 text-cyan" />
        <h3 className="text-sm font-medium text-zinc-100">Recent activity</h3>
      </div>

      <div className="max-h-[28rem] overflow-y-auto">
        {isLoading && (
          <div className="space-y-3 p-4">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="h-9 animate-pulse rounded bg-zinc-900" />
            ))}
          </div>
        )}

        {isError && (
          <p className="p-4 text-xs text-zinc-500">Could not load activity right now.</p>
        )}

        {!isLoading && !isError && (data?.length ?? 0) === 0 && (
          <p className="p-4 text-xs text-zinc-500">
            No activity yet. Create a file, run some code, or invite a teammate.
          </p>
        )}

        {!isLoading && !isError && (data?.length ?? 0) > 0 && (
          <ul className="divide-y divide-zinc-800/60">
            {data!.map((event) => {
              const actor =
                event.userId && event.userId === currentUserId
                  ? "You"
                  : event.userName ?? "Someone";
              return (
                <li key={event.id} className="flex gap-3 px-4 py-3">
                  <span className="mt-0.5 shrink-0">{ICONS[event.type] ?? <Activity className="h-3.5 w-3.5 text-zinc-500" />}</span>
                  <div className="min-w-0 flex-1">
                    <p className="text-xs leading-5 text-zinc-300">
                      <span className="font-medium text-zinc-100">{actor}</span> {describe(event)}{" "}
                      <span className="text-zinc-500">in</span>{" "}
                      <span className="font-medium text-zinc-200">{event.roomName}</span>
                    </p>
                    <p className="mt-0.5 text-[11px] text-zinc-600">{formatRelativeDate(event.createdAt)}</p>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
