import { useEffect, useMemo, useRef, useState } from "react";
import * as Y from "yjs";
import { WebsocketProvider } from "y-websocket";
import { api } from "@/lib/api";
import { useAuthStore } from "@/stores/auth-store";

export interface CollabPeer {
  clientId: number;
  userId: string;
  name: string;
  color: string;
}

export interface CollabRoom {
  ydoc: Y.Doc;
  yText: Y.Text;
  provider: WebsocketProvider | null;
  awareness: WebsocketProvider["awareness"] | null;
  isConnected: boolean;
  isReady: boolean;
  peers: CollabPeer[];
}

const SNAPSHOT_DEBOUNCE_MS = 3000;
const Y_TEXT_KEY = "main";

function pickColor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  const hue = Math.abs(hash) % 360;
  return `hsl(${hue} 80% 60%)`;
}

function buildWebsocketUrl(): { base: string } {
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1") as string;
  const url = new URL(apiBase);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  const path = url.pathname.endsWith("/")
    ? `${url.pathname}ws/rooms`
    : `${url.pathname}/ws/rooms`;
  url.pathname = path;
  return { base: url.toString().replace(/\/$/, "") };
}

export function useCollabRoom(roomId: string | undefined): CollabRoom {
  const accessToken = useAuthStore((state) => state.accessToken);
  const user = useAuthStore((state) => state.user);

  const ydoc = useMemo(() => new Y.Doc(), [roomId]);
  const yText = useMemo(() => ydoc.getText(Y_TEXT_KEY), [ydoc]);

  const providerRef = useRef<WebsocketProvider | null>(null);
  const snapshotTimerRef = useRef<number | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const [isReady, setIsReady] = useState(false);
  const [peers, setPeers] = useState<CollabPeer[]>([]);

  useEffect(() => {
    if (!roomId || !accessToken || !user) {
      return;
    }

    let cancelled = false;
    const { base } = buildWebsocketUrl();

    async function bootstrap() {
      try {
        const snapshot = await api.get<ArrayBuffer>(`/rooms/${roomId}/snapshot`, {
          responseType: "arraybuffer",
        });
        if (cancelled) return;
        const bytes = new Uint8Array(snapshot.data);
        if (bytes.byteLength > 0) {
          Y.applyUpdate(ydoc, bytes, "remote-snapshot");
        }
      } catch (error) {
        console.warn("Failed to load room snapshot", error);
      }

      if (cancelled) return;

      const provider = new WebsocketProvider(base, roomId!, ydoc, {
        params: { token: accessToken! },
        connect: true,
      });
      providerRef.current = provider;

      provider.awareness.setLocalStateField("user", {
        id: user!.id,
        name: user!.fullName,
        color: pickColor(user!.id),
      });

      provider.on("status", ({ status }: { status: string }) => {
        if (cancelled) return;
        const connected = status === "connected";
        setIsConnected(connected);
        // Our backend is a relay-only WS (no server-side Y.Doc), so
        // y-websocket's "sync" event never fires when you are alone in
        // the room. The snapshot REST call already loaded the prior
        // state before the WS opened, so once the WS is connected we
        // are effectively ready.
        if (connected) setIsReady(true);
      });

      provider.on("sync", (synced: boolean) => {
        if (cancelled) return;
        if (synced) setIsReady(true);
      });

      const refreshPeers = () => {
        if (cancelled) return;
        const states = Array.from(provider.awareness.getStates().entries());
        const next: CollabPeer[] = states
          .map(([clientId, state]) => {
            const userState = state?.user as
              | { id: string; name: string; color: string }
              | undefined;
            if (!userState) return null;
            return {
              clientId,
              userId: userState.id,
              name: userState.name,
              color: userState.color,
            };
          })
          .filter((peer): peer is CollabPeer => peer !== null);
        setPeers(next);
      };

      provider.awareness.on("change", refreshPeers);
      refreshPeers();

      const scheduleSnapshot = () => {
        if (snapshotTimerRef.current !== null) {
          window.clearTimeout(snapshotTimerRef.current);
        }
        snapshotTimerRef.current = window.setTimeout(async () => {
          snapshotTimerRef.current = null;
          try {
            const update = Y.encodeStateAsUpdate(ydoc);
            await api.put(`/rooms/${roomId}/snapshot`, update, {
              headers: { "Content-Type": "application/octet-stream" },
              transformRequest: [(data) => data],
            });
          } catch (error) {
            console.warn("Failed to push room snapshot", error);
          }
        }, SNAPSHOT_DEBOUNCE_MS);
      };

      const onUpdate = (_update: Uint8Array, origin: unknown) => {
        if (origin === "remote-snapshot") return;
        scheduleSnapshot();
      };

      ydoc.on("update", onUpdate);

      provider.on("connection-close", () => {
        if (cancelled) return;
        setIsConnected(false);
      });
    }

    void bootstrap();

    return () => {
      cancelled = true;
      if (snapshotTimerRef.current !== null) {
        window.clearTimeout(snapshotTimerRef.current);
        snapshotTimerRef.current = null;
      }
      const provider = providerRef.current;
      if (provider) {
        try {
          const update = Y.encodeStateAsUpdate(ydoc);
          void api
            .put(`/rooms/${roomId}/snapshot`, update, {
              headers: { "Content-Type": "application/octet-stream" },
              transformRequest: [(data) => data],
            })
            .catch(() => {});
        } catch {
          // ignore
        }
        provider.disconnect();
        provider.destroy();
        providerRef.current = null;
      }
      ydoc.destroy();
    };
  }, [roomId, accessToken, user, ydoc]);

  return {
    ydoc,
    yText,
    provider: providerRef.current,
    awareness: providerRef.current?.awareness ?? null,
    isConnected,
    isReady,
    peers,
  };
}
