# CodeMirror 6 Migration Plan

This document is a handoff plan for replacing Monaco with CodeMirror 6 in
Codeleon while preserving all existing room/editor functionality.

## Current State As Of 2026-05-19

The core implementation pass is done. The remaining work is mostly manual room
QA in a real authenticated workspace.

### Completed So Far

- Removed Monaco packages from `frontend-web/package.json`:
  - `@monaco-editor/react`
  - `monaco-editor`
  - `y-monaco`
- Added CodeMirror 6 packages and `y-codemirror.next`.
- Ran `npm install` in `frontend-web`, updating `frontend-web/package-lock.json`.
- Added `frontend-web/src/components/editor/CodeMirrorEditor.tsx`.
- Moved CodeMirror-specific setup into `CodeMirrorEditor`:
  - `EditorView`
  - CodeMirror extension bundle
  - Codeleon dark theme
  - Yjs binding via `y-codemirror.next`
  - active-file binding to `collab.ydoc.getText(activePath)`
  - read-only/editable state
  - focus/getValue/search APIs exposed through a React ref
- Replaced Monaco usage in `RoomPage.tsx`.
  - Removed `Editor`, `MonacoBinding`, Monaco model cache, Monaco theme, and
    Monaco action calls.
  - `runCode` now reads from CodeMirror through `editorRef.current?.getValue()`.
  - AI active-file context still reads from CodeMirror through `getEditorText`.
  - Whole-project AI indexing still reads from `collab.ydoc.getText(path)`.
- Kept the existing backend collaboration architecture unchanged:
  - `useCollabRoom`
  - `Y.Doc`
  - `ydoc.getText(path)`
  - `y-websocket`
  - `/ws/rooms/{roomId}`
  - `/rooms/{roomId}/snapshot`
- Refactored `frontend-web/src/lib/files/file-language.ts` to return
  CodeMirror language extensions/configuration.
- Wired language support for the planned language set using official packages
  where available and legacy modes/fallbacks where appropriate.
- Added the practical CodeMirror feature bundle:
  - line numbers
  - active line/gutter highlight
  - syntax highlighting
  - bracket matching
  - close brackets
  - folding/fold gutter
  - search panel
  - selection match highlighting
  - autocomplete/completion keymap
  - lint gutter UI/keymap
  - multiple selections
  - rectangular selection
  - special character highlighting
  - drop cursor
  - line wrapping
  - Yjs undo/redo keymap
  - read-only mode
- Updated `MenuBar.tsx`:
  - Find opens CodeMirror search.
  - Replace opens CodeMirror search panel.
  - Format Document is a safe no-op for now.
  - About says `CodeMirror 6 + Yjs`.
  - Keyboard shortcuts no longer mention Monaco command palette.
- Added responsive/resizable room sidebars in `RoomPage.tsx`:
  - left sidebar defaults to `272px`, min `192px`, max `448px`
  - right sidebar defaults to `320px`, min `256px`, max `544px`
  - widths persist in `localStorage`
  - drag handles use pointer events
  - keyboard arrow resizing is supported on handles
  - compact screens use overlay sidebars instead of squeezing the editor
- Added file lifecycle plumbing around CodeMirror/Yjs:
  - creating a file refreshes the file list used by whole-project AI indexing
  - renaming a file moves its Yjs text from the old path to the new path
  - deleting a file clears its Yjs text and closes stale tabs
  - local folder and GitHub imports refresh the file list used by indexing
  - the header Run button is disabled when no active file is open
- Updated current product references in:
  - `frontend-web/src/pages/LandingPage.tsx`
  - `frontend-web/src/components/layout/MenuBar.tsx`
  - `README.md`
  - `docs/uml/component-diagram.md`
  - `docs/uml/sequence-realtime-collab.md`
  - `docs/NEXT-SESSION.md`
  - current-planning parts of `docs/ROADMAP.md`
- Verified:
  - `cd D:\Codeleon\frontend-web && npm run build` passes.
  - `cd D:\Codeleon\backend && mvn test` passes: 85 tests, 0 failures.
  - 2026-05-19 continuation: frontend `npm.cmd run build` passes.
  - 2026-05-19 continuation: backend `mvn test` passes with JDK 23:
    85 tests, 0 failures.
  - `rg '@monaco-editor|\bmonaco-editor\b|y-monaco|MonacoBinding' frontend-web/src frontend-web/package.json frontend-web/package-lock.json` returns no matches.
  - Vite dev server responded HTTP 200 at `http://127.0.0.1:5173/`.

### Known Verification Limitation

The in-app Browser automation could not be used for a visual screenshot smoke
test because the local Node runtime failed with a permission error while
touching `C:\Users\pc\AppData`. A simple HTTP smoke check succeeded instead.

### Still To Do

Manual product QA in a real running room:

- Start full Codeleon stack.
- Login.
- Open a project/room.
- Open files from `FileExplorer` into tabs.
- Confirm active tab content appears in CodeMirror.
- Type in CodeMirror and confirm Yjs text updates.
- Switch tabs and confirm each file's content is preserved.
- Reload and confirm snapshot content restores.
- Open a second browser/user and confirm live edits.
- Confirm remote cursor/selection awareness if supported by the binding.
- Confirm viewer/read-only users cannot edit.
- Create a new file.
- Rename a file.
- Delete a file.
- Import a local folder.
- Import a GitHub repo.
- Run an active Python file.
- Confirm output panel shows stdout/stderr/errors.
- Ask AI about the active file.
- Trigger a Python error and ask AI about the error.
- Confirm whole-project indexing still reads file text from `collab.ydoc`.
- Test find/replace.
- Test code folding.
- Test autocomplete.
- Confirm lint gutter UI does not crash.
- Drag left and right resize handles.
- Reload and confirm sidebar widths persist.
- Check compact/narrow screen behavior.

Optional cleanup/polish:

- Decide whether to update historical references in `docs/progress.md` and
  `docs/pfe-presentation/index.html`. They still mention Monaco, but many of
  those entries describe past commits/history, so do not blindly rewrite them.
- Consider CodeMirror/language package code-splitting later. Vite warns that
  the main JS chunk is about 1.95 MB after minification.
- Add an automated browser/e2e smoke test after Browser automation is working
  again.

### Useful Diff Checks

Before continuing in a new session, verify the exact diff with:

```powershell
git status --short
git diff -- frontend-web/package.json frontend-web/package-lock.json
```

Expected current state: dependencies and app code should both be migrated, but
manual room QA is not yet complete.

## Goal

Replace Monaco with CodeMirror 6 while keeping the product behavior the same:

- left file explorer
- center editor tabs
- center editor area
- bottom output panel
- right participants sidebar
- right AI chatbox
- Yjs real-time collaboration
- snapshot persistence
- local folder import
- GitHub import
- run active file
- AI active-file context
- AI last-run-error context
- whole-project AI indexing from Yjs text
- read-only behavior for viewers

The user-facing room workspace should still feel like Codeleon. This is an
editor-engine refactor, not a redesign.

## What To Do

### 1. Keep The Existing Layout

Do not redesign the room page.

Keep the current three-column structure:

- left sidebar: `FileExplorer`
- middle: `EditorTabs`, editor, `OutputPanel`
- right sidebar: participants section and `ChatPanel`

Only replace the editor engine inside the middle editor area.

### 1b. Make The Workspace Responsive And Resizable

During the CodeMirror 6 migration, also improve the room workspace layout so
it behaves more like a real IDE.

Goals:

- The editor should adapt cleanly to desktop, laptop, tablet-width, and narrow
  screens.
- The left file sidebar and right AI/participants sidebar should be manually
  resizable.
- The editor should keep the largest possible usable area.
- Resizing should feel like VS Code-style panels: drag a vertical divider to
  change sidebar width.
- Sidebar widths should persist in `localStorage`.

Left sidebar behavior:

- Starts around `17rem`.
- Minimum width: around `12rem` (`192px`).
- Maximum width: around `28rem` (`448px`).
- Resizable by dragging its right edge.
- Persist width under `codeleon.leftSidebarWidth`.

Right sidebar behavior:

- Starts around `20rem`.
- Minimum width: around `16rem` (`256px`).
- Maximum width: around `34rem` (`544px`).
- Resizable by dragging its left edge.
- Persist width under `codeleon.rightSidebarWidth`.

Responsive behavior:

- Wide screens: use three columns: file sidebar, editor, AI sidebar.
- Medium screens: keep sidebars resizable but clamp widths so the editor never
  becomes unusable.
- Small screens: prefer the editor area. Sidebars should collapse/toggle rather
  than squeezing the editor to nothing.
- Existing `View > File Explorer` and `View > AI Assistant` toggles must still
  work.

Implementation guidance:

- Replace the current hardcoded room grid columns in `RoomPage.tsx` with
  state-driven sidebar widths.
- Add draggable resize handles between file explorer/editor and editor/AI
  panel.
- Use pointer events: `onPointerDown`, `pointermove`, `pointerup`, and
  `setPointerCapture`.
- Prevent text selection while dragging.
- Add cursor styles while dragging.
- Make sure CodeMirror fills the available area after resizing.

Do not:

- Redesign the room page.
- Remove existing sidebar toggles.
- Let sidebars shrink the editor below a usable width.
- Break AI chatbox scrolling.
- Break file explorer scrolling.
- Break output panel height.
- Make mobile layout worse just to support desktop resizing.

### 2. Create A CodeMirror Editor Component

Add a dedicated component, for example:

```text
frontend-web/src/components/editor/CodeMirrorEditor.tsx
```

This component should own CodeMirror-specific logic:

- `EditorView`
- CodeMirror extensions
- CodeMirror compartments
- Yjs binding lifecycle
- active language reconfiguration
- read-only/editable reconfiguration
- focus and content APIs

`RoomPage.tsx` should keep product workflow logic and should not be full of
CodeMirror setup details.

### 3. Preserve The Existing Yjs Model

Do not change the backend collaboration system.

Keep:

- `useCollabRoom`
- `Y.Doc`
- `ydoc.getText(path)`
- `y-websocket`
- `/ws/rooms/{roomId}`
- `/rooms/{roomId}/snapshot`

Replace only the binding:

- remove `MonacoBinding`
- use `y-codemirror.next`

Each active file path should still bind to:

```ts
collab.ydoc.getText(activePath)
```

### 4. Replace Monaco Calls In RoomPage

Replace these Monaco concepts:

- `editorRef.current?.getValue()`
- `editor.focus()`
- `editor.getAction(...)`
- Monaco model cache
- Monaco theme definition
- Monaco language IDs

With CodeMirror equivalents:

- `view.state.doc.toString()`
- `view.focus()`
- CodeMirror commands from `@codemirror/search`, `@codemirror/commands`, etc.
- Yjs text as source of truth instead of Monaco text models
- CodeMirror theme extension
- CodeMirror language extensions

### 5. Add CodeMirror Feature Bundle

Enable the practical CodeMirror 6 feature set:

- line numbers
- active line highlight
- active gutter highlight
- syntax highlighting
- bracket matching
- auto-close brackets
- code folding
- fold gutter
- search panel
- replace panel
- regexp search
- selection match highlighting
- autocompletion
- completion keymap
- lint gutter UI
- diagnostics panel/keymap
- multiple selections
- rectangular selection
- special character highlighting
- drop cursor
- line wrapping
- undo/redo history
- Codeleon dark theme
- read-only mode for viewers

Note: CodeMirror provides lint UI, but not deep real linting for every
language by default. Wire the UI and diagnostics infrastructure first. Do not
invent fake language linting.

### 6. Language Support

Refactor:

```text
frontend-web/src/lib/files/file-language.ts
```

The file currently maps extensions to Monaco language IDs. Change it to expose
CodeMirror language extensions/configuration.

Use official CodeMirror packages where practical:

- JavaScript
- TypeScript
- JSX / TSX
- Python
- Java
- HTML
- CSS
- SCSS / Sass
- JSON
- Markdown
- PHP
- C / C++
- Rust
- Go
- SQL
- XML
- YAML
- Vue

Use legacy modes or plaintext fallback for languages without solid official
support:

- shell/bash
- Ruby
- C#
- Kotlin
- Swift
- Dockerfile
- `.env`
- unknown config files

Do not block the migration because one language has no perfect parser.

### 7. Update MenuBar

Update:

```text
frontend-web/src/components/layout/MenuBar.tsx
```

Keep the same menus, but replace Monaco-specific behavior/copy:

- `Edit > Find` opens CodeMirror search.
- `Edit > Replace` opens CodeMirror replace.
- `Edit > Format Document` can remain, but should be a safe no-op or disabled
  unless a formatter exists.
- `Help > About` should say `CodeMirror 6 + Yjs`.
- `Help > Keyboard Shortcuts` should remove `Monaco command palette`.

### 8. Update Marketing/Text References

Search and update visible references:

```powershell
rg "Monaco|monaco|y-monaco" frontend-web/src README.md docs
```

Likely places:

- `LandingPage.tsx`
- `MenuBar.tsx`
- `README.md`
- roadmap/docs if they mention Monaco as current implementation

Do not blindly rewrite historical docs if the text is clearly describing the
past. Only update current product claims.

## What Not To Do

- Do not redesign the room UI.
- Do not change backend APIs.
- Do not change database migrations.
- Do not change the Yjs WebSocket backend.
- Do not replace Yjs with CodeMirror's own collaboration example.
- Do not remove AI chat history.
- Do not remove whole-project indexing before chat.
- Do not remove local or GitHub import.
- Do not remove the output panel.
- Do not implement nested folders during this migration.
- Do not add a new terminal feature during this migration.
- Do not add new runner languages during this migration.
- Do not fake deep linting for every language.
- Do not run `npm audit fix --force` as part of this task.
- Do not revert unrelated dirty files such as PFE presentation changes.

## Acceptance Criteria

The migration is done only when all of these still work:

- app builds with `npm run build`
- room opens without runtime error
- file explorer opens files into tabs
- active tab content appears in CodeMirror
- editing updates Yjs text
- switching tabs preserves each file's content
- creating a file works
- renaming a file works
- deleting a file works
- local folder import works
- GitHub import works
- run button sends current CodeMirror text
- output panel shows stdout/stderr/errors
- AI chat receives active file content
- AI chat receives last run stderr/error
- whole-project indexing reads file text from `collab.ydoc`
- reload restores snapshot content
- two-browser collaboration works
- remote cursor/selection awareness works if supported by the binding
- viewers cannot edit
- find/replace works
- code folding works
- autocomplete opens
- lint gutter UI does not crash

## Suggested Verification Commands

Frontend:

```powershell
cd D:\Codeleon\frontend-web
npm run build
```

Backend regression:

```powershell
cd D:\Codeleon\backend
$env:JAVA_HOME='C:\Users\pc\.jdks\openjdk-23.0.1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test
```

Search for leftover Monaco references:

```powershell
cd D:\Codeleon
rg "Monaco|monaco|y-monaco|@monaco-editor" frontend-web README.md docs
```

## Manual Demo Checklist

Use this before committing:

1. Start Codeleon.
2. Login.
3. Open a project.
4. Open a file.
5. Type in CodeMirror.
6. Reload the page and confirm content remains.
7. Open a second browser/user and confirm live edits.
8. Create a new file.
9. Rename it.
10. Delete it.
11. Import a local folder.
12. Import a GitHub repo.
13. Run a Python file.
14. Ask the AI about the active file.
15. Trigger a Python error and ask AI about the error.
16. Test search/replace.
17. Test folding.
18. Test autocomplete.
19. Test viewer/read-only access.

## Defense Framing

Present this as an architecture improvement, not a library swap.

Suggested explanation:

> I migrated the collaborative editor from Monaco to CodeMirror 6 while
> preserving the Yjs real-time layer, multi-file workspace, sandbox runner,
> and RAG assistant. This demonstrates that Codeleon separates product
> features from the editor engine.

## Files Expected To Change

Likely files:

- `frontend-web/package.json`
- `frontend-web/package-lock.json`
- `frontend-web/src/pages/RoomPage.tsx`
- `frontend-web/src/lib/files/file-language.ts`
- `frontend-web/src/components/layout/MenuBar.tsx`
- new `frontend-web/src/components/editor/CodeMirrorEditor.tsx`
- maybe new editor helper files under `frontend-web/src/components/editor/`
- text/docs references that still claim Monaco is current

Avoid touching unrelated backend files.
