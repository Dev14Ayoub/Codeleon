import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { useAuthStore } from "@/stores/auth-store";

export interface VoicePeer {
  id: string;
  name: string;
  stream?: MediaStream;
}

export type VoiceStatus = "idle" | "connecting" | "in-call" | "error";

/**
 * Local-first WebRTC config. We rely on host candidates — on the tailnet peers
 * have mutually reachable 100.x addresses, so calls connect without an external
 * STUN/TURN (keeps the "100% local" property). A self-hosted coturn can be added
 * later for off-tailnet robustness.
 */
const RTC_CONFIG: RTCConfiguration = { iceServers: [] };

function buildVoiceWsUrl(roomId: string, token: string): string {
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1") as string;
  const url = /^https?:\/\//i.test(apiBase)
    ? new URL(apiBase)
    : new URL(apiBase, window.location.origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  const basePath = url.pathname.replace(/\/$/, "");
  url.pathname = `${basePath}/ws/rooms/${roomId}/voice`;
  url.search = `?token=${encodeURIComponent(token)}`;
  return url.toString();
}

/** Minimal slice of the collab awareness used to broadcast call presence. */
export interface VoiceAwareness {
  setLocalStateField: (field: string, value: unknown) => void;
}

/**
 * Drives a peer-to-peer voice call for a room. Signaling rides a WebSocket
 * relay; audio is a direct encrypted WebRTC connection per remote peer (mesh).
 * The joining peer offers to existing peers; existing peers answer.
 *
 * <p>Pass the collab {@code awareness} so the call broadcasts presence to the
 * room — members not yet in the call use it to ring an incoming-call banner.
 */
export function useRoomVoiceCall(
  roomId: string | undefined,
  awareness?: VoiceAwareness | null,
) {
  const accessToken = useAuthStore((s) => s.accessToken);

  const [status, setStatus] = useState<VoiceStatus>("idle");
  const [peers, setPeers] = useState<VoicePeer[]>([]);
  const [muted, setMuted] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const localStreamRef = useRef<MediaStream | null>(null);
  const pcsRef = useRef<Map<string, RTCPeerConnection>>(new Map());
  // ICE candidates arriving before the remote description is set are buffered
  // here (per peer) then flushed — key to reliable mesh connections.
  const pendingCandidatesRef = useRef<Map<string, RTCIceCandidateInit[]>>(new Map());

  const sendSignal = (to: string, data: unknown) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "signal", to, data }));
    }
  };

  const upsertPeer = (peer: VoicePeer) =>
    setPeers((prev) => {
      const idx = prev.findIndex((p) => p.id === peer.id);
      if (idx < 0) return [...prev, peer];
      const next = [...prev];
      next[idx] = { ...next[idx], ...peer };
      return next;
    });

  const dropPeer = (id: string) => {
    pcsRef.current.get(id)?.close();
    pcsRef.current.delete(id);
    pendingCandidatesRef.current.delete(id);
    setPeers((prev) => prev.filter((p) => p.id !== id));
  };

  const createPeerConnection = useCallback((peerId: string): RTCPeerConnection => {
    const existing = pcsRef.current.get(peerId);
    if (existing) return existing;

    const pc = new RTCPeerConnection(RTC_CONFIG);
    localStreamRef.current?.getTracks().forEach((track) => {
      pc.addTrack(track, localStreamRef.current!);
    });
    pc.onicecandidate = (event) => {
      if (event.candidate) sendSignal(peerId, { candidate: event.candidate });
    };
    pc.ontrack = (event) => {
      upsertPeer({ id: peerId, name: "", stream: event.streams[0] });
    };
    pcsRef.current.set(peerId, pc);
    return pc;
  }, []);

  const leave = useCallback(() => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "leave" }));
    }
    try {
      ws?.close();
    } catch {
      /* noop */
    }
    wsRef.current = null;
    pcsRef.current.forEach((pc) => pc.close());
    pcsRef.current.clear();
    pendingCandidatesRef.current.clear();
    localStreamRef.current?.getTracks().forEach((t) => t.stop());
    localStreamRef.current = null;
    setPeers([]);
    setMuted(false);
    setErrorMessage(null);
    setStatus("idle");
  }, []);

  const join = useCallback(async () => {
    if (!roomId || !accessToken || status !== "idle") return;
    setErrorMessage(null);
    setStatus("connecting");
    try {
      localStreamRef.current = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      setErrorMessage("Micro indisponible — vérifie les permissions du navigateur.");
      setStatus("error");
      return;
    }

    const ws = new WebSocket(buildVoiceWsUrl(roomId, accessToken));
    wsRef.current = ws;

    ws.onopen = () => setStatus("in-call");
    ws.onerror = () => setStatus("error");
    ws.onclose = () => {
      // Remote close — tear down locally.
      pcsRef.current.forEach((pc) => pc.close());
      pcsRef.current.clear();
      localStreamRef.current?.getTracks().forEach((t) => t.stop());
      localStreamRef.current = null;
      setPeers([]);
      setStatus((prev) => (prev === "error" ? prev : "idle"));
    };

    ws.onmessage = async (event) => {
      let msg: {
        type: string;
        peers?: { id: string; name: string }[];
        id?: string;
        name?: string;
        max?: number;
        from?: string;
        data?: { sdp?: RTCSessionDescriptionInit; candidate?: RTCIceCandidateInit };
      };
      try {
        msg = JSON.parse(event.data as string);
      } catch {
        return;
      }

      if (msg.type === "peers") {
        // We are the newcomer — offer to every existing peer.
        for (const peer of msg.peers ?? []) {
          upsertPeer({ id: peer.id, name: peer.name });
          const pc = createPeerConnection(peer.id);
          const offer = await pc.createOffer();
          await pc.setLocalDescription(offer);
          sendSignal(peer.id, { sdp: offer });
        }
      } else if (msg.type === "peer-joined" && msg.id) {
        // A newcomer will offer to us — just record them.
        upsertPeer({ id: msg.id, name: msg.name ?? "" });
      } else if (msg.type === "peer-left" && msg.id) {
        dropPeer(msg.id);
      } else if (msg.type === "full") {
        setErrorMessage(`L'appel est complet (max ${msg.max ?? 4} participants).`);
        setStatus("error");
        try {
          wsRef.current?.close();
        } catch {
          /* noop */
        }
      } else if (msg.type === "signal" && msg.from && msg.data) {
        const from = msg.from;
        const pc = createPeerConnection(from);
        if (msg.data.sdp) {
          await pc.setRemoteDescription(msg.data.sdp);
          // Remote description set — flush ICE candidates we buffered for it.
          const buffered = pendingCandidatesRef.current.get(from);
          if (buffered) {
            for (const candidate of buffered) {
              try {
                await pc.addIceCandidate(candidate);
              } catch {
                /* noop */
              }
            }
            pendingCandidatesRef.current.delete(from);
          }
          if (msg.data.sdp.type === "offer") {
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            sendSignal(from, { sdp: answer });
          }
        } else if (msg.data.candidate) {
          if (pc.remoteDescription) {
            try {
              await pc.addIceCandidate(msg.data.candidate);
            } catch {
              /* noop */
            }
          } else {
            const list = pendingCandidatesRef.current.get(from) ?? [];
            list.push(msg.data.candidate);
            pendingCandidatesRef.current.set(from, list);
          }
        }
      }
    };
  }, [roomId, accessToken, status, createPeerConnection]);

  const toggleMute = useCallback(() => {
    const stream = localStreamRef.current;
    if (!stream) return;
    const nextMuted = !muted;
    stream.getAudioTracks().forEach((t) => {
      t.enabled = !nextMuted;
    });
    setMuted(nextMuted);
  }, [muted]);

  // Broadcast call presence to the room over the (always-connected) collab
  // awareness, so members not in the call can ring an incoming-call banner.
  useEffect(() => {
    awareness?.setLocalStateField("voice", {
      inCall: status === "in-call" || status === "connecting",
    });
  }, [status, awareness]);

  // Tear down on unmount / room change.
  useEffect(() => () => leave(), [leave]);

  return { status, peers, muted, errorMessage, join, leave, toggleMute };
}

export type RoomVoiceCall = ReturnType<typeof useRoomVoiceCall>;

/**
 * Shares the single room voice-call instance (owned by RoomPage) with the
 * deeply-nested VoiceCallBar, without threading props through the right panel.
 */
export const VoiceCallContext = createContext<RoomVoiceCall | null>(null);

export function useVoiceCallContext(): RoomVoiceCall | null {
  return useContext(VoiceCallContext);
}
