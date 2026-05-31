# Screenshots — capture guide

> The main `README.md` references PNG screenshots from this folder.
> Take them in a 1920 × 1080 viewport for a consistent look, save as
> lossless PNG, and commit alongside the docs. Each screenshot should
> showcase a specific page or interaction.

## Expected files

| Filename | Page / state | What it should show |
|---|---|---|
| `landing.png` | Landing page (`/`) | Animated mesh gradient backdrop visible, hero copy, CTAs to Sign in + Create account. Capture in dark mode with the orbs in mid-drift. |
| `login.png` | Login page (`/login`) | Two-column `AuthShell`: value props on the left, form card on the right with both **Sign in with GitHub** button and email/password fields visible. |
| `dashboard.png` | Dashboard (`/dashboard`) | Sidebar left, stat tiles top, project cards grid, activity feed right, create + join cards. Use a populated account with at least 3 projects and 5 activity items. |
| `room.png` | Room workspace (`/rooms/{id}`) | Top bar with **Live** indicator, file explorer left, editor with code, AI panel right, output strip at the bottom (collapsed), status bar at the very bottom. Have a Python file open. |
| `chat.png` | AI chat — RAG mode | AI panel after sending a question, showing the streamed answer in a bubble + the **Context** drawer expanded with retrieved file chunks below the question. Agent toggle should be **off**. |
| `agent.png` | AI chat — agent mode | Same panel with **agent** toggle **on**, tool call cards visible (`list_files`, `read_file`), and a **propose_patch** card with the **Apply** / **Reject** buttons rendered with a diff. |
| `output.png` | Output panel expanded | After running a Python file: output strip expanded, exit code shown in green, stdout/stderr column populated. Optionally show the project mode with the PROJECT COMMAND column. |
| `admin.png` | Admin dashboard (`/admin`) | Visible only when logged as `ADMIN`. Show the users tab with at least 3 users and the role dropdown open, OR the AI metrics tab with the latency histogram and recent queries. |

## How to capture

1. Make sure the dev stack is running (`.\scripts\start.ps1` on
   Windows, or the manual sequence in the top-level README).
2. Open the browser in a 1920 × 1080 window — use the DevTools device
   toolbar to lock the viewport if needed.
3. Capture with the OS screenshot tool (Snipping Tool on Windows,
   `Cmd+Shift+4` on macOS). Crop to the browser content area, NOT the
   browser chrome.
4. Save as PNG at 100 % quality (no JPEG).
5. Commit the PNG into this folder with the exact filename from the
   table above.

## Mobile screenshots (optional but recommended)

For the soutenance, capture the Redmi (Android Chrome) layouts too:

| Filename | View |
|---|---|
| `mobile-dashboard.png` | Dashboard with the hamburger menu and the mobile-optimised project grid |
| `mobile-room.png` | Room with the file explorer drawer open |
| `mobile-chat.png` | AI chat panel as a full-screen modal (the toggle is documented in `RoomPage.tsx`) |

## Branding

If you want a small logo on the screenshots themselves (for the slide
deck), use the SVG in `frontend-web/public/favicon.svg` rendered at
64 px. Don't overlay text — let the screenshots speak.
</content>
</invoke>