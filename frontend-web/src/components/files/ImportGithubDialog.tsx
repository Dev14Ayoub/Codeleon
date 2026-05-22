import * as Dialog from "@radix-ui/react-dialog";
import { useQuery } from "@tanstack/react-query";
import { AnimatePresence, motion } from "framer-motion";
import { Github, Loader2, Lock, Search, X } from "lucide-react";
import { FormEvent, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  API_BASE_URL,
  fetchGithubRepositories,
  getApiErrorMessage,
  importGithub,
  type GithubRepository,
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
  const [repoSearch, setRepoSearch] = useState("");
  const [branch, setBranch] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [report, setReport] = useState<GithubImportResponse | null>(null);

  const repositoriesQuery = useQuery({
    queryKey: ["github-repositories", roomId],
    queryFn: () => fetchGithubRepositories(roomId),
    enabled: open,
    retry: false,
    staleTime: 60_000,
  });

  const repositories = repositoriesQuery.data ?? [];
  const showRepositoryPicker = repositoriesQuery.isSuccess;
  const filteredRepositories = useMemo(
    () => filterRepositories(repositories, repoSearch),
    [repositories, repoSearch],
  );

  const reset = () => {
    setRepoUrl("");
    setRepoSearch("");
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
      setError(getApiErrorMessage(ex, "Import failed"));
    } finally {
      setBusy(false);
    }
  };

  const shouldShowGithubConnect =
    error != null &&
    /github/i.test(error) &&
    /(authentication|required|private|connect|reconnect)/i.test(error);

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay asChild>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm"
          />
        </Dialog.Overlay>
        <Dialog.Content asChild>
          <motion.div
            initial={{ opacity: 0, y: 18, scale: 0.96 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 18, scale: 0.96 }}
            transition={{ duration: 0.24, ease: [0.16, 1, 0.3, 1] }}
            className="fixed left-1/2 top-1/2 z-50 w-[92vw] max-w-lg -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-lg border border-zinc-800 bg-surface p-6 shadow-glow"
          >
          <span className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-cyan/70 to-transparent" />
          <div className="flex items-start justify-between gap-4">
            <div>
              <Dialog.Title className="flex items-center gap-2 text-lg font-semibold text-zinc-50">
                <Github className="h-5 w-5 text-cyan" />
                Import from GitHub
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-sm text-zinc-400">
                {showRepositoryPicker
                  ? "Choose one of your GitHub repositories, or paste a URL manually. Up to 200 text files smaller than 100 KB will be added to this room."
                  : "Paste a repository URL. Private repositories require a connected GitHub account. Up to 200 text files smaller than 100 KB will be added to this room."}
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
            {repositoriesQuery.isLoading && (
              <div className="flex items-center gap-2 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2 text-xs text-zinc-400">
                <Loader2 className="h-3.5 w-3.5 animate-spin text-cyan" />
                Checking connected GitHub repositories...
              </div>
            )}

            {showRepositoryPicker && (
              <div className="space-y-2">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-xs uppercase tracking-wide text-zinc-500">
                    Your GitHub repositories
                  </span>
                  <span className="text-[11px] text-zinc-600">
                    {repositories.length} found
                  </span>
                </div>
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-600" />
                  <input
                    value={repoSearch}
                    onChange={(e) => setRepoSearch(e.target.value)}
                    placeholder="Search repositories..."
                    disabled={busy}
                    className={cn(fieldClasses, "pl-9 font-sans")}
                  />
                </div>
                <div className="max-h-56 overflow-y-auto rounded-md border border-zinc-800 bg-zinc-950 p-1">
                  {filteredRepositories.length > 0 ? (
                    filteredRepositories.map((repository) => (
                      <RepositoryOption
                        key={repository.fullName}
                        repository={repository}
                        selected={repoUrl === repository.fullName}
                        onSelect={() => {
                          setRepoUrl(repository.fullName);
                          setBranch(repository.defaultBranch ?? "");
                        }}
                      />
                    ))
                  ) : (
                    <p className="px-3 py-6 text-center text-xs text-zinc-500">
                      No repositories match your search.
                    </p>
                  )}
                </div>
              </div>
            )}

            <Field label="Repository URL">
              <input
                value={repoUrl}
                onChange={(e) => setRepoUrl(e.target.value)}
                placeholder="https://github.com/owner/repo  (or  owner/repo)"
                disabled={busy}
                className={fieldClasses}
                autoFocus={!showRepositoryPicker}
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

            <AnimatePresence>
            {error && (
              <motion.div
                initial={{ opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6 }}
                className="space-y-2 rounded border border-rose-900 bg-rose-950/40 px-3 py-2 text-xs text-rose-300"
              >
                <p>{error}</p>
                {shouldShowGithubConnect && (
                  <Button asChild type="button" variant="secondary">
                    <a href={`${API_BASE_URL}/oauth2/authorization/github`}>
                      <Github className="h-4 w-4" />
                      Connect GitHub
                    </a>
                  </Button>
                )}
              </motion.div>
            )}
            </AnimatePresence>

            <AnimatePresence>
            {report && (
              <motion.div
                initial={{ opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6 }}
                className="space-y-2 rounded border border-zinc-800 bg-zinc-950 px-3 py-2 text-xs"
              >
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
              </motion.div>
            )}
            </AnimatePresence>

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
          </motion.div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

const fieldClasses = cn(
  "w-full rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2 font-mono text-[13px] text-zinc-100",
  "placeholder:text-zinc-600 focus:border-cyan focus:outline-none disabled:opacity-60",
);

function RepositoryOption({
  onSelect,
  repository,
  selected,
}: {
  onSelect: () => void;
  repository: GithubRepository;
  selected: boolean;
}) {
  const updatedAt = repository.updatedAt
    ? new Date(repository.updatedAt).toLocaleDateString(undefined, { month: "short", day: "numeric" })
    : null;

  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        "group flex w-full items-start justify-between gap-3 rounded px-3 py-2 text-left transition",
        selected ? "bg-cyan/10 text-zinc-50" : "text-zinc-300 hover:bg-surfaceRaised hover:text-zinc-50",
      )}
    >
      <span className="min-w-0">
        <span className="flex items-center gap-2">
          <span className="truncate font-mono text-xs">{repository.fullName}</span>
          {repository.privateRepo && <Lock className="h-3 w-3 shrink-0 text-zinc-500" />}
        </span>
        <span className="mt-1 block truncate text-xs text-zinc-600">
          {repository.description ?? "No description"}
        </span>
      </span>
      <span className="shrink-0 text-right text-[10px] text-zinc-600">
        {repository.defaultBranch && <span className="block font-mono">{repository.defaultBranch}</span>}
        {updatedAt && <span className="block">Updated {updatedAt}</span>}
      </span>
    </button>
  );
}

function filterRepositories(repositories: GithubRepository[], query: string) {
  const trimmed = query.trim().toLowerCase();
  if (!trimmed) {
    return repositories;
  }

  return repositories.filter((repository) =>
    [
      repository.fullName,
      repository.owner,
      repository.name,
      repository.description,
      repository.defaultBranch,
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase()
      .includes(trimmed),
  );
}

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
