import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completionKeymap,
} from "@codemirror/autocomplete";
import {
  defaultKeymap,
  history,
  historyKeymap,
  indentWithTab,
} from "@codemirror/commands";
import {
  bracketMatching,
  codeFolding,
  defaultHighlightStyle,
  foldGutter,
  foldKeymap,
  indentOnInput,
  syntaxHighlighting,
} from "@codemirror/language";
import { lintGutter, lintKeymap } from "@codemirror/lint";
import {
  closeSearchPanel,
  highlightSelectionMatches,
  openSearchPanel,
  search,
  searchKeymap,
} from "@codemirror/search";
import { Compartment, EditorState, type Extension } from "@codemirror/state";
import { oneDarkHighlightStyle } from "@codemirror/theme-one-dark";
import {
  crosshairCursor,
  dropCursor,
  drawSelection,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
} from "@codemirror/view";
import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import * as Y from "yjs";
import { yCollab, yUndoManagerKeymap } from "y-codemirror.next";
import { codeMirrorLanguageFromPath } from "@/lib/files/file-language";

export interface CodeMirrorEditorHandle {
  getValue: () => string;
  focus: () => void;
  openFind: () => void;
  openReplace: () => void;
  formatDocument: () => void;
  /**
   * Moves the caret to the given 1-indexed line and scrolls it into the
   * middle of the viewport. Called when the user clicks a citation in
   * the chat context drawer to jump to the cited code.
   */
  gotoLine: (line: number) => void;
}

interface CodeMirrorEditorProps {
  activePath: string | null;
  ydoc: Y.Doc;
  awareness: unknown;
  canEdit: boolean;
  onReadyChange?: (ready: boolean) => void;
}

const editableCompartment = new Compartment();
const languageCompartment = new Compartment();

export const CodeMirrorEditor = forwardRef<
  CodeMirrorEditorHandle,
  CodeMirrorEditorProps
>(function CodeMirrorEditor(
  { activePath, ydoc, awareness, canEdit, onReadyChange },
  ref,
) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);

  useImperativeHandle(
    ref,
    () => ({
      getValue: () => viewRef.current?.state.doc.toString() ?? "",
      focus: () => viewRef.current?.focus(),
      openFind: () => {
        const view = viewRef.current;
        if (!view) return;
        view.focus();
        openSearchPanel(view);
      },
      openReplace: () => {
        const view = viewRef.current;
        if (!view) return;
        view.focus();
        openSearchPanel(view);
      },
      formatDocument: () => {
        viewRef.current?.focus();
      },
      gotoLine: (line: number) => {
        const view = viewRef.current;
        if (!view || line <= 0) return;
        const target = Math.min(Math.max(1, Math.floor(line)), view.state.doc.lines);
        const pos = view.state.doc.line(target).from;
        view.dispatch({
          selection: { anchor: pos, head: pos },
          // EditorView.scrollIntoView centres the position; useful when
          // jumping from a citation since the user has lost their place.
          effects: EditorView.scrollIntoView(pos, { y: "center" }),
        });
        view.focus();
      },
    }),
    [],
  );

  useEffect(() => {
    const parent = containerRef.current;
    if (!parent) return;

    viewRef.current?.destroy();
    viewRef.current = null;
    onReadyChange?.(false);

    const yText = activePath ? ydoc.getText(activePath) : null;
    const undoManager = yText ? new Y.UndoManager(yText) : null;
    const editable = canEdit && Boolean(activePath) && Boolean(awareness);
    const extensions: Extension[] = [
      ...baseEditorExtensions,
      languageCompartment.of(
        activePath ? codeMirrorLanguageFromPath(activePath) : [],
      ),
      editableCompartment.of(editableExtensions(editable)),
    ];

    if (yText) {
      extensions.push(
        yCollab(yText, awareness, {
          undoManager: undoManager ?? false,
        }),
      );
    }

    const state = EditorState.create({
      doc: yText?.toString() ?? "",
      extensions,
    });
    const view = new EditorView({ state, parent });
    viewRef.current = view;
    onReadyChange?.(true);

    return () => {
      closeSearchPanel(view);
      view.destroy();
      undoManager?.destroy();
      if (viewRef.current === view) {
        viewRef.current = null;
        onReadyChange?.(false);
      }
    };
  }, [activePath, awareness, canEdit, onReadyChange, ydoc]);

  return (
    <div className="relative h-full min-h-0">
      <div ref={containerRef} className="h-full min-h-0" />
      {!activePath && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center px-6 text-center text-sm text-zinc-600">
          Select a file from the explorer to start editing.
        </div>
      )}
    </div>
  );
});

function editableExtensions(editable: boolean): Extension[] {
  return [EditorView.editable.of(editable), EditorState.readOnly.of(!editable)];
}

const codeleonCodeMirrorTheme = EditorView.theme(
  {
    "&": {
      height: "100%",
      backgroundColor: "#09090b",
      color: "#fafafa",
      fontSize: "14px",
    },
    ".cm-scroller": {
      height: "100%",
      overflow: "auto",
      fontFamily: "Geist Mono, ui-monospace, SFMono-Regular, monospace",
      lineHeight: "1.65",
    },
    ".cm-content": {
      minHeight: "100%",
      padding: "18px 0",
      caretColor: "#06b6d4",
    },
    ".cm-line": {
      padding: "0 18px",
    },
    ".cm-gutters": {
      backgroundColor: "#09090b",
      borderRight: "1px solid #27272a",
      color: "#52525b",
    },
    ".cm-activeLine": {
      backgroundColor: "#18181b",
    },
    ".cm-activeLineGutter": {
      backgroundColor: "#18181b",
      color: "#a1a1aa",
    },
    ".cm-cursor": {
      borderLeftColor: "#06b6d4",
    },
    "&.cm-focused": {
      outline: "none",
    },
    "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection":
      {
        backgroundColor: "#6366f166",
      },
    ".cm-panels": {
      backgroundColor: "#18181b",
      borderColor: "#27272a",
      color: "#e4e4e7",
    },
    ".cm-search button, .cm-search input": {
      border: "1px solid #3f3f46",
      borderRadius: "4px",
      backgroundColor: "#09090b",
      color: "#f4f4f5",
      fontSize: "12px",
    },
    ".cm-tooltip": {
      border: "1px solid #3f3f46",
      backgroundColor: "#18181b",
      color: "#f4f4f5",
    },
    ".cm-tooltip-autocomplete ul li[aria-selected]": {
      backgroundColor: "#164e63",
      color: "#f8fafc",
    },
    ".cm-foldPlaceholder": {
      border: "1px solid #3f3f46",
      backgroundColor: "#18181b",
      color: "#a1a1aa",
    },
  },
  { dark: true },
);

const baseEditorExtensions: Extension[] = [
  lineNumbers(),
  highlightActiveLineGutter(),
  highlightSpecialChars(),
  history(),
  foldGutter(),
  codeFolding(),
  drawSelection(),
  dropCursor(),
  EditorState.allowMultipleSelections.of(true),
  indentOnInput(),
  syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
  syntaxHighlighting(oneDarkHighlightStyle),
  bracketMatching(),
  closeBrackets(),
  autocompletion(),
  rectangularSelection(),
  crosshairCursor(),
  highlightActiveLine(),
  search({ top: true }),
  highlightSelectionMatches(),
  lintGutter(),
  EditorView.lineWrapping,
  EditorView.contentAttributes.of({ spellcheck: "false" }),
  codeleonCodeMirrorTheme,
  keymap.of([
    ...yUndoManagerKeymap,
    ...closeBracketsKeymap,
    ...defaultKeymap,
    ...searchKeymap,
    ...historyKeymap,
    ...foldKeymap,
    ...completionKeymap,
    ...lintKeymap,
    indentWithTab,
  ]),
];
