import { useCallback, useEffect, useState } from "react";
import { ExternalLink, Globe, Loader2, Play, RotateCw, Square } from "lucide-react";
import {
  getApiErrorMessage,
  getPreviewStatus,
  previewIframeUrl,
  startPreview,
  stopPreview,
  type IndexFile,
} from "@/lib/api";

// Default dev-server command. The server MUST bind 0.0.0.0:8000 to be reachable
// from the backend (a different container); the flags below do that for Vite.
const DEFAULT_COMMAND = "npm install && npm run dev -- --host 0.0.0.0 --port 8000";

/**
 * Live web preview: starts a sandboxed dev-server container and shows it in an
 * iframe, reverse-proxied by the backend. The first start may take a while
 * (npm install); until the server is up, the proxy returns a "not ready"
 * notice — click Reload once it has booted.
 */
export function PreviewPanel({
  roomId,
  active,
  getFiles,
}: {
  roomId: string | undefined;
  /** Only fetch status / show the iframe while the tab is visible. */
  active: boolean;
  getFiles: () => IndexFile[];
}) {
  const [command, setCommand] = useState(DEFAULT_COMMAND);
  const [running, setRunning] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [iframeKey, setIframeKey] = useState(0);

  const url = roomId ? previewIframeUrl(roomId) : "";

  // Reflect an already-running preview when the tab becomes visible.
  useEffect(() => {
    if (!active || !roomId) return;
    let cancelled = false;
    getPreviewStatus(roomId)
      .then((s) => {
        if (!cancelled) {
          setRunning(s.running);
          if (s.command) setCommand(s.command);
        }
      })
      .catch(() => {
        /* ignore — treat as not running */
      });
    return () => {
      cancelled = true;
    };
  }, [active, roomId]);

  const start = useCallback(async () => {
    if (!roomId) return;
    setPending(true);
    setError(null);
    try {
      await startPreview(roomId, command, getFiles());
      setRunning(true);
      // Give the dev server a head start, then load the iframe. The proxy shows
      // a friendly notice until the port is actually up.
      window.setTimeout(() => setIframeKey((k) => k + 1), 2000);
    } catch (e) {
      setError(getApiErrorMessage(e, "Failed to start preview"));
    } finally {
      setPending(false);
    }
  }, [roomId, command, getFiles]);

  const stop = useCallback(async () => {
    if (!roomId) return;
    setPending(true);
    try {
      await stopPreview(roomId);
      setRunning(false);
    } catch (e) {
      setError(getApiErrorMessage(e, "Failed to stop preview"));
    } finally {
      setPending(false);
    }
  }, [roomId]);

  return (
    <div className="flex h-full flex-col bg-zinc-950">
      <div className="flex h-8 shrink-0 items-center gap-2 border-b border-zinc-800 bg-surface px-3">
        <Globe className="h-3.5 w-3.5 shrink-0 text-zinc-500" />
        <input
          value={command}
          onChange={(e) => setCommand(e.target.value)}
          spellCheck={false}
          disabled={pending}
          placeholder="Commande du serveur de dev (doit écouter sur 0.0.0.0:8000)"
          className="h-6 min-w-0 flex-1 rounded bg-zinc-900 px-2 font-mono text-[11px] text-zinc-200 outline-none placeholder:text-zinc-600"
        />
        {running ? (
          <button
            type="button"
            onClick={stop}
            disabled={pending}
            className="inline-flex items-center gap-1 rounded px-2 py-1 font-mono text-[11px] text-rose-400 transition hover:bg-surfaceRaised disabled:opacity-50"
          >
            {pending ? <Loader2 className="h-3 w-3 animate-spin" /> : <Square className="h-3 w-3" />}
            Stop
          </button>
        ) : (
          <button
            type="button"
            onClick={start}
            disabled={pending}
            className="inline-flex items-center gap-1 rounded px-2 py-1 font-mono text-[11px] text-emerald-400 transition hover:bg-surfaceRaised disabled:opacity-50"
          >
            {pending ? <Loader2 className="h-3 w-3 animate-spin" /> : <Play className="h-3 w-3" />}
            Start
          </button>
        )}
        <button
          type="button"
          onClick={() => setIframeKey((k) => k + 1)}
          disabled={!running}
          title="Recharger l'aperçu"
          className="inline-flex items-center gap-1 rounded px-2 py-1 font-mono text-[11px] text-zinc-400 transition hover:bg-surfaceRaised disabled:cursor-not-allowed disabled:text-zinc-700"
        >
          <RotateCw className="h-3 w-3" />
        </button>
        {running && (
          <a
            href={url}
            target="_blank"
            rel="noreferrer"
            title="Ouvrir dans un nouvel onglet"
            className="inline-flex items-center rounded px-1.5 py-1 text-zinc-400 transition hover:bg-surfaceRaised"
          >
            <ExternalLink className="h-3 w-3" />
          </a>
        )}
      </div>

      {error && (
        <div className="shrink-0 border-b border-zinc-800 bg-rose-950/30 px-3 py-1 font-mono text-[11px] text-rose-300">
          {error}
        </div>
      )}

      <div className="min-h-0 flex-1">
        {running ? (
          <iframe
            key={iframeKey}
            title="Aperçu live"
            src={url}
            sandbox="allow-scripts allow-forms allow-same-origin allow-popups"
            className="h-full w-full border-0 bg-white"
          />
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center text-zinc-500">
            <Globe className="h-6 w-6 text-zinc-700" />
            <p className="max-w-sm font-mono text-xs leading-5">
              Lance un serveur de dev pour voir ton app en direct. Le premier
              démarrage peut prendre un moment (installation des dépendances),
              puis clique sur recharger.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
