import { useCallback, useEffect, useRef, useState } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { useAuthStore } from "@/stores/auth-store";

/** A room file shipped to the backend so the sandbox can mount it. */
export interface TerminalFile {
  path: string;
  text: string;
}

export type TerminalStatus = "idle" | "connecting" | "ready" | "exited" | "error";

/**
 * Builds the wss:// URL for the terminal endpoint. Mirrors the collab WS
 * builder: VITE_API_BASE_URL is relative in prod ("/api/v1") so we resolve
 * against the page origin, then swap http→ws.
 */
function buildTerminalWsUrl(roomId: string, token: string): string {
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1") as string;
  const url = /^https?:\/\//i.test(apiBase)
    ? new URL(apiBase)
    : new URL(apiBase, window.location.origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  const basePath = url.pathname.replace(/\/$/, "");
  url.pathname = `${basePath}/ws/rooms/${roomId}/terminal`;
  url.search = `?token=${encodeURIComponent(token)}`;
  return url.toString();
}

/**
 * Drives an interactive xterm.js terminal wired to a sandboxed bash process
 * over a WebSocket. The backend runs `bash -i` without a TTY, so this hook
 * does local echo + line buffering: printable keys are echoed and accumulated,
 * and the whole line is sent on Enter. That makes input()/Scanner feel
 * interactive without needing a real PTY.
 */
export function useRoomTerminal(options: {
  roomId: string | undefined;
  /** Only connect while the Terminal tab is actually visible. */
  active: boolean;
  getFiles: () => TerminalFile[];
}) {
  const { roomId, active, getFiles } = options;
  const accessToken = useAuthStore((state) => state.accessToken);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const termRef = useRef<Terminal | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const lineRef = useRef<string>("");
  // Keep getFiles in a ref so adding/removing a file doesn't tear down the
  // live terminal (getAllFiles is recreated whenever the file list changes).
  const getFilesRef = useRef(getFiles);
  useEffect(() => {
    getFilesRef.current = getFiles;
  }, [getFiles]);

  const [status, setStatus] = useState<TerminalStatus>("idle");
  const [restartKey, setRestartKey] = useState(0);

  const send = (payload: unknown) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
    }
  };

  /**
   * Pushes the current project files into the container, then injects a command
   * and echoes it. The sync makes "Run active file" always reflect the latest
   * editor state (the workspace is a bind mount, so the container sees it at
   * once) — no Restart needed after editing.
   */
  const runCommand = useCallback((command: string) => {
    const term = termRef.current;
    if (!term) return;
    lineRef.current = "";
    send({ type: "sync", files: getFilesRef.current() });
    term.write(`${command}\r\n`);
    send({ type: "stdin", data: `${command}\n` });
  }, []);

  const restart = useCallback(() => {
    setRestartKey((key) => key + 1);
  }, []);

  useEffect(() => {
    if (!active || !roomId || !accessToken || !containerRef.current) {
      return;
    }
    let disposed = false;

    const term = new Terminal({
      fontFamily: "Geist Mono, ui-monospace, SFMono-Regular, monospace",
      fontSize: 13,
      cursorBlink: true,
      convertEol: true,
      theme: {
        background: "#09090b",
        foreground: "#e4e4e7",
        cursor: "#6366f1",
        selectionBackground: "#27272a",
      },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(containerRef.current);
    try {
      fit.fit();
    } catch {
      /* container not laid out yet */
    }
    termRef.current = term;
    term.writeln("\x1b[2mConnecting to sandbox…\x1b[0m");
    setStatus("connecting");

    const ws = new WebSocket(buildTerminalWsUrl(roomId, accessToken));
    wsRef.current = ws;

    ws.onopen = () => {
      if (disposed) return;
      ws.send(JSON.stringify({ type: "init", files: getFilesRef.current() }));
    };

    ws.onmessage = (event) => {
      if (disposed) return;
      let msg: { type: string; data?: string; code?: number };
      try {
        msg = JSON.parse(event.data as string);
      } catch {
        return;
      }
      switch (msg.type) {
        case "ready":
          setStatus("ready");
          term.writeln(
            "\x1b[2mSandbox ready (bash). Run a file with e.g. \x1b[0mpython main.py\x1b[2m.\x1b[0m\r",
          );
          break;
        case "output":
          term.write(msg.data ?? "");
          break;
        case "error":
          term.writeln(`\r\n\x1b[31m${msg.data ?? "error"}\x1b[0m`);
          setStatus("error");
          break;
        case "exit":
          term.writeln(`\r\n\x1b[2m[shell exited with code ${msg.code}]\x1b[0m`);
          setStatus("exited");
          break;
        default:
          break;
      }
    };

    ws.onclose = () => {
      if (!disposed) setStatus((prev) => (prev === "ready" ? "exited" : prev));
    };
    ws.onerror = () => {
      if (!disposed) setStatus("error");
    };

    const dataSub = term.onData((data) => {
      if (data === "\r") {
        // Enter — flush the buffered line to the shell.
        term.write("\r\n");
        send({ type: "stdin", data: `${lineRef.current}\n` });
        lineRef.current = "";
      } else if (data === "\x7f") {
        // Backspace — erase one char locally.
        if (lineRef.current.length > 0) {
          lineRef.current = lineRef.current.slice(0, -1);
          term.write("\b \b");
        }
      } else if (data === "\x03") {
        // Ctrl+C — discard the line and ask the backend to SIGINT the shell.
        term.write("^C\r\n");
        lineRef.current = "";
        send({ type: "signal", data: "SIGINT" });
      } else if (data.charCodeAt(0) < 32 && data !== "\t") {
        // swallow other control sequences (arrows, etc.) — no PTY line editing
      } else {
        lineRef.current += data;
        term.write(data); // local echo
      }
    });

    const resizeObserver = new ResizeObserver(() => {
      try {
        fit.fit();
      } catch {
        /* noop */
      }
    });
    resizeObserver.observe(containerRef.current);
    const onWindowResize = () => {
      try {
        fit.fit();
      } catch {
        /* noop */
      }
    };
    window.addEventListener("resize", onWindowResize);

    return () => {
      disposed = true;
      window.removeEventListener("resize", onWindowResize);
      resizeObserver.disconnect();
      dataSub.dispose();
      try {
        ws.close();
      } catch {
        /* noop */
      }
      term.dispose();
      termRef.current = null;
      wsRef.current = null;
      lineRef.current = "";
    };
  }, [active, roomId, accessToken, restartKey]);

  return { containerRef, status, runCommand, restart };
}
