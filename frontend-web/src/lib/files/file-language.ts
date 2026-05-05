/**
 * Maps a file path's extension to a Monaco language ID. Mirrors the
 * backend's RoomFileService.detectLanguage so the displayed syntax
 * highlighting matches the language stored on the RoomFile entity.
 *
 * Returns "plaintext" for unknown or extension-less paths.
 */
export function languageFromPath(path: string): string {
  const dot = path.lastIndexOf(".");
  if (dot < 0 || dot === path.length - 1) return "plaintext";
  const ext = path.substring(dot + 1).toLowerCase();
  switch (ext) {
    case "java":
      return "java";
    case "py":
      return "python";
    case "js":
    case "mjs":
    case "cjs":
      return "javascript";
    case "ts":
      return "typescript";
    case "jsx":
      // Monaco does not ship a "javascriptreact" mode; "javascript" gives the
      // closest tokenizer (JSX literals are parsed loosely but it works).
      return "javascript";
    case "tsx":
      return "typescript";
    case "html":
    case "htm":
      return "html";
    case "css":
      return "css";
    case "scss":
      return "scss";
    case "json":
      return "json";
    case "yml":
    case "yaml":
      return "yaml";
    case "xml":
      return "xml";
    case "md":
    case "markdown":
      return "markdown";
    case "sh":
    case "bash":
      return "shell";
    case "sql":
      return "sql";
    case "go":
      return "go";
    case "rs":
      return "rust";
    case "rb":
      return "ruby";
    case "php":
      return "php";
    case "cpp":
    case "cxx":
    case "cc":
    case "hpp":
    case "h":
      return "cpp";
    case "c":
      return "c";
    case "cs":
      return "csharp";
    case "kt":
    case "kts":
      return "kotlin";
    case "swift":
      return "swift";
    case "dockerfile":
      return "dockerfile";
    default:
      return "plaintext";
  }
}

/**
 * Pretty name displayed next to a file row when the language is not
 * obvious from the extension. Currently unused but handy for tabs
 * later on.
 */
export function languageDisplayName(language: string): string {
  switch (language) {
    case "javascript":
      return "JavaScript";
    case "typescript":
      return "TypeScript";
    case "csharp":
      return "C#";
    case "cpp":
      return "C++";
    case "shell":
      return "Shell";
    case "html":
      return "HTML";
    case "css":
      return "CSS";
    case "scss":
      return "SCSS";
    case "json":
      return "JSON";
    case "yaml":
      return "YAML";
    case "xml":
      return "XML";
    case "sql":
      return "SQL";
    default:
      return language.charAt(0).toUpperCase() + language.slice(1);
  }
}
