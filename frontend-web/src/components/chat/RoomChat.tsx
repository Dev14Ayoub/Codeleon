import { ChangeEvent, FormEvent, useEffect, useMemo, useRef, useState } from "react";
import * as Y from "yjs";
import { File as FileIcon, Image as ImageIcon, Loader2, Paperclip, Send, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  API_BASE_URL,
  fetchRoomPeerChatHistory,
  peerChatFileUrl,
  sendRoomPeerChatFile,
  sendRoomPeerChatMessage,
  type RoomPeerChatMessage as ApiPeerChatMessage,
} from "@/lib/api";
import { useAuthStore } from "@/stores/auth-store";
import { cn } from "@/lib/utils";
import { VoicePlayer } from "@/components/chat/VoicePlayer";
import { VoiceRecorder } from "@/components/chat/VoiceRecorder";

/**
 * Y.Array key inside the room's shared Y.Doc. Used purely as a
 * realtime broadcast channel between peers — the canonical store is
 * the Postgres table {@code room_peer_chat_messages}. When the
 * snapshot resets, history survives in the DB and the frontend
 * rehydrates on mount.
 */
const CHAT_BROADCAST_KEY = "room-chat";

/** Hard cap on how many messages we keep in the Y.Array. The DB has
 *  the full history; this is just a sliding window for live sync. */
const MAX_BROADCAST_MESSAGES = 200;

/** Mirror of the API response shape. Keeps the local Y.Array in sync
 *  with what the backend stores so the two layers can be merged by id. */
interface BroadcastMessage {
  id: string;
  userId: string | null;
  userName: string;
  color: string;
  content: string;
  fileName: string | null;
  fileType: string | null;
  fileSize: number | null;
  audioDurationMs: number | null;
  expiresAt: string | null;
  createdAt: string;
}

interface RoomChatProps {
  ydoc: Y.Doc;
  roomId: string;
  currentUserId: string | undefined;
  currentUserName: string | undefined;
  /** Read-only view, e.g. when the owner is reviewing another member. */
  canSend: boolean;
}

function pickColor(seed: string | null | undefined): string {
  if (!seed) return "hsl(0 0% 50%)";
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  const hue = Math.abs(hash) % 360;
  return `hsl(${hue} 80% 60%)`;
}

function fromApi(msg: ApiPeerChatMessage): BroadcastMessage {
  return {
    id: msg.id,
    userId: msg.userId,
    userName: msg.userName,
    color: pickColor(msg.userId),
    content: msg.content,
    fileName: msg.fileName,
    fileType: msg.fileType,
    fileSize: msg.fileSize,
    audioDurationMs: msg.audioDurationMs ?? null,
    expiresAt: msg.expiresAt ?? null,
    createdAt: msg.createdAt,
  };
}

/**
 * Peer chat for a room — separate from the AI chat panel.
 *
 * <p>Two-layer sync:
 * <ul>
 *   <li><b>Postgres</b> is the source of truth. Mount fetches the last
 *       200 messages so a fresh peer (or a peer whose snapshot was
 *       cleared) catches up.</li>
 *   <li><b>Y.Array</b> in the shared Y.Doc broadcasts new messages to
 *       all connected peers without a backend roundtrip. The author
 *       writes to BOTH on send; observers (other peers) only see the
 *       Y.Array push and de-dupe by id when the DB fetch later
 *       reconciles.</li>
 * </ul>
 *
 * <p>This double-write design is intentional: it keeps the realtime UX
 * (no perceived send latency) while making history survive snapshot
 * resets, member churn, and Y.Doc corruption.
 */
export function RoomChat({ ydoc, roomId, currentUserId, currentUserName, canSend }: RoomChatProps) {
  const yArray = useMemo(() => ydoc.getArray<BroadcastMessage>(CHAT_BROADCAST_KEY), [ydoc]);
  const [messages, setMessages] = useState<BroadcastMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [attachment, setAttachment] = useState<File | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Merge two message lists by id, keeping the most recent values and
  // sorting chronologically. Used to reconcile the DB history with
  // whatever the Y.Array already has from concurrent peer pushes.
  function merge(a: BroadcastMessage[], b: BroadcastMessage[]): BroadcastMessage[] {
    const byId = new Map<string, BroadcastMessage>();
    for (const m of a) byId.set(m.id, m);
    for (const m of b) byId.set(m.id, m);
    return Array.from(byId.values()).sort((x, y) => x.createdAt.localeCompare(y.createdAt));
  }

  // 1. Hydrate from DB on mount (and on roomId change).
  useEffect(() => {
    let cancelled = false;
    setHistoryLoaded(false);
    fetchRoomPeerChatHistory(roomId, MAX_BROADCAST_MESSAGES)
      .then((rows) => {
        if (cancelled) return;
        const fromDb = rows.map(fromApi);
        const fromYArray = yArray.toArray();
        const merged = merge(fromYArray, fromDb);
        setMessages(merged);
        // Replace the Y.Array contents with the merged list so peers
        // who arrive later (or whose snapshot lagged) get the same
        // view. Wrap in a transaction so it's one CRDT op.
        ydoc.transact(() => {
          if (yArray.length > 0) yArray.delete(0, yArray.length);
          if (merged.length > 0) yArray.push(merged);
        });
      })
      .catch(() => {
        // Soft-fail: if history fetch errors, fall back to whatever
        // the Y.Array has. The user can still chat.
        if (cancelled) return;
        setMessages(yArray.toArray());
      })
      .finally(() => {
        if (!cancelled) setHistoryLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [roomId, yArray, ydoc]);

  // 2. Subscribe to Y.Array updates for live broadcasts.
  useEffect(() => {
    const sync = () => setMessages(yArray.toArray());
    yArray.observe(sync);
    return () => yArray.unobserve(sync);
  }, [yArray]);

  // Auto-scroll to bottom on new messages.
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages.length]);

  // Drop expired voice messages from the live state. We rely on the
  // server's scheduled job for the authoritative purge, but a local
  // tick prevents users from seeing a row whose audio bytes are about
  // to 404 server-side. Runs every minute — cheap on a list capped
  // at MAX_BROADCAST_MESSAGES.
  useEffect(() => {
    const interval = window.setInterval(() => {
      const now = Date.now();
      setMessages((current) => {
        const kept = current.filter((m) => !m.expiresAt || new Date(m.expiresAt).getTime() > now);
        return kept.length === current.length ? current : kept;
      });
    }, 60_000);
    return () => window.clearInterval(interval);
  }, []);

  function pushToYArray(msg: BroadcastMessage) {
    ydoc.transact(() => {
      if (yArray.length >= MAX_BROADCAST_MESSAGES) {
        yArray.delete(0, yArray.length - MAX_BROADCAST_MESSAGES + 1);
      }
      yArray.push([msg]);
    });
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (!currentUserId || !currentUserName) return;
    const trimmed = draft.trim();
    const file = attachment;
    if (!trimmed && !file) return;
    setSending(true);
    setError(null);
    try {
      let saved: ApiPeerChatMessage;
      if (file) {
        saved = await sendRoomPeerChatFile(roomId, file, trimmed);
      } else {
        saved = await sendRoomPeerChatMessage(roomId, trimmed);
      }
      pushToYArray(fromApi(saved));
      setDraft("");
      setAttachment(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
    } catch (ex) {
      const message = ex instanceof Error ? ex.message : "Failed to send message";
      setError(message);
    } finally {
      setSending(false);
    }
  }

  /**
   * Persist a recorded voice message: ship the blob as a regular
   * attachment but carry the client-measured duration so the player
   * doesn't have to wait on loadedmetadata to render the time. The
   * backend stamps expiresAt server-side from APP_VOICE_TTL_HOURS.
   */
  async function onSendVoice(blob: Blob, durationMs: number) {
    if (!currentUserId || !currentUserName) return;
    const extension = blob.type.includes("mp4")
      ? "m4a"
      : blob.type.includes("ogg")
        ? "ogg"
        : "webm";
    const file = new File([blob], `voice-${Date.now()}.${extension}`, { type: blob.type || "audio/webm" });
    setError(null);
    const saved = await sendRoomPeerChatFile(roomId, file, "", durationMs);
    pushToYArray(fromApi(saved));
  }

  function onFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    if (!file) {
      setAttachment(null);
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setError("File exceeds 5 MB limit");
      event.target.value = "";
      return;
    }
    setError(null);
    setAttachment(file);
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div
        ref={scrollRef}
        className="flex-1 min-h-0 space-y-2 overflow-y-auto rounded-md border border-zinc-800 bg-zinc-950 p-2.5"
      >
        {!historyLoaded && messages.length === 0 ? (
          <p className="flex h-full items-center justify-center px-4 text-center text-xs text-zinc-500">
            <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            Loading messages…
          </p>
        ) : messages.length === 0 ? (
          <p className="flex h-full items-center justify-center px-4 text-center text-xs text-zinc-500">
            No messages yet — say hi to your collaborators.
          </p>
        ) : (
          messages.map((message) => (
            <MessageBubble
              key={message.id}
              message={message}
              isMine={message.userId === currentUserId}
              roomId={roomId}
            />
          ))
        )}
      </div>

      {canSend && (
        <form onSubmit={onSubmit} className="mt-2 flex flex-col gap-1.5">
          {attachment && (
            <div className="flex items-center justify-between gap-2 rounded-md border border-zinc-800 bg-zinc-950 px-2 py-1.5 text-[11px]">
              <span className="inline-flex min-w-0 items-center gap-1.5 text-zinc-300">
                {attachment.type.startsWith("image/") ? (
                  <ImageIcon className="h-3.5 w-3.5 shrink-0 text-cyan" />
                ) : (
                  <FileIcon className="h-3.5 w-3.5 shrink-0 text-cyan" />
                )}
                <span className="truncate">{attachment.name}</span>
                <span className="shrink-0 text-zinc-500">({Math.round(attachment.size / 1024)} KB)</span>
              </span>
              <button
                type="button"
                onClick={() => {
                  setAttachment(null);
                  if (fileInputRef.current) fileInputRef.current.value = "";
                }}
                className="shrink-0 text-zinc-500 transition hover:text-zinc-200"
                aria-label="Remove attachment"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
          {error && (
            <p className="truncate rounded-md border border-rose-900 bg-rose-950/40 px-2 py-1 text-[11px] text-rose-300">
              {error}
            </p>
          )}
          <div className="flex items-center gap-1.5">
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*,application/pdf,text/*,application/json,application/xml,application/zip"
              onChange={onFileChange}
              className="hidden"
              id="peer-chat-file-input"
            />
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={sending}
              className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-zinc-800 text-zinc-400 transition hover:border-zinc-700 hover:text-zinc-100 disabled:opacity-40"
              title="Attach a file (max 5 MB)"
              aria-label="Attach a file"
            >
              <Paperclip className="h-4 w-4" />
            </button>
            {/* Voice recorder lives inline with the input. In idle state
                it's a single mic button and the text input stays usable;
                during recording / preview it expands and takes over the
                row so the user focuses on the audio capture. */}
            <VoiceRecorder onSend={onSendVoice} disabled={sending || !currentUserId} />
            <input
              type="text"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder={attachment ? "Add a caption (optional)…" : "Message the room…"}
              maxLength={4000}
              disabled={!currentUserId || sending}
              className="h-9 flex-1 rounded-md border border-zinc-800 bg-zinc-950 px-3 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-cyan focus:outline-none disabled:opacity-60"
            />
            <Button
              type="submit"
              disabled={(!draft.trim() && !attachment) || !currentUserId || sending}
            >
              {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            </Button>
          </div>
        </form>
      )}

      {!canSend && (
        <p className="mt-2 truncate rounded-md border border-zinc-800 bg-zinc-950 px-2.5 py-1.5 text-[11px] text-zinc-500">
          Read-only view.
        </p>
      )}
    </div>
  );
}

function MessageBubble({
  message,
  isMine,
  roomId,
}: {
  message: BroadcastMessage;
  isMine: boolean;
  roomId: string;
}) {
  const time = new Date(message.createdAt).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
  const isImage = message.fileType?.startsWith("image/") ?? false;
  const isAudio = message.fileType?.startsWith("audio/") ?? false;
  const hasFile = !!message.fileName;
  return (
    <div className={cn("flex flex-col", isMine ? "items-end" : "items-start")}>
      <div className="mb-0.5 inline-flex items-center gap-1.5 px-1 text-[10px] text-zinc-500">
        <span
          className="h-2 w-2 shrink-0 rounded-full"
          style={{ backgroundColor: message.color }}
          aria-hidden
        />
        <span className="font-medium" style={{ color: message.color }}>
          {isMine ? "you" : message.userName}
        </span>
        <span>·</span>
        <span>{time}</span>
      </div>
      <div
        className={cn(
          "max-w-[85%] overflow-hidden rounded-md text-[13px] leading-5",
          isMine ? "bg-signature/20 text-zinc-100" : "bg-zinc-900 text-zinc-200",
          // Audio bubbles are wider than text bubbles to fit the
          // progress bar + duration + countdown chip comfortably.
          isAudio && "min-w-[16rem]",
        )}
      >
        {hasFile && isAudio && (
          <VoicePlayer
            fileUrl={peerChatFileUrl(roomId, message.id)}
            durationMs={message.audioDurationMs}
            expiresAt={message.expiresAt}
            isMine={isMine}
          />
        )}
        {hasFile && !isAudio && (
          <AttachmentPreview
            roomId={roomId}
            messageId={message.id}
            fileName={message.fileName!}
            fileType={message.fileType}
            fileSize={message.fileSize}
            isImage={isImage}
          />
        )}
        {message.content && (
          <p className="whitespace-pre-wrap break-words px-2.5 py-1.5">{message.content}</p>
        )}
      </div>
    </div>
  );
}

function AttachmentPreview({
  roomId,
  messageId,
  fileName,
  fileType,
  fileSize,
  isImage,
}: {
  roomId: string;
  messageId: string;
  fileName: string;
  fileType: string | null;
  fileSize: number | null;
  isImage: boolean;
}) {
  // Auth: the file endpoint requires a Bearer token. Building an <img
  // src=...> doesn't carry headers, so for images we fetch the bytes
  // with auth and convert to a blob URL. For non-image attachments
  // we render a link the user clicks to open in a new tab.
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [imgError, setImgError] = useState<string | null>(null);
  const fileUrl = peerChatFileUrl(roomId, messageId);

  useEffect(() => {
    if (!isImage) return;
    let cancelled = false;
    let createdUrl: string | null = null;
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
        createdUrl = URL.createObjectURL(blob);
        setBlobUrl(createdUrl);
      })
      .catch((ex) => {
        if (cancelled) return;
        setImgError(ex instanceof Error ? ex.message : "Failed to load image");
      });
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [fileUrl, isImage]);

  async function downloadNonImage() {
    // Same auth dance as the image preview, but kicks off a "save as"
    // by setting <a download>. Avoids requiring the user to install
    // browser auth headers.
    try {
      const token = useAuthStore.getState().accessToken;
      const res = await fetch(fileUrl, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      // Revoke a tick later so Chrome has time to start the download.
      setTimeout(() => URL.revokeObjectURL(url), 1_000);
    } catch (ex) {
      // No UI surface for the error here; this is a best-effort UX.
      // The user can still right-click the link if it ever became one.
      // eslint-disable-next-line no-console
      console.warn("Attachment download failed", ex);
    }
  }

  if (isImage) {
    if (imgError) {
      return (
        <div className="px-2.5 py-1.5 text-[11px] text-rose-400">
          Could not load image: {imgError}
        </div>
      );
    }
    if (!blobUrl) {
      return (
        <div className="flex items-center gap-1.5 px-2.5 py-1.5 text-[11px] text-zinc-500">
          <Loader2 className="h-3 w-3 animate-spin" />
          Loading image…
        </div>
      );
    }
    return (
      <button
        type="button"
        onClick={() => window.open(blobUrl, "_blank", "noopener,noreferrer")}
        className="block w-full"
        title={`${fileName} — click to view full size`}
      >
        <img
          src={blobUrl}
          alt={fileName}
          className="max-h-48 w-full object-cover transition hover:opacity-90"
        />
      </button>
    );
  }

  // Non-image attachment: clickable chip that downloads.
  return (
    <button
      type="button"
      onClick={downloadNonImage}
      className="flex w-full items-center gap-2 border-b border-black/20 px-2.5 py-2 text-left transition hover:bg-black/20"
    >
      <FileIcon className="h-4 w-4 shrink-0 text-cyan" />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[12px] font-medium">{fileName}</span>
        <span className="block text-[10px] text-zinc-500">
          {fileType ?? "file"}
          {fileSize ? ` · ${Math.round(fileSize / 1024)} KB` : ""}
        </span>
      </span>
    </button>
  );
}
