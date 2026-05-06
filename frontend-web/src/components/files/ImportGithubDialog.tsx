import * as Dialog from "@radix-ui/react-dialog";
import { AxiosError } from "axios";
import { Github, Loader2, X } from "lucide-react";
import { FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  importGithub,
  type GithubImportResponse,
} from "@/lib/api";
import { cn } from "@/lib/utils";

interface ImportGithubDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  roomId: string;
  /**
   * Called once the backend returns the imported files. The parent uses
   * the returned file list to seed each Y.Text(path) with the file
   * content; we don't touch Yjs from inside the dialog.
   */
  onImported: (response: GithubImportResponse) => Promise<void> | void;
}

export function ImportGithubDialog({
  open,
  onOpenChange,
  roomId,
  onImported,
}: ImportGithubDialogProps) {
  const [repoUrl, setRepoUrl] = useState("");
  const [branch, setBranch] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [report, setReport] = useState<GithubImportResponse | null>(null);

  const reset = () => {
    setRepoUrl("");
    setBranch("");
    setError(null);
    setReport(null);
    setBusy(false);
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!repoUrl.trim() || busy) return;
    setBusy(true);
    setError(null);
    setReport(null);

    try {
      const response = await importGithub(roomId, {
        repoUrl: repoUrl.trim(),
        branch: branch.trim() || undefined,
      });
      setReport(response);
      await onImported(response);
    } catch (ex) {
      if (ex instanceof AxiosError) {
        const msg = (ex.response?.data as { message?: string } | undefined)?.message;
        setError(msg ?? ex.message);
      } else {
        setError(ex instanceof Error ? ex.message : "Import failed");
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[92vw] max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-lg border border-zinc-800 bg-surface p-6 shadow-glow">
          <div className="flex items-start justify-between gap-4">
            <div>
              <Dialog.Title className="flex items-center gap-2 text-lg font-semibold text-zinc-50">
                <Github className="h-5 w-5 text-cyan" />
                Import from GitHub
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-sm text-zinc-400">
                Paste a public repository URL. Up to 200 text files smaller than 100 KB will be added to this room.
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button
                aria-label="Close"
                className="rounded p-1 text-zinc-500 hover:bg-surfaceRaised hover:text-zinc-200"
              >
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>

          <form onSubmit={handleSubmit} className="mt-5 space-y-4">
            <Field label="Repository URL">
              <input
                value={repoUrl}
                onChange={(e) => setRepoUrl(e.target.value)}
                placeholder="https://github.com/owner/repo  (or  owner/repo)"
                disabled={busy}
                className={fieldClasses}
                autoFocus
              />
            </Field>
            <Field label="Branch (optional)">
              <input
                value={branch}
                onChange={(e) => setBranch(e.target.value)}
                placeholder="main (defaults to main, falls back to master)"
                disabled={busy}
                className={fieldClasses}
              />
            </Field>

            {error && (
              <p className="rounded border border-rose-900 bg-rose-950/40 px-3 py-2 text-xs text-rose-300">
                {error}
              </p>
            )}

            {report && (
              <div className="space-y-2 rounded border border-zinc-800 bg-zinc-950 px-3 py-2 text-xs">
                <p className="text-emerald-400">
                  Imported {report.imported.length} file
                  {report.imported.length === 1 ? "" : "s"} from{" "}
                  <span className="font-mono text-emerald-300">
                    {report.owner}/{report.repo}@{report.branchUsed}
                  </span>
                  {report.truncated && " (list truncated at 200 files)"}.
                </p>
                {report.skipped.length > 0 && (
                  <details className="text-zinc-400">
                    <summary className="cursor-pointer text-zinc-500 hover:text-zinc-300">
                      {report.skipped.length} skipped
                    </summary>
                    <ul className="mt-1 max-h-40 space-y-0.5 overflow-y-auto pl-3 font-mono text-[10px]">
                      {report.skipped.slice(0, 50).map((s, i) => (
                        <li key={i}>
                          <span className="text-zinc-300">{s.path}</span>
                          <span className="text-zinc-600"> — {s.reason}</span>
                        </li>
                      ))}
                      {report.skipped.length > 50 && (
                        <li className="text-zinc-600">
                          + {report.skipped.length - 50} more...
                        </li>
                      )}
                    </ul>
                  </details>
                )}
              </div>
            )}

            <div className="flex items-center justify-end gap-2 pt-2">
              <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
                Close
              </Button>
              <Button type="submit" disabled={busy || !repoUrl.trim()}>
                {busy ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Github className="h-4 w-4" />
                )}
                {busy ? "Importing..." : report ? "Import again" : "Import"}
              </Button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

const fieldClasses = cn(
  "w-full rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2 font-mono text-[13px] text-zinc-100",
  "placeholder:text-zinc-600 focus:border-cyan focus:outline-none disabled:opacity-60",
);

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs uppercase tracking-wide text-zinc-500">
        {label}
      </span>
      {children}
    </label>
  );
}
