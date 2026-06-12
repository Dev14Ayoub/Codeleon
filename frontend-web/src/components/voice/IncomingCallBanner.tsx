import { Phone, PhoneOff } from "lucide-react";

/**
 * Ringing banner shown to room members who are not yet in a call when someone
 * starts one. Answer joins the call; Ignore dismisses the ring locally.
 */
export function IncomingCallBanner({
  callerName,
  onAnswer,
  onDismiss,
}: {
  callerName: string;
  onAnswer: () => void;
  onDismiss: () => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-emerald-900/60 bg-emerald-950/40 px-4 py-2">
      <span className="inline-flex min-w-0 items-center gap-2 text-sm text-emerald-200">
        <span className="relative flex h-2.5 w-2.5 shrink-0">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
          <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-emerald-400" />
        </span>
        <span className="truncate">
          <strong>{callerName}</strong> démarre un appel vocal…
        </span>
      </span>
      <div className="flex shrink-0 items-center gap-2">
        <button
          type="button"
          onClick={onAnswer}
          className="inline-flex items-center gap-1 rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-medium text-white transition hover:bg-emerald-500"
        >
          <Phone className="h-3.5 w-3.5" />
          Répondre
        </button>
        <button
          type="button"
          onClick={onDismiss}
          className="inline-flex items-center gap-1 rounded-md bg-zinc-800 px-3 py-1.5 text-xs text-zinc-300 transition hover:bg-zinc-700"
        >
          <PhoneOff className="h-3.5 w-3.5" />
          Ignorer
        </button>
      </div>
    </div>
  );
}
