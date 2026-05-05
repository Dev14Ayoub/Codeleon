import { AxiosError } from "axios";
import { useCallback, useEffect, useState } from "react";
import {
  createRoomFile,
  deleteRoomFile,
  listRoomFiles,
  renameRoomFile,
  type RoomFile,
} from "@/lib/api";

interface UseRoomFilesResult {
  files: RoomFile[];
  loading: boolean;
  error: string | null;
  create: (path: string) => Promise<RoomFile | null>;
  rename: (fileId: string, newPath: string) => Promise<RoomFile | null>;
  remove: (fileId: string) => Promise<boolean>;
  refresh: () => Promise<void>;
}

function sortByPath(arr: RoomFile[]): RoomFile[] {
  return [...arr].sort((a, b) => a.path.localeCompare(b.path));
}

function asMessage(error: unknown): string {
  if (error instanceof AxiosError) {
    const data = error.response?.data as { message?: string } | undefined;
    return data?.message ?? error.message;
  }
  return error instanceof Error ? error.message : String(error);
}

export function useRoomFiles(roomId: string | undefined): UseRoomFilesResult {
  const [files, setFiles] = useState<RoomFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!roomId) return;
    setLoading(true);
    setError(null);
    try {
      const list = await listRoomFiles(roomId);
      setFiles(sortByPath(list));
    } catch (ex) {
      setError(asMessage(ex));
    } finally {
      setLoading(false);
    }
  }, [roomId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const create = useCallback(
    async (path: string): Promise<RoomFile | null> => {
      if (!roomId) return null;
      setError(null);
      try {
        const created = await createRoomFile(roomId, path);
        setFiles((prev) => sortByPath([...prev, created]));
        return created;
      } catch (ex) {
        setError(asMessage(ex));
        return null;
      }
    },
    [roomId],
  );

  const rename = useCallback(
    async (fileId: string, newPath: string): Promise<RoomFile | null> => {
      if (!roomId) return null;
      setError(null);
      try {
        const updated = await renameRoomFile(roomId, fileId, newPath);
        setFiles((prev) =>
          sortByPath(prev.map((f) => (f.id === fileId ? updated : f))),
        );
        return updated;
      } catch (ex) {
        setError(asMessage(ex));
        return null;
      }
    },
    [roomId],
  );

  const remove = useCallback(
    async (fileId: string): Promise<boolean> => {
      if (!roomId) return false;
      setError(null);
      try {
        await deleteRoomFile(roomId, fileId);
        setFiles((prev) => prev.filter((f) => f.id !== fileId));
        return true;
      } catch (ex) {
        setError(asMessage(ex));
        return false;
      }
    },
    [roomId],
  );

  return { files, loading, error, create, rename, remove, refresh };
}
