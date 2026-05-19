import { cpp } from "@codemirror/lang-cpp";
import { css } from "@codemirror/lang-css";
import { go } from "@codemirror/lang-go";
import { html } from "@codemirror/lang-html";
import { java } from "@codemirror/lang-java";
import { javascript } from "@codemirror/lang-javascript";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { php } from "@codemirror/lang-php";
import { python } from "@codemirror/lang-python";
import { rust } from "@codemirror/lang-rust";
import { sass } from "@codemirror/lang-sass";
import { sql } from "@codemirror/lang-sql";
import { vue } from "@codemirror/lang-vue";
import { xml } from "@codemirror/lang-xml";
import { yaml } from "@codemirror/lang-yaml";
import { StreamLanguage } from "@codemirror/language";
import type { Extension } from "@codemirror/state";
import { c, csharp, kotlin } from "@codemirror/legacy-modes/mode/clike";
import { dockerFile } from "@codemirror/legacy-modes/mode/dockerfile";
import { properties } from "@codemirror/legacy-modes/mode/properties";
import { ruby } from "@codemirror/legacy-modes/mode/ruby";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { swift } from "@codemirror/legacy-modes/mode/swift";

export type CodeleonLanguage =
  | "plaintext"
  | "java"
  | "python"
  | "javascript"
  | "typescript"
  | "jsx"
  | "tsx"
  | "html"
  | "css"
  | "scss"
  | "json"
  | "yaml"
  | "xml"
  | "markdown"
  | "shell"
  | "sql"
  | "go"
  | "rust"
  | "ruby"
  | "php"
  | "cpp"
  | "c"
  | "csharp"
  | "kotlin"
  | "swift"
  | "dockerfile"
  | "env"
  | "vue";

/**
 * Mirrors the backend's RoomFileService.detectLanguage while returning
 * editor-neutral IDs that CodeMirror can map to language extensions.
 */
export function languageFromPath(path: string): CodeleonLanguage {
  const basename = path.split(/[\\/]/).pop()?.toLowerCase() ?? path.toLowerCase();
  if (basename === "dockerfile" || basename.endsWith(".dockerfile")) return "dockerfile";
  if (basename === ".env" || basename.startsWith(".env.")) return "env";

  const dot = basename.lastIndexOf(".");
  if (dot < 0 || dot === basename.length - 1) return "plaintext";
  const ext = basename.substring(dot + 1);
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
      return "jsx";
    case "tsx":
      return "tsx";
    case "html":
    case "htm":
      return "html";
    case "css":
      return "css";
    case "scss":
      return "scss";
    case "sass":
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
    case "zsh":
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
    case "hxx":
      return "cpp";
    case "c":
    case "h":
      return "c";
    case "cs":
      return "csharp";
    case "kt":
    case "kts":
      return "kotlin";
    case "swift":
      return "swift";
    case "vue":
      return "vue";
    default:
      return "plaintext";
  }
}

export function codeMirrorLanguageFromPath(path: string): Extension {
  return codeMirrorLanguage(languageFromPath(path));
}

export function codeMirrorLanguage(language: CodeleonLanguage): Extension {
  switch (language) {
    case "java":
      return java();
    case "python":
      return python();
    case "javascript":
      return javascript();
    case "typescript":
      return javascript({ typescript: true });
    case "jsx":
      return javascript({ jsx: true });
    case "tsx":
      return javascript({ jsx: true, typescript: true });
    case "html":
      return html();
    case "css":
      return css();
    case "scss":
      return sass();
    case "json":
      return json();
    case "yaml":
      return yaml();
    case "xml":
      return xml();
    case "markdown":
      return markdown();
    case "shell":
      return stream(shell);
    case "sql":
      return sql();
    case "go":
      return go();
    case "rust":
      return rust();
    case "ruby":
      return stream(ruby);
    case "php":
      return php();
    case "cpp":
      return cpp();
    case "c":
      return stream(c);
    case "csharp":
      return stream(csharp);
    case "kotlin":
      return stream(kotlin);
    case "swift":
      return stream(swift);
    case "dockerfile":
      return stream(dockerFile);
    case "env":
      return stream(properties);
    case "vue":
      return vue();
    case "plaintext":
    default:
      return [];
  }
}

function stream(parser: Parameters<typeof StreamLanguage.define>[0]): Extension {
  return StreamLanguage.define(parser);
}

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
    case "env":
      return ".env";
    default:
      return language.charAt(0).toUpperCase() + language.slice(1);
  }
}
