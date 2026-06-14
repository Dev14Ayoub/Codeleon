import { useEffect, useState } from "react";
import { ImageOff, Loader2 } from "lucide-react";
import { fetchRoomAssetBlobUrl } from "@/lib/api";

/**
 * Read-only preview of a binary asset (image). Fetches the bytes with the
 * bearer token (so it stays behind auth) and shows them via a blob URL, which
 * is revoked on unmount / change. Non-image assets just show a placeholder.
 */
export function ImagePreview({ roomId, path }: { roomId: string; path: string }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    let created: string | null = null;
    setUrl(null);
    setError(false);
    fetchRoomAssetBlobUrl(roomId, path)
      .then((blobUrl) => {
        if (active) {
          created = blobUrl;
          setUrl(blobUrl);
        } else {
          URL.revokeObjectURL(blobUrl);
        }
      })
      .catch(() => {
        if (active) setError(true);
      });
    return () => {
      active = false;
      if (created) URL.revokeObjectURL(created);
    };
  }, [roomId, path]);

  const isImage = /\.(png|jpe?g|gif|webp|avif|svg|bmp|ico|tiff?)$/i.test(path);

  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 overflow-auto bg-zinc-950 p-6">
      {error ? (
        <div className="flex flex-col items-center gap-2 text-zinc-500">
          <ImageOff className="h-6 w-6" />
          <p className="text-sm">Couldn't load preview</p>
        </div>
      ) : !url ? (
        <Loader2 className="h-5 w-5 animate-spin text-zinc-500" />
      ) : isImage ? (
        <img
          src={url}
          alt={path}
          className="max-h-full max-w-full rounded border border-zinc-800 object-contain"
        />
      ) : (
        <div className="flex flex-col items-center gap-2 text-zinc-400">
          <p className="text-sm">Binary file — no preview available</p>
          <a href={url} download className="font-mono text-xs text-cyan hover:underline">
            Download
          </a>
        </div>
      )}
      <p className="font-mono text-[11px] text-zinc-500">{path}</p>
    </div>
  );
}
