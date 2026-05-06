# 🦎 Codeleon

> **Code + Caméléon** — a collaborative coding platform with real-time
> multi-cursor editing, sandboxed code execution, and a local
> retrieval-augmented AI assistant.

[![Tests](https://img.shields.io/badge/tests-57%20passing-brightgreen)](#running-the-tests)
[![Backend](https://img.shields.io/badge/backend-Spring%20Boot%203.2-6DB33F)](backend/pom.xml)
[![Frontend](https://img.shields.io/badge/frontend-React%2018%20%2B%20Vite%205-61DAFB)](frontend-web/package.json)
[![Editor](https://img.shields.io/badge/editor-Monaco%20%2B%20Yjs-007ACC)](frontend-web/src/lib/collab)
[![AI](https://img.shields.io/badge/AI-Ollama%20%2B%20Qdrant%20(local)-9333EA)](docker-compose.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](#license)

Codeleon (PFE final-year project, Licence Pro 2026) is a self-hosted,
all-batteries-included alternative to a Google-Docs-for-code: open a
room, share an invite link, type together with live cursors, ask the
local AI assistant about your own code without sending a byte to the
cloud, and run it in a Docker sandbox with one click.

---

## ✨ Highlights

| Feature | Tech |
|---|---|
| **Real-time collaborative editor** with multi-cursor + presence | Yjs CRDT + y-monaco + WebSocket binary relay |
| **Multi-file workspace** with VS Code-style tabs, file explorer, right-click menus, menubar | React + Radix Context Menu / Menubar / Dialog |
| **Code execution sandbox** | Docker `python:3.12-slim`, `--network=none`, 256 MB / 0.5 CPU / 8 s timeout |
| **Local RAG AI assistant** with streaming SSE + retrieved context drawer | Ollama (`qwen2.5-coder:0.5b` + `nomic-embed-text`) + Qdrant 1.11 |
| **Project import** from local folder or public GitHub repo | `webkitdirectory` picker / GitHub archive ZIP fetch + filter |
| **Auth** with JWT email/password **+ OAuth2 social login** (GitHub, Google) | Spring Security 6 + custom programmatic `ClientRegistrationRepository` |

---

## 📸 Screenshots

> *Screenshots to be captured before the defense — placeholders below.*

| Login (with OAuth2) | Dashboard | Room workspace |
|---|---|---|
| `docs/screenshots/login.png` | `docs/screenshots/dashboard.png` | `docs/screenshots/room.png` |

| AI chat (RAG) | File explorer + tabs | GitHub import |
|---|---|---|
| `docs/screenshots/chat.png` | `docs/screenshots/files.png` | `docs/screenshots/github-import.png` |

---

## 🏗️ Architecture (high-level)

```
┌──────────────────────────────────────────────────────────────────┐
│                        Browser (localhost:5173)                  │
│   React + Vite + Tailwind   ·   Monaco + y-monaco + Yjs          │
└────────────────────────────────┬─────────────────────────────────┘
                                 │ REST + JWT, SSE (chat), WS (collab)
┌────────────────────────────────▼─────────────────────────────────┐
│       Spring Boot 3.2 / JDK 21       (localhost:8080/api/v1)     │
│  Auth · Rooms · RoomFiles · Runner · ChatController · Index      │
│  GithubImport · CollabWebSocketHandler · OAuth2SuccessHandler    │
└─┬───────────┬───────────────┬──────────────────┬─────────────────┘
  │ JDBC      │ Lettuce        │ HTTP             │ HTTP NDJSON
┌─▼─────────┐ ┌─▼──────────┐ ┌─▼──────────┐    ┌─▼─────────────────┐
│ Postgres  │ │   Redis    │ │ Qdrant     │    │ Ollama (CPU)      │
│ port 5433 │ │ port 6379  │ │ port 6333  │    │ port 11434        │
│ Flyway    │ │            │ │ 768-d Cos. │    │ qwen2.5 + nomic   │
└───────────┘ └────────────┘ └────────────┘    └───────────────────┘
                                            ┌─────────────────────┐
                                Run code  → │ Docker python:3.12  │
                                            │ --network=none      │
                                            └─────────────────────┘
```

Detailed views in [`docs/uml/`](docs/uml/) and [`docs/merise/mcd.md`](docs/merise/mcd.md):
- [Component diagram](docs/uml/component-diagram.md) — every process and protocol on the wire
- [Sequence: real-time collaboration](docs/uml/sequence-realtime-collab.md) — keystroke to peer to snapshot
- [Sequence: RAG chat](docs/uml/sequence-rag-chat.md) — query to embed to search to streaming reply
- [Merise MCD](docs/merise/mcd.md) — entities, cardinalities, integrity constraints

---

## 🚀 Quick start (Windows / PowerShell)

> Codeleon is developed and tested on Windows 11 with Docker Desktop +
> WSL2. Linux/macOS work too — adjust the launcher script accordingly.

### 1. Prerequisites

| Tool | Version | Why |
|---|---|---|
| Docker Desktop | latest with WSL2 backend | Postgres, Redis, Qdrant, Ollama, sandbox runner |
| JDK | 21+ (we use **JDK 23**) | Backend (`JAVA_HOME` must point to a 21+ JDK) |
| Maven | 3.9+ | Backend build |
| Node | 20+ | Frontend build |
| Git | latest | obvious |

### 2. Clone + env

```powershell
git clone https://github.com/Dev14Ayoub/Codeleon.git
cd Codeleon
Copy-Item .env.example .env
```

The defaults in `.env` are correct for local development; edit the
file if you want OAuth (GitHub / Google client IDs and secrets) — see
[`docs/oauth-plan.md`](docs/oauth-plan.md) for the credential setup
guide.

### 3. One-shot launcher (full stack in one command)

```powershell
.\scripts\start.ps1 -Ai
```

This script:

1. Creates `.env` from the example if missing
2. Spins up Postgres + Redis (and Qdrant + Ollama with `-Ai`)
3. Waits until every container is `healthy` (60 s timeout)
4. Opens a *Codeleon Backend* PowerShell window (Spring Boot)
5. Opens a *Codeleon Frontend* PowerShell window (Vite)
6. Opens `http://localhost:5173` in the default browser

To stop everything: `.\scripts\stop.ps1`.

### 4. Manual start (Bash / Linux / macOS)

```bash
# 1. Stack
docker compose up -d                        # core: Postgres + Redis
docker compose --profile ai up -d           # adds Qdrant + Ollama

# 2. Backend (JDK 21+ required)
export JAVA_HOME=/path/to/jdk-21
cd backend
mvn spring-boot:run                         # blocks; runs on :8080

# 3. Frontend (in another terminal)
cd frontend-web
npm install
npm run dev                                 # runs on :5173
```

Open `http://localhost:5173`.

---

## 🧪 Running the tests

```bash
cd backend
mvn test
```

Currently **57 tests** covering auth, rooms, multi-files,
runner, OAuth, GitHub import, RAG service, RAG chat controller,
embeddings + chunker.

```
Tests run: 57, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Frontend type checking and build:

```bash
cd frontend-web
npx tsc --noEmit         # type check
npm run build            # production bundle
```

---

## 🦎 Why "Codeleon"?

The name is a portmanteau of **Code + Caméléon** (chameleon).
A chameleon is a remarkably good metaphor for collaborative coding:

- It changes color → multi-user cursors (every peer gets a unique color).
- Its eyes move independently → multiple users editing the same file simultaneously.
- It adapts to its environment → multi-language editor with extension-aware syntax highlighting.

---

## 🗺️ Roadmap

Done:

- [x] Auth (JWT, refresh tokens, OAuth2 GitHub + Google)
- [x] Rooms, members, invite codes
- [x] Real-time collaborative editing (Yjs, multi-cursor, snapshot persistence)
- [x] Code runner (Python sandbox)
- [x] RAG infrastructure (Qdrant + Ollama, file indexing, chat endpoint, ChatPanel UI)
- [x] Multi-file workspace (file explorer, tabs, menubar, local + GitHub import)
- [x] UML and Merise documentation
- [x] One-shot Windows launcher

Pending (PFE timeline):

- [ ] Logo + favicon (chameleon mark)
- [ ] Profile edit page + `PATCH /users/me`
- [ ] Demo seed data (3 users + 2 rooms)
- [ ] PFE memoire (~30-50 pages)
- [ ] Defense slides + scripted demo + backup video

Out of scope for this PFE:

- Mobile app (no `frontend-mobile/` planned)
- Multi-language run (only Python)
- Inherited file-tree folders inside the explorer (flat list only)

---

## 📂 Project layout

```
Codeleon/
├── backend/                  # Spring Boot 3.2 / JDK 21 target / JDK 23 runtime
│   ├── src/main/java/com/codeleon/
│   │   ├── auth/             # AuthController, JWT, OAuth2 success handler
│   │   ├── ai/               # Ollama + Qdrant clients, RAG chat, indexer, chat panel
│   │   ├── config/           # SecurityConfig, JwtAuthenticationFilter
│   │   ├── room/             # Room CRUD, files, WebSocket, GitHub import
│   │   ├── runner/           # Python Docker sandbox
│   │   └── user/             # User entity + service (incl. findOrCreateByOAuth)
│   └── src/main/resources/db/migration/  # V1..V4 (Flyway)
├── frontend-web/             # React 18 + Vite 5 + Tailwind 3 + TypeScript 5.4
│   └── src/
│       ├── components/       # auth/, chat/, files/, layout/, ui/
│       ├── lib/              # api.ts, chat/, collab/, files/
│       └── pages/            # LandingPage, LoginPage, SignupPage,
│                             # AuthCallbackPage, DashboardPage, RoomPage
├── docs/
│   ├── uml/                  # component, sequence-collab, sequence-rag
│   ├── merise/               # mcd
│   ├── api.md                # endpoint reference
│   ├── architecture.md
│   ├── oauth-plan.md         # GitHub / Google OAuth setup guide
│   └── progress.md           # commit-level changelog
├── scripts/
│   ├── start.ps1             # one-shot launcher (Docker + backend + frontend + browser)
│   ├── stop.ps1
│   └── run-backend.ps1       # backend wrapper used by .claude/launch.json
├── docker-compose.yml        # Postgres + Redis + (profile: ai) Qdrant + Ollama
└── .env.example
```

---

## 🤝 License

MIT. See [LICENSE](LICENSE) (to be added before the defense).

---

## 🎓 PFE context

Final-year project for Licence Pro Génie Logiciel, Faculté
Polydisciplinaire de Taza — academic year 2025-2026. Mentored by
[Claude Code](https://claude.com/claude-code) (AI pair-programming
assistant). Co-authorship is acknowledged on every commit message.
