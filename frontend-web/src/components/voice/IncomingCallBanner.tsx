import { useEffect } from "react";
import { Phone, PhoneOff } from "lucide-react";

/**
 * Loops a synthesized incoming-call ring for as long as the component is
 * mounted. We generate the tones with the Web Audio API rather than ship an
 * audio asset, so the call stays fully local (no file to host or license).
 *
 * Because this lives inside {@link IncomingCallBanner} — which only mounts for
 * room members who are NOT in the call — only the call receivers ever hear it;
 * the caller is in-call and never renders the banner.
 *
 * Autoplay caveat: browsers keep an AudioContext suspended until the page has
 * seen a user gesture. A member already typing in the editor will hear the
 * ring; one who just opened the room may not until they interact — the visual
 * banner is the fallback either way.
 */
function useRingtone() {
  useEffect(() => {
    const AudioCtor =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtor) return;

    const ctx = new AudioCtor();
    void ctx.resume?.();

    // One ring = two short ascending sine tones; the interval below leaves a
    // gap before the next, giving the classic incoming-call cadence.
    // `AudioContextState` is typed without the "running" literal in this DOM
    // lib, so compare against the runtime string to avoid a TS2367 no-overlap
    // error while still gating correctly at runtime.
    const isRunning = () => (ctx.state as string) === "running";
    const playRing = () => {
      if (!isRunning()) {
        void ctx.resume?.();
        if (!isRunning()) return;
      }
      const now = ctx.currentTime;
      const tones = [
        { freq: 587.33, at: 0 }, // D5
        { freq: 783.99, at: 0.18 }, // G5
      ];
      for (const { freq, at } of tones) {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = "sine";
        osc.frequency.value = freq;
        const start = now + at;
        const dur = 0.16;
        // Tiny attack/release so the tone doesn't click on/off.
        gain.gain.setValueAtTime(0, start);
        gain.gain.linearRampToValueAtTime(0.18, start + 0.02);
        gain.gain.setValueAtTime(0.18, start + dur - 0.04);
        gain.gain.linearRampToValueAtTime(0, start + dur);
        osc.connect(gain).connect(ctx.destination);
        osc.start(start);
        osc.stop(start + dur);
      }
    };

    playRing();
    const interval = window.setInterval(playRing, 2400);

    return () => {
      window.clearInterval(interval);
      void ctx.close();
    };
  }, []);
}

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
  useRingtone();

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
