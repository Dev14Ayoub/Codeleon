# Codeleon — Roadmap

> Living document. Last refreshed: **2026-05-08** (session day 8).
> 39 commits on `main`, 64 backend tests green, 0 known regressions.

This file is the single source of truth for **what Codeleon is, what is
already shipped, and what is still planned** — both for the PFE
deliverables and for post-PFE feature work the user has explicitly asked
for. Read top to bottom; the sections build on each other.

---

## 1. Project snapshot

Codeleon (Code + caméléon) is a self-hosted collaborative coding
platform with a local RAG AI assistant. Every dependency runs on
the developer's own machine — no cloud LLM, no SaaS storage. Users
sign in (email/password, GitHub, Google), open a workspace, edit
code together with multi-cursor presence, run their code in a
sandboxed Docker container, ask the assistant questions about their
own indexed code, and import existing folders or public GitHub
repositories with one click.

PFE final-year project, Licence Pro Génie Logiciel, Faculté
Polydisciplinaire de Taza, academic year 2025-2026.

---

## 2. What's already built

### 2.1 Foundation
| Area | Commit |
|---|---|
| Monorepo bootstrap | `9ea55b4` |
| Auth (JWT + refresh tokens) | `9ea55b4` |
| Rooms (CRUD, members, invite codes) | `475067a` |
| Room editor page (Monaco) | `c7eac9b` |

### 2.2 Real-time collaboration
| Area | Commit |
|---|---|
| Yjs CRDT + WebSocket relay + multi-cursor | `e00e1ae` |
| Snapshot persistence in Postgres | (within `e00e1ae`) |
| WS readiness fix (single-user case) | `55d3d3b` |

### 2.3 Code execution
| Area | Commit |
|---|---|
| Python sandbox (Docker, `--network=none`, mem/cpu/timeout caps) | `28356e8` |

### 2.4 RAG AI assistant
| Area | Commit |
|---|---|
| Compose profile for Qdrant + Ollama | `e247e8c` |
| Spring HTTP clients (Ollama, Qdrant) | `1012d87` |
| File indexing pipeline (chunk → embed → upsert) | `5291c2f` |
| `/chat` SSE streaming endpoint | `5bac642` |
| ChatPanel UI with context drawer | `2afe043` |
| Token JSON-wrap fix (SSE space-stripping) | (within `2afe043`) |

### 2.5 Multi-file workspace
| Area | Commit |
|---|---|
| Backend V3 migration + multi-file CRUD | `964f75b` |
| File explorer + right-click context menu | `fda2ccc` |
| Editor tabs + per-file Monaco models | `236cd65` |
| Menubar (File / Edit / View / Run / Help) | `69f2376` |
| Local folder import (`webkitdirectory`) | `98b1351` |
| GitHub repo import (ZIP → unzip → bulk create) | `1c3cb2c` |

### 2.6 Authentication (full)
| Area | Commit |
|---|---|
| OAuth2 social login backend | `483f81f` |
| OAuth2 buttons on Login + Signup | `b33bec9` |
| OIDC clock skew tolerance | `5e6432c` |
| Stale-JWT silent-discard fix | `d45ece2` |

### 2.7 Branding & docs
| Area | Commit |
|---|---|
| Chameleon logo + favicon | `049c80a` |
| README rewrite (PFE-grade landing page) | `3b9725e` |
| 3 UML diagrams + Merise MCD | `0f44b3d` |
| OAuth setup plan | `e5e8206` |

### 2.8 Admin dashboard
| Area | Commit |
|---|---|
| Backend (bootstrap, 7 endpoints, role guard, stats incl. Qdrant) | `2d1618b` |
| Frontend (3 tabs: Users / Rooms / Stats + detail dialog) | `5a92e55` |
| Hidden from dashboard chrome (URL-only access) | `bf2afd5` |

### 2.9 Tooling
| Area | Commit |
|---|---|
| One-shot Windows launcher | `1982305` |
| `.env` loader for backend | `815c91e` |
| Postgres port remap (5433) | `f0ca6ef` |

---

## 3. Pending PFE deliverables (the original 4-week plan)

### Week 3 — Polish & remaining backend bits (~10h)
- [ ] `docs/api.md` — full endpoint reference with curl examples + payload schemas
- [ ] Seed SQL — 3 demo users + 2 demo rooms with realistic content
- [ ] Profile edit page + `PATCH /users/me` (frontend wiring)
- [ ] `LICENSE` (MIT) at the repo root
- [ ] `CONTRIBUTING.md`

### Week 4 — Defense prep (~25h, the bulk of remaining work)
- [ ] **PFE memoire** (`docs/memoire/`, ~30-50 pages, French): introduction, état de l'art, conception, réalisation, tests, conclusion
- [ ] Defense slides (~15-20 slides, French)
- [ ] Scripted demo flow (numbered click sequence so nothing improvises live)
- [ ] Backup demo video (~5 min screen recording)
- [ ] Capture screenshots for the README placeholders (`docs/screenshots/*.png`)

---

## 4. New feature: user project dashboard

The current `/dashboard` shows a flat list of rooms and a "create
room" form. The user wants a richer **project dashboard** that
reflects how developers actually think about their work: as
projects, with metadata, status indicators, and quick actions.

The simplest path is to rename the user-facing concept from "room"
to "project" without touching the backend schema (Room is still
the right primitive; we just label it differently in the UI). The
table itself stays — we just enrich each row.

### 4.1 Data model — no breaking change required

We do not need a new entity. A `Room` already has everything we
need to display a "project card": `name`, `description`,
`visibility`, `inviteCode`, `createdAt`, `updatedAt`, the owner,
the file count, the member count. The frontend can compose a
"project" view from existing endpoints.

### 4.2 UI plan

```
┌────────────────────────────────────────────────────────────────────┐
│ My projects                                            [ + New ▾ ] │
│ Search: [______________]   Sort: [Recent ▾]   Filter: [All ▾]      │
├────────────────────────────────────────────────────────────────────┤
│ ┌──────────────────────┐ ┌──────────────────────┐ ┌──────────────┐ │
│ │ ⭐ Algorithms Lab    │ │   PFE — RAG demo     │ │ React mobile │ │
│ │ Java · 8 files       │ │ Python · 12 files    │ │ TypeScript   │ │
│ │ 3 collaborators      │ │ 2 collaborators      │ │ 5 files      │ │
│ │ Updated 2 hours ago  │ │ Updated yesterday    │ │ Updated 3d   │ │
│ │ 🟢 1 person editing  │ │                      │ │              │ │
│ │  [Open] [Share] [⋯]  │ │ [Open] [Share] [⋯]   │ │ [Open] ...   │ │
│ └──────────────────────┘ └──────────────────────┘ └──────────────┘ │
└────────────────────────────────────────────────────────────────────┘

Recent activity (sidebar)        ▼
─────────────────────────────────────
🟢 You ran fibonacci.py  (5 min ago)
🟢 Alice joined Algorithms Lab (15 min)
🟢 Bob asked the AI in PFE — RAG demo
```

### 4.3 New / enriched endpoints (backend, ~3h)

- `GET /rooms` — already exists, but extend `RoomResponse` to include
  `fileCount`, `memberCount`, `lastEditedBy` (denormalized fields,
  cheap counts).
- `GET /rooms/{id}/activity?since=...` — paginated stream of recent
  events (joined, ran code, asked AI, indexed). Backed by a new
  `room_events` table with append-only rows.
- `POST /rooms/{id}/pin` and `DELETE /rooms/{id}/pin` — per-user
  pinning. Backed by a `room_pins (user_id, room_id)` table. Pinned
  projects bubble to the top of the dashboard.
- `POST /rooms/{id}/archive` and unarchive — soft-delete style;
  archived projects hidden by default.

### 4.4 Frontend (new components, ~4h)

- `ProjectCard.tsx` — replaces the current text rows.
- `NewProjectMenu.tsx` — replaces the "create room" form with a
  dropdown menu offering: **Empty project**, **From template**,
  **Import local folder**, **Import from GitHub**. Each opens a
  pre-existing flow.
- `ProjectFilters.tsx` — search bar + sort dropdown (recent /
  alphabetical / most files / most active) + filter dropdown
  (All / Owned / Joined / Pinned / Archived).
- `ActivityFeed.tsx` — sidebar widget polling `/rooms/.../activity`
  every 30 s.

### 4.5 Templates (~2h)

A small JSON catalogue (`backend/src/main/resources/templates/`)
with starter content the user can pick at creation time:

- **Empty Java** — single `Main.java` with a `public static void main`
- **Empty Python** — single `main.py` with `print('Hello, Codeleon')`
- **Hello React** — `App.jsx` + `index.css` skeleton
- **Algorithms playground** — a few classic algorithms in Python (binary
  search, Fibonacci, BFS) ready to run

`POST /rooms` gains an optional `templateId` parameter that copies
the template files into the freshly-created Y.Doc.

### 4.6 Effort summary
- Backend: ~5h (events table, pin/archive endpoints, templates)
- Frontend: ~5h (4 new components + dashboard rewrite)
- Total: ~10h, 3-4 commits

---

## 5. New feature: VS-Code-style editor

The Monaco editor is already there with multi-file tabs, syntax
highlighting, and a menubar. The next layer is to bring the
ergonomics closer to a real IDE.

### 5.1 Settings panel

A gear icon in the menubar opens a settings drawer (Radix Dialog or
sidebar overlay). Settings persist per-user via `localStorage` and
optionally sync to the backend later.

```
┌────────────────────────────────────────────────────┐
│ ⚙️ Editor settings                            [×]  │
├────────────────────────────────────────────────────┤
│ Appearance                                         │
│   Theme         [ Codeleon Dark ▾ ]                │
│   Font family   [ Geist Mono       ]               │
│   Font size     [ 14 ▾ ]                           │
│                                                    │
│ Behavior                                           │
│   Tab size            [ 4 ▾ ]                      │
│   Insert spaces       [✓]                          │
│   Word wrap           [✓]                          │
│   Format on save      [ ]                          │
│   Auto-save delay     [ 3000 ms ]                  │
│   Render whitespace   [ Selection ▾ ]              │
│                                                    │
│ AI                                                 │
│   Auto-index on save  [✓]                          │
│   Context window      [ 5 chunks ▾ ]               │
└────────────────────────────────────────────────────┘
```

Effort: ~3h frontend (Radix Dialog + Zustand-persisted settings
store + apply each setting to Monaco's options or the chat call).

### 5.2 Add folder (nested file tree)

Currently the file list is **flat**: every file lives at the top
level of the room. To support `src/Main.java` showing as a folder
tree, we need:

- Backend: no schema change needed. The `path` column already accepts
  slashes; we just rebuild the tree client-side.
- Frontend: a real tree view component. The current `FileExplorer`
  needs to:
  - Group paths by their `/` segments.
  - Render expand/collapse triangles for folders.
  - Right-click a folder → "New file in folder" / "Rename folder" /
    "Delete folder" (cascades to children).
  - Drag files between folders (out of scope first iteration).

Effort: ~5h frontend, no backend change.

### 5.3 Command palette (Ctrl+Shift+P)

Monaco ships with a built-in command palette. We just need to:

- Stop intercepting Ctrl+Shift+P.
- Optionally augment it with **Codeleon-specific commands**
  ("Index this file", "Run", "Open in browser") so the palette
  becomes the universal action surface.

Effort: ~1h.

### 5.4 Search across files (Ctrl+Shift+F)

Monaco's find is per-file. To search across all files in a project:

- New `GET /rooms/{id}/search?q=...` endpoint that scans every
  `Y.Text` in the Y.Doc on the backend (or relies on a denormalized
  index — out of scope).
  Realistically, this will run on the **frontend** by iterating
  `ydoc.getText(path)` across all open files. Free, fast, no backend
  change.
- Frontend: a side panel showing match results with file:line
  preview, click-to-jump.

Effort: ~3h frontend.

### 5.5 Theme variants

Use the chameleon metaphor: **the chameleon adapts**. Ship 4 themes:

- **Codeleon Dark** (current, default)
- **Codeleon Cyan** (gradient-heavy on accents)
- **Codeleon High Contrast** (accessibility)
- **Codeleon Light** (white background, dark code)

Effort: ~2h. Theme JSON definitions + a select in the settings panel.

### 5.6 Effort summary
- Settings panel: 3h
- Add folder (tree view): 5h
- Command palette: 1h
- Cross-file search: 3h
- Theme variants: 2h
- **Total: ~14h** if we do all five. Pick a subset based on PFE budget.

---

## 6. Other ideas worth considering

Drawn from competing platforms (CodeSandbox, Replit, GitHub
Codespaces, Stackblitz) and the user's likely defense narrative.
Each is small enough to scope individually — pick the ones that
serve your demo story best.

| # | Idea | Why it matters | Rough effort |
|---|---|---|---|
| 1 | **Project templates marketplace** | Lets users save their own templates and share them publicly. Demonstrates a small but real social feature. | 6h |
| 2 | **Markdown live preview pane** | Split-pane view when the active file is `.md`. Easy win, very visual. | 3h |
| 3 | **Multiple language runners** (Node, Java, Go) | Demonstrates extensibility of the sandbox runner. Each = 1-2h with a Docker image swap. | 4-6h |
| 4 | **AI inline suggestions** ("explain this line") | Right-click a selection → "Ask AI". Reuses the existing RAG endpoint. | 2h |
| 5 | **Public project landing page** | Each public project gets a `/p/{inviteCode}` read-only page anyone can preview without an account. SEO bait + impressive demo. | 4h |
| 6 | **Activity heatmap** on the user profile | GitHub-style green-square contribution calendar driven by `room_events`. | 3h |
| 7 | **Refresh-token auto-rotation in axios** | Currently when the access token expires the user has to log out + log in. A response interceptor that catches 401 and silently refreshes would polish the UX. | 2h |
| 8 | **Per-file run history** | Every Run click logs to a `run_history` table; the OutputPanel gains a dropdown to revisit prior runs. | 3h |
| 9 | **Comments on lines** (a la GitHub PR review) | A `room_comments (file_id, line, user_id, body)` table + decorations on Monaco lines. | 5h |
| 10 | **Notifications panel** | Inbox-style: someone joined, AI answered, run finished. Backed by a `notifications` table + WebSocket push. | 5h |
| 11 | **Public invite code QR** | Click "Share" → shows a QR for the invite link. 30 lines of code with `qrcode.react`. | 1h |
| 12 | **Session presence map** | Sidebar shows who is currently in the room + which file each person is editing right now. Reuses Yjs awareness. | 2h |
| 13 | **Room export as ZIP** | Server-side zip of every Y.Text in the Y.Doc + RoomFile metadata. | 2h |
| 14 | **AI chat history persistence** | Currently chats are session-only. Persisting them per-room lets the user revisit past Q&A. | 4h |
| 15 | **Multi-account on the same browser** | Switch between Codeleon accounts without logging out (like GitHub). Useful for the demo (`as admin`, `as collaborator`). | 5h |
| 16 | **CLI / API token** | A user can mint an API token from their profile and use it via curl, demonstrating the underlying API. | 3h |
| 17 | **Slack-style command bar** for the chat (`/run`, `/index`, `/clear`) | Adds slash commands to the AI chat panel for quick power-user actions. | 2h |
| 18 | **Mobile-friendly dashboard** | The room editor is desktop-only by nature, but the dashboard could become a phone-friendly read-only view. | 4h |
| 19 | **Audit log** in the admin panel | Already mentioned — a row per admin action with who/when/what. Easy ops sell. | 3h |
| 20 | **System health page** for non-admins | `/status` page showing Postgres / Redis / Qdrant / Ollama up/down. Doubles as a "uptime" panel for the defense. | 2h |
| 21 | **Embedded terminal (bash MVP)** | xterm.js panel + WebSocket PTY proxy that pipes a sandboxed bash inside the room's runner container. Materialises the room's `RoomFile` rows into a tmpfs so `python main.py` actually works. **Bash only**: PowerShell adds another container image and Windows `cmd` is a different Docker engine entirely (host isolation), so it is intentionally out of scope. **Stretch goal — only attempted after the memoire is done.** | 8h |
| 22 | **Template content seeding (Pass B)** | The Inc 3 template feature ships file *structure* only — every template file lands on disk with the right name and language, but the editor opens empty. Filling them in requires either a Java Y.Doc encoder (heavy dep) or a `seed_content TEXT` column on `room_files` that the WebSocket handler injects into the corresponding Y.Text on the first connect and then clears. Pick the second approach: V6 migration adds the column, `RoomService.createRoom` populates it from the template JSON's new `content` field, `CollabWebSocketHandler` writes the seed into Y.Text once and nulls the column. After this every template gives the user a real running starting point instead of empty files. | 2h |

---

## 7. Recommended priority for the remaining PFE timeline

You currently have ~3.5 weeks of calendar time left and the bulk of
the code work is done. The PFE jury cares about the document and
the live demo, in that order. With that in mind:

### Week 3 (this week, May 12-18) — Polish & docs [~10h]
1. `docs/api.md` (with curl examples)
2. `LICENSE` (MIT) and `CONTRIBUTING.md`
3. Profile edit page (small, shippable in 2h)
4. `User project dashboard` — sections 4.4 and 4.5 (cards + templates,
   the visual upgrade is the most jury-impressive non-feature)

### Week 4 (May 19-25) — Memoire + slides [~20h]
1. `docs/memoire/` outline + chapter-by-chapter rédaction
2. Defense slides
3. Demo script + backup video

### Stretch goals if you finish memoire early
Pick **one or two** from section 6 above for additional wow factor:
- Idea **2** (markdown preview) — great visual demo
- Idea **5** (public project page) — easy crowd-pleaser
- Idea **12** (presence map) — reinforces the collaboration story
- Idea **20** (system health page) — useful for the soutenance Q&A
- Idea **21** (embedded bash terminal) — confirmed late-stretch goal,
  decided 2026-05-09. Only attempt with at least 2 free days and after
  the memoire is finalised. The full multi-shell version (PowerShell +
  Windows cmd) is explicitly post-PFE; only a sandboxed bash will be
  attempted, sharing the existing runner Docker pipeline.

### What to NOT do before the defense
- Section **5.2 (nested folders)** — looks essential but is 5h of
  fiddly tree code and the flat list works fine for demo content.
- Section **6.10 (notifications)** — adds DB tables and WS events
  for a feature the jury will not see in a 5-min demo.
- Section **6.15 (multi-account)** — neat but doesn't strengthen
  the defense narrative.

---

## 8. Deployment & sharing — can other people visit a room?

Out of the box Codeleon binds everything to `localhost`, so the
short answer is **no, not without configuration**. Here are the four
realistic levels of "remote access", from cheapest to most involved.

### 8.1 Level 0 — Same machine, multiple browsers ✅ free

Open Chrome with the OWNER account and Edge in private mode with a
second account. Both will land on `http://localhost:5173`, both will
see each other's cursors in the editor. Perfect for the PFE
projector demo and requires **zero** setup. This is what we will
recommend on stage.

### 8.2 Level 1 — Same Wi-Fi / LAN ⚠️ 5 changes

Lets a second device on the same Wi-Fi (a friend's laptop, your
phone) connect to your machine via your local IP.

```powershell
# 1. Find your LAN IP (e.g. 192.168.1.42)
ipconfig | Select-String "IPv4"
```

```diff
# 2. Edit .env
- VITE_API_BASE_URL=http://localhost:8080/api/v1
+ VITE_API_BASE_URL=http://192.168.1.42:8080/api/v1
- CORS_ALLOWED_ORIGINS=http://localhost:5173
+ CORS_ALLOWED_ORIGINS=http://localhost:5173,http://192.168.1.42:5173
```

```bash
# 3. Bind Vite to all interfaces
cd frontend-web
npm run dev -- --host 0.0.0.0
```

```powershell
# 4. Open Windows firewall ports (admin PS)
New-NetFirewallRule -DisplayName "Codeleon backend"  -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "Codeleon frontend" -Direction Inbound -LocalPort 5173 -Protocol TCP -Action Allow
```

5. Restart the backend so it picks up the new `CORS_ALLOWED_ORIGINS`
   and `VITE_API_BASE_URL`.

The visitor opens `http://192.168.1.42:5173`. Email/password login
works. **OAuth fails** because the GitHub / Google callback URLs
are fixed at `http://localhost:8080/api/v1/login/oauth2/code/...`.
Adding a second callback URL at each provider lets the LAN host
work too.

### 8.3 Level 2 — Internet via ngrok 🟠 single demo, ~10 min setup

Lets anyone on the internet (your jury, your remote supervisor)
hit your laptop. Useful as a backup if the auditorium projector
fails on defense day.

```powershell
choco install ngrok    # or: winget install ngrok

# Two parallel PowerShell tabs:
ngrok http 5173        # → https://abc123.ngrok.app  (frontend)
ngrok http 8080        # → https://xyz789.ngrok.app  (backend)
```

```diff
# .env
- VITE_API_BASE_URL=http://localhost:8080/api/v1
+ VITE_API_BASE_URL=https://xyz789.ngrok.app/api/v1
- CORS_ALLOWED_ORIGINS=http://localhost:5173
+ CORS_ALLOWED_ORIGINS=https://abc123.ngrok.app
```

Restart both processes; the public URL is now
`https://abc123.ngrok.app`.

Caveats:
- Free ngrok URLs change on every restart (paid plans get fixed
  domains).
- OAuth needs the ngrok URLs registered in each provider's callback
  list every session — painful, **so disable OAuth for the
  ngrok-hosted demo and rely on email/password only**.
- WebSockets work over `wss://` automatically because ngrok proxies
  TCP, so live collaboration still functions.

### 8.4 Level 3 — Real cloud deployment 🔴 ~2-3 days, optional for PFE

Sustained, public, 24/7 access at e.g. `https://codeleon.bznitar.dev`.
Recommended **after** the defense — it's nice for a portfolio link,
not required for the PFE itself.

| Layer | Service | Cost |
|---|---|---|
| Frontend (Vite build) | Vercel or Cloudflare Pages | free |
| Backend (Spring Boot JAR) | Render or Fly.io free tier | free, with cold starts |
| Postgres | Supabase or Neon free tier | free |
| Redis | Upstash free tier | free |
| Qdrant | Qdrant Cloud free tier (1 GB) | free |
| Ollama | ⚠️ no free option that runs the model | — |

**The Ollama problem.** `qwen2.5-coder:0.5b` needs ~2 GB RAM and a
half-decent CPU. No free tier supports that. Two workarounds:

1. **Keep Ollama on your local machine** and tunnel it
   (`ngrok http 11434`). The cloud-hosted backend hits the ngrok
   URL for inference. Implies your PC must be online during demos.
2. **Swap Ollama for the OpenAI API** (~5 USD/mo for demo traffic).
   Defeats the "100% local" pitch — bad idea for the PFE narrative.

### 8.5 Recommendation for the defense

Do **Level 0** during the defense (your laptop projected, you flip
between two browser windows to demonstrate collaboration). Have
**Level 2** ready as a safety net by writing the ngrok commands
into a small `scripts/demo-ngrok.ps1` so you can stand it up in two
minutes if the projector fails. Skip **Level 3** until after you
graduate.

---

## 9. How to keep this file fresh

- Whenever you ship a feature, add it to section 2 with the commit
  hash.
- Whenever the user asks for something new, append it to section 6
  with an effort estimate before deciding when to schedule it.
- The 4-week plan in section 7 is the binding contract until the
  defense; revisit it weekly.

This file replaces the previous `docs/NEXT-SESSION.md` as the
forward-looking source of truth (`NEXT-SESSION.md` was a snapshot
at one moment in time, now stale).
