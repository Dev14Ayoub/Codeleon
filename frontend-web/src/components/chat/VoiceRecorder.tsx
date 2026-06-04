import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, Send, Square, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface VoiceRecorderProps {
  /** Hard cap on a single recording, in seconds. The recorder stops
   *  automatically when this is reached and surfaces a preview as if
   *  the user had clicked stop themselves. */
  maxSeconds?: number;
  /** Called when the user confirms sending the preview. The recorder
   *  resets to its idle state on a successful return. */
  onSend: (blob: Blob, durationMs: number) => Promise<void> | void;
  /** Disables the mic button while a parent operation is in flight
   *  (e.g. another send). The current preview still lets the user
   *  cancel locally so they aren't trapped in a stale UI. */
  disabled?: boolean;
}

type Phase = "idle" | "recording" | "preview" | "sending";

/**
 * Self-contained voice recorder for the peer chat.
 *
 * <p>Press the mic to start. While recording, the button turns red,
 * a live timer counts up, and a small VU bar pulses with the input
 * level so the user can tell the mic is actually picking them up.
 * Press stop to land in the preview state — an inline <audio> element
 * lets the user listen back and either send or discard.
 *
 * <p>All MediaStream tracks are stopped in every exit path (cancel,
 * send, unmount) so the browser's red mic indicator goes away
 * immediately. The blob URL for the preview is revoked on unmount
 * to avoid leaks across many recordings in one session.
 */
export function VoiceRecorder({ maxSeconds = 60, onSend, disabled }: VoiceRecorderProps) {
  const [phase, setPhase] = useState<Phase>("idle");
  const [error, setError] = useState<string | null>(null);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [level, setLevel] = useState(0);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<BlobPart[]>([]);
  const startedAtRef = useRef<number>(0);
  const tickRef = useRef<number | null>(null);
  const blobRef = useRef<Blob | null>(null);
  const durationRef = useRef<number>(0);
  // Web Audio plumbing for the VU bar — only created in recording phase.
  const audioCtxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const rafRef = useRef<number | null>(null);

  const cleanupStream = useCallback(() => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    if (tickRef.current !== null) {
      window.clearInterval(tickRef.current);
      tickRef.current = null;
    }
    if (analyserRef.current) {
      analyserRef.current.disconnect();
      analyserRef.current = null;
    }
    if (audioCtxRef.current) {
      void audioCtxRef.current.close();
      audioCtxRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }
    recorderRef.current = null;
  }, []);

  useEffect(() => {
    return () => {
      cleanupStream();
      if (previewUrl) URL.revokeObjectURL(previewUrl);
    };
    // previewUrl intentionally not in deps: we revoke on unmount only,
    // mid-session revocation happens in resetPreview().
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const resetPreview = useCallback(() => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setPreviewUrl(null);
    blobRef.current = null;
    durationRef.current = 0;
    setElapsedMs(0);
    setLevel(0);
  }, [previewUrl]);

  async function startRecording() {
    setError(null);
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      setError("Voice messages are not supported in this browser.");
      return;
    }
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (ex) {
      const message = ex instanceof Error ? ex.message : "Could not access the microphone.";
      setError(message.includes("Permission") ? "Microphone permission denied." : message);
      return;
    }
    streamRef.current = stream;
    chunksRef.current = [];

    // Pick a MIME the browser supports. Chrome/Edge prefer webm/opus,
    // Safari typically lands on audio/mp4. Both are fine — the server
    // just stores the bytes and serves them back with the same type.
    const candidates = [
      "audio/webm;codecs=opus",
      "audio/webm",
      "audio/mp4",
      "audio/ogg;codecs=opus",
    ];
    const mimeType = candidates.find((m) => MediaRecorder.isTypeSupported(m)) ?? "";
    const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
    recorderRef.current = recorder;

    recorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) chunksRef.current.push(event.data);
    };
    recorder.onstop = () => {
      const blob = new Blob(chunksRef.current, { type: recorder.mimeType || "audio/webm" });
      blobRef.current = blob;
      durationRef.current = Date.now() - startedAtRef.current;
      const url = URL.createObjectURL(blob);
      setPreviewUrl(url);
      setPhase("preview");
      cleanupStream();
    };

    // Wire up the VU meter via Web Audio. AnalyserNode reads the
    // post-mix signal which is cheap and runs every animation frame.
    try {
      const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const ctx = new Ctx();
      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 512;
      source.connect(analyser);
      audioCtxRef.current = ctx;
      analyserRef.current = analyser;
      const buffer = new Uint8Array(analyser.frequencyBinCount);
      const draw = () => {
        if (!analyserRef.current) return;
        analyserRef.current.getByteTimeDomainData(buffer);
        // Crude RMS — good enough for a 6-bar visualization.
        let sum = 0;
        for (let i = 0; i < buffer.length; i += 1) {
          const v = (buffer[i] - 128) / 128;
          sum += v * v;
        }
        const rms = Math.sqrt(sum / buffer.length);
        setLevel(Math.min(1, rms * 1.8));
        rafRef.current = requestAnimationFrame(draw);
      };
      rafRef.current = requestAnimationFrame(draw);
    } catch {
      // VU is a nice-to-have; failing to set it up shouldn't kill the recorder.
    }

    startedAtRef.current = Date.now();
    setElapsedMs(0);
    tickRef.current = window.setInterval(() => {
      const elapsed = Date.now() - startedAtRef.current;
      setElapsedMs(elapsed);
      if (elapsed >= maxSeconds * 1000) {
        stopRecording();
      }
    }, 200);

    recorder.start();
    setPhase("recording");
  }

  function stopRecording() {
    const recorder = recorderRef.current;
    if (recorder && recorder.state !== "inactive") {
      recorder.stop();
    } else {
      cleanupStream();
      setPhase("idle");
    }
  }

  function cancelPreview() {
    resetPreview();
    setPhase("idle");
  }

  async function confirmSend() {
    if (!blobRef.current) return;
    setPhase("sending");
    try {
      await onSend(blobRef.current, durationRef.current);
      resetPreview();
      setPhase("idle");
    } catch (ex) {
      setError(ex instanceof Error ? ex.message : "Failed to send voice message");
      // Stay in preview so the user can retry without re-recording.
      setPhase("preview");
    }
  }

  if (phase === "idle") {
    return (
      <div className="flex flex-col gap-1">
        <button
          type="button"
          onClick={startRecording}
          disabled={disabled}
          title="Record a voice message (auto-deleted after 24h)"
          aria-label="Record a voice message"
          className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-zinc-800 text-zinc-400 transition hover:border-cyan/50 hover:text-cyan disabled:opacity-40"
        >
          <Mic className="h-4 w-4" />
        </button>
        {error && (
          <p className="truncate text-[10px] text-rose-400" title={error}>{error}</p>
        )}
      </div>
    );
  }

  if (phase === "recording") {
    return (
      <div className="flex flex-1 items-center gap-2 rounded-md border border-rose-700/60 bg-rose-950/30 px-2.5 py-1.5">
        <span className="relative flex h-2.5 w-2.5">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-rose-500 opacity-70" />
          <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-rose-500" />
        </span>
        <VuBars level={level} />
        <span className="ml-auto font-mono text-[11px] text-rose-200">{formatDuration(elapsedMs)}</span>
        <button
          type="button"
          onClick={stopRecording}
          aria-label="Stop recording"
          className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-rose-700 bg-rose-700/40 text-rose-100 transition hover:bg-rose-700/70"
        >
          <Square className="h-3.5 w-3.5 fill-current" />
        </button>
      </div>
    );
  }

  // preview / sending
  return (
    <div className="flex flex-1 items-center gap-2 rounded-md border border-zinc-800 bg-zinc-950 px-2.5 py-1.5">
      <button
        type="button"
        onClick={cancelPreview}
        disabled={phase === "sending"}
        aria-label="Discard recording"
        className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-zinc-500 transition hover:bg-zinc-900 hover:text-rose-300 disabled:opacity-40"
        title="Discard"
      >
        <Trash2 className="h-3.5 w-3.5" />
      </button>
      {previewUrl && (
        <audio src={previewUrl} controls className="h-8 min-w-0 flex-1" preload="metadata" />
      )}
      <span className="shrink-0 font-mono text-[10px] text-zinc-500">{formatDuration(durationRef.current)}</span>
      <Button
        type="button"
        onClick={confirmSend}
        disabled={phase === "sending"}
        className="h-7 px-2 text-[11px]"
        title="Send voice message"
      >
        <Send className="h-3.5 w-3.5" />
        {phase === "sending" ? "Sending..." : "Send"}
      </Button>
    </div>
  );
}

function VuBars({ level }: { level: number }) {
  // 6 vertical bars; the lower bars light up first, higher bars need
  // more energy. Looks closer to a real meter than a uniform fill.
  const bars = 6;
  const thresholds = [0.05, 0.12, 0.22, 0.35, 0.5, 0.7];
  return (
    <div className="flex items-end gap-0.5">
      {Array.from({ length: bars }).map((_, i) => {
        const active = level >= thresholds[i];
        const heightPct = 30 + i * 10;
        return (
          <span
            key={i}
            className={cn(
              "w-0.5 rounded-sm transition-colors",
              active ? "bg-rose-300" : "bg-rose-900/60",
            )}
            style={{ height: `${heightPct}%`, minHeight: 4 }}
          />
        );
      })}
    </div>
  );
}

function formatDuration(ms: number): string {
  const totalSec = Math.round(ms / 1000);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${String(sec).padStart(2, "0")}`;
}
