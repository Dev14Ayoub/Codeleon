import { useEffect, useRef } from "react";
import { Loader2, Mic, MicOff, Phone, PhoneOff } from "lucide-react";
import { useRoomVoiceCall, type VoicePeer } from "@/lib/voice/useRoomVoiceCall";

/**
 * Voice-call control for a room. Join starts a peer-to-peer WebRTC call
 * (signaling over a WebSocket relay); audio plays automatically. Mute toggles
 * the local mic; Leave tears the call down.
 */
export function VoiceCallBar({ roomId }: { roomId: string | undefined }) {
  const { status, peers, muted, join, leave, toggleMute } = useRoomVoiceCall(roomId);

  if (status === "idle" || status === "error") {
    return (
      <div className="rounded-md border border-zinc-800 bg-zinc-950 p-3">
        <button
          type="button"
          onClick={() => void join()}
          className="inline-flex w-full items-center justify-center gap-2 rounded-md bg-emerald-600/90 px-3 py-2 text-sm font-medium text-white transition hover:bg-emerald-600"
        >
          <Phone className="h-4 w-4" />
          Démarrer un appel vocal
        </button>
        {status === "error" && (
          <p className="mt-2 text-[11px] text-rose-400">
            Micro indisponible ou connexion refusée. Vérifie les permissions du micro.
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="rounded-md border border-emerald-900/60 bg-emerald-950/20 p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="inline-flex items-center gap-2 text-sm font-medium text-emerald-300">
          {status === "connecting" ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" /> Connexion…
            </>
          ) : (
            <>
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-400" />
              </span>
              Appel en cours
            </>
          )}
        </span>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={toggleMute}
            title={muted ? "Réactiver le micro" : "Couper le micro"}
            className={`inline-flex items-center gap-1 rounded px-2 py-1 text-[11px] transition ${
              muted ? "text-rose-400 hover:bg-surfaceRaised" : "text-zinc-300 hover:bg-surfaceRaised"
            }`}
          >
            {muted ? <MicOff className="h-3.5 w-3.5" /> : <Mic className="h-3.5 w-3.5" />}
            {muted ? "Muet" : "Micro"}
          </button>
          <button
            type="button"
            onClick={leave}
            title="Quitter l'appel"
            className="inline-flex items-center gap-1 rounded bg-rose-600/90 px-2 py-1 text-[11px] font-medium text-white transition hover:bg-rose-600"
          >
            <PhoneOff className="h-3.5 w-3.5" />
            Quitter
          </button>
        </div>
      </div>

      <div className="mt-2 space-y-1">
        <p className="text-[11px] text-zinc-500">
          {peers.length === 0
            ? "Seul·e pour l'instant — invite quelqu'un à rejoindre."
            : `${peers.length} ${peers.length === 1 ? "participant" : "participants"}`}
        </p>
        {peers.map((peer) => (
          <div key={peer.id} className="flex items-center gap-2 text-xs text-zinc-300">
            <span className={`h-1.5 w-1.5 rounded-full ${peer.stream ? "bg-emerald-400" : "bg-zinc-600"}`} />
            <span className="truncate">{peer.name || "Participant"}</span>
          </div>
        ))}
      </div>

      {/* Hidden audio sinks — one per remote peer's stream. */}
      {peers.map((peer) => peer.stream && <PeerAudio key={`audio:${peer.id}`} stream={peer.stream} />)}
    </div>
  );
}

/** Attaches a remote MediaStream to an <audio> element (srcObject isn't a JSX attr). */
function PeerAudio({ stream }: { stream: VoicePeer["stream"] }) {
  const ref = useRef<HTMLAudioElement>(null);
  useEffect(() => {
    if (ref.current && stream) ref.current.srcObject = stream;
  }, [stream]);
  return <audio ref={ref} autoPlay className="hidden" />;
}
