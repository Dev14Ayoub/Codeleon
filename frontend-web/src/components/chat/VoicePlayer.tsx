import { Clock, Loader2, Mic, Pause, Play } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useAuthStore } from "@/stores/auth-store";
import { cn } from "@/lib/utils";

interface VoicePlayerProps {
  /** Authenticated URL of the audio bytes — fetched with a Bearer
   *  token and converted to a blob URL so the <audio> element can
   *  consume it. */
  fileUrl: string;
  /** Optional duration cached at upload time. Lets us render the
   *  total length without waiting for the <audio> metadata event. */
  durationMs: number | null;
  /** ISO timestamp at which the message will be purged server-side.
   *  Null when the message is not ephemeral (shouldn't happen for
   *  voice but safe to support). */
  expiresAt: string | null;
  /** Rendered slightly differently when the bubble is on the right
   *  (current user) vs the left (someone else). */
  isMine: boolean;
}

/**
 * Compact audio player for voice messages.
 *
 * <p>The bytes are fetched with the same Bearer-token dance the image
 * preview uses, then handed to a native {@code <audio>} element through
 * a blob URL. A single ▶/⏸ button drives playback and we track the
 * current position to render an inline progress bar.
 *
 * <p>A small countdown chip beside the duration tells the recipient
 * how long this message has left before the cleanup job removes it.
 * When the timer hits zero we self-hide so the UI doesn't show a
 * ghost row between the deletion and the next page reload.
 */
export function VoicePlayer({ fileUrl, durationMs, expiresAt, isMine }: VoicePlayerProps) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [playing, setPlaying] = useState(false);
  const [currentMs, setCurrentMs] = useState(0);
  const [totalMs, setTotalMs] = useState<number>(durationMs ?? 0);
  const [expiresInMs, setExpiresInMs] = useState<number | null>(
    expiresAt ? Math.max(0, new Date(expiresAt).getTime() - Date.now()) : null,
  );
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // Fetch + blob-url the audio bytes once.
  useEffect(() => {
    let cancelled = false;
    let created: string | null = null;
    const token = useAuthStore.getState().accessToken;
    fetch(fileUrl, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        created = URL.createObjectURL(blob);
        setBlobUrl(created);
      })
      .catch((ex) => {
        if (cancelled) return;
        setError(ex instanceof Error ? ex.message : "Failed to load voice message");
      });
    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [fileUrl]);

  // Countdown tick — once per second is plenty for "in X h Y min".
  useEffect(() => {
    if (!expiresAt) return;
    const target = new Date(expiresAt).getTime();
    const update = () => setExpiresInMs(Math.max(0, target - Date.now()));
    update();
    const id = window.setInterval(update, 1000);
    return () => window.clearInterval(id);
  }, [expiresAt]);

  // Audio element callbacks. Total duration may not be known until
  // loadedmetadata fires (some browsers, especially for opus); the
  // cached durationMs from upload covers the initial render.
  useEffect(() => {
    const el = audioRef.current;
    if (!el) return;
    function onTime() { setCurrentMs((el?.currentTime ?? 0) * 1000); }
    function onMeta() {
      const d = el?.duration;
      if (typeof d === "number" && isFinite(d) && d > 0) setTotalMs(d * 1000);
    }
    function onEnd() { setPlaying(false); setCurrentMs(0); }
    function onPause() { setPlaying(false); }
    function onPlay() { setPlaying(true); }
    el.addEventListener("timeupdate", onTime);
    el.addEventListener("loadedmetadata", onMeta);
    el.addEventListener("ended", onEnd);
    el.addEventListener("pause", onPause);
    el.addEventListener("play", onPlay);
    return () => {
      el.removeEventListener("timeupdate", onTime);
      el.removeEventListener("loadedmetadata", onMeta);
      el.removeEventListener("ended", onEnd);
      el.removeEventListener("pause", onPause);
      el.removeEventListener("play", onPlay);
    };
  }, [blobUrl]);

  function toggle() {
    const el = audioRef.current;
    if (!el) return;
    if (el.paused) {
      void el.play();
    } else {
      el.pause();
    }
  }

  // Once the expiration has lapsed locally, hide the player. The row
  // is going to disappear at the next server reload anyway; this
  // avoids a confusing "play" that 404s on the server.
  if (expiresAt && expiresInMs === 0) {
    return (
      <div className="flex items-center gap-2 px-2.5 py-2 text-[11px] text-zinc-500">
        <Mic className="h-3 w-3" />
        Voice message expired.
      </div>
    );
  }

  const progress = totalMs > 0 ? Math.min(1, currentMs / totalMs) : 0;

  return (
    <div className={cn(
      "flex items-center gap-2 px-2.5 py-2",
      isMine ? "text-zinc-100" : "text-zinc-200",
    )}>
      <button
        type="button"
        onClick={toggle}
        disabled={!blobUrl || !!error}
        aria-label={playing ? "Pause voice message" : "Play voice message"}
        className={cn(
          "inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border transition disabled:opacity-50",
          isMine
            ? "border-cyan/40 bg-cyan/15 text-cyan hover:bg-cyan/25"
            : "border-zinc-700 bg-zinc-800 text-zinc-100 hover:bg-zinc-700",
        )}
      >
        {!blobUrl && !error ? (
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
        ) : playing ? (
          <Pause className="h-3.5 w-3.5 fill-current" />
        ) : (
          <Play className="h-3.5 w-3.5 fill-current" />
        )}
      </button>

      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="relative h-1 w-full rounded-full bg-zinc-800/70 overflow-hidden">
          <div
            className={cn(
              "absolute inset-y-0 left-0 transition-[width] duration-150",
              isMine ? "bg-cyan" : "bg-zinc-400",
            )}
            style={{ width: `${progress * 100}%` }}
          />
        </div>
        <div className="flex items-center gap-2 text-[10px] text-zinc-400">
          <span className="font-mono">{formatDuration(currentMs)} / {formatDuration(totalMs)}</span>
          {expiresInMs !== null && (
            <span
              className={cn(
                "inline-flex items-center gap-1 rounded-full border px-1.5 py-0.5",
                expiresInMs < 60 * 60 * 1000
                  ? "border-amber-700/60 bg-amber-950/40 text-amber-300"
                  : "border-zinc-700 bg-zinc-900 text-zinc-500",
              )}
              title="This voice message will be deleted automatically."
            >
              <Clock className="h-2.5 w-2.5" />
              {formatExpiresIn(expiresInMs)}
            </span>
          )}
        </div>
      </div>

      {blobUrl && <audio ref={audioRef} src={blobUrl} preload="metadata" className="hidden" />}
      {error && (
        <span className="text-[11px] text-rose-400">{error}</span>
      )}
    </div>
  );
}

function formatDuration(ms: number): string {
  const totalSec = Math.max(0, Math.round(ms / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${String(sec).padStart(2, "0")}`;
}

function formatExpiresIn(ms: number): string {
  if (ms <= 0) return "expired";
  const totalSec = Math.floor(ms / 1000);
  const hours = Math.floor(totalSec / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  if (hours >= 1) return `${hours}h ${minutes}m left`;
  if (minutes >= 1) return `${minutes}m left`;
  return `${totalSec}s left`;
}
