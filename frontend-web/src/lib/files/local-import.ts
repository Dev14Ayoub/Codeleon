/**
 * Helpers for importing a folder of local files into a Codeleon room.
 *
 * The browser's File API exposes `<input type="file" webkitdirectory>`
 * which yields a flat FileList where every File carries a
 * `webkitRelativePath` like "my-project/src/Main.java". We strip the
 * top-level folder name and keep the remainder as the file path that
 * Codeleon will use.
 */

const MAX_FILES = 200;
const MAX_FILE_BYTES = 100 * 1024; // 100 KB
const PATH_PATTERN = /^[A-Za-z0-9._-][A-Za-z0-9._/ -]*$/;

const TEXT_EXTENSIONS = new Set([
  "java",
  "py",
  "js",
  "mjs",
  "cjs",
  "ts",
  "jsx",
  "tsx",
  "html",
  "htm",
  "css",
  "scss",
  "json",
  "yml",
  "yaml",
  "xml",
  "md",
  "markdown",
  "sh",
  "bash",
  "sql",
  "go",
  "rs",
  "rb",
  "php",
  "cpp",
  "cxx",
  "cc",
  "hpp",
  "h",
  "c",
  "cs",
  "kt",
  "kts",
  "swift",
  "dockerfile",
  "txt",
  "log",
  "env",
  "ini",
  "toml",
  "gradle",
  "properties",
  "gitignore",
  "editorconfig",
  "lock",
]);

const SKIP_DIRECTORIES = new Set([
  "node_modules",
  ".git",
  ".idea",
  ".vscode",
  "dist",
  "build",
  "target",
  "out",
  ".next",
  ".nuxt",
  "__pycache__",
  ".pytest_cache",
  "venv",
  ".venv",
  ".mvn",
]);

export interface PreparedFile {
  path: string;
  content: string;
}

export interface ImportFilterReport {
  prepared: PreparedFile[];
  skipped: { path: string; reason: string }[];
  truncated: boolean;
}

/**
 * Reads the picked FileList and returns a list of files we are willing
 * to upload, along with a report on what was skipped and why.
 */
export async function prepareLocalImport(fileList: FileList): Promise<ImportFilterReport> {
  const prepared: PreparedFile[] = [];
  const skipped: { path: string; reason: string }[] = [];
  let truncated = false;

  const all = Array.from(fileList);

  for (const file of all) {
    if (prepared.length >= MAX_FILES) {
      truncated = true;
      break;
    }

    const rawPath = (file as File & { webkitRelativePath: string }).webkitRelativePath || file.name;
    const cleaned = stripTopFolder(rawPath);

    if (!cleaned) continue;

    if (cleaned.length > 255) {
      skipped.push({ path: cleaned, reason: "path too long" });
      continue;
    }

    if (segments(cleaned).some((seg) => SKIP_DIRECTORIES.has(seg))) {
      skipped.push({ path: cleaned, reason: "ignored directory" });
      continue;
    }

    if (!PATH_PATTERN.test(cleaned)) {
      skipped.push({ path: cleaned, reason: "invalid characters in path" });
      continue;
    }

    if (file.size > MAX_FILE_BYTES) {
      skipped.push({ path: cleaned, reason: `larger than ${MAX_FILE_BYTES / 1024} KB` });
      continue;
    }

    if (!isLikelyTextFile(cleaned, file)) {
      skipped.push({ path: cleaned, reason: "binary or non-text" });
      continue;
    }

    try {
      const content = await file.text();
      prepared.push({ path: cleaned, content });
    } catch (ex) {
      skipped.push({
        path: cleaned,
        reason: ex instanceof Error ? ex.message : "read failed",
      });
    }
  }

  return { prepared, skipped, truncated };
}

function stripTopFolder(rawPath: string): string {
  const normalized = rawPath.replace(/\\/g, "/").replace(/^\.?\//, "");
  const idx = normalized.indexOf("/");
  return idx < 0 ? normalized : normalized.slice(idx + 1);
}

function segments(path: string): string[] {
  return path.split("/").filter((s) => s.length > 0);
}

function isLikelyTextFile(path: string, file: File): boolean {
  if (file.type.startsWith("text/")) return true;
  if (file.type === "application/json") return true;
  if (file.type === "application/xml") return true;
  if (file.type === "application/javascript") return true;
  if (file.type === "application/typescript") return true;
  if (file.type.startsWith("image/")) return false;
  if (file.type.startsWith("video/")) return false;
  if (file.type.startsWith("audio/")) return false;

  const dot = path.lastIndexOf(".");
  if (dot >= 0) {
    const ext = path.substring(dot + 1).toLowerCase();
    if (TEXT_EXTENSIONS.has(ext)) return true;
  }
  // Filenames without extension that are conventionally text.
  const base = path.split("/").pop()?.toLowerCase() ?? "";
  if (
    base === "dockerfile" ||
    base === "makefile" ||
    base === "readme" ||
    base === "license" ||
    base.startsWith(".env")
  ) {
    return true;
  }
  return false;
}

export const IMPORT_LIMITS = {
  MAX_FILES,
  MAX_FILE_BYTES,
} as const;
