# Component Diagram

Static deployment view of every Codeleon piece and how they talk to each
other. Boxes are processes, arrows are protocols. Read top-to-bottom:
client → backend → data + AI + sandbox.

```mermaid
flowchart TB
    subgraph Client["Client (browser, http://localhost:5173)"]
        UI["React 18 + Vite 5 + Tailwind 3<br/>Pages: Landing / Login / Signup<br/>Dashboard / Room"]
        Editor["Monaco editor<br/>+ y-monaco binding"]
        Yjs["Yjs Y.Doc<br/>1 per room<br/>N Y.Texts (one per file path)"]
        Chat["ChatPanel<br/>SSE consumer (fetch + ReadableStream)"]
        UI --> Editor
        Editor --> Yjs
        UI --> Chat
    end

    subgraph Backend["Backend (Spring Boot 3.2.5 / JDK 21, http://localhost:8080/api/v1)"]
        Auth["AuthController<br/>POST /auth/register, /login, /refresh, /logout<br/>GET  /auth/me, /auth/providers"]
        OAuth["OAuth2LoginSuccessHandler<br/>GitHub + Google → JWT + redirect"]
        RoomCtrl["RoomController<br/>CRUD rooms, invite codes"]
        FileCtrl["RoomFileController<br/>multi-file CRUD + snapshot"]
        Run["RunController<br/>Python sandbox"]
        ChatCtrl["ChatController<br/>RAG SSE streaming"]
        Idx["IndexController<br/>chunk + embed + upsert"]
        GH["GithubImportController<br/>ZIP fetch + unzip"]
        WS["CollabWebSocketHandler<br/>binary relay /ws/rooms/{id}"]
    end

    subgraph Data["Data layer"]
        PG[("PostgreSQL 16<br/>localhost:5433<br/>Flyway V1..V4")]
        Redis[("Redis 7<br/>port 6379")]
    end

    subgraph AI["AI services (Docker compose profile: ai)"]
        Qdrant[("Qdrant 1.11<br/>port 6333<br/>768-dim Cosine")]
        Ollama["Ollama (CPU only)<br/>qwen2.5-coder:0.5b<br/>nomic-embed-text"]
    end

    subgraph Sandbox["Code execution"]
        DockerRun["Docker python:3.12-slim<br/>--network=none<br/>--memory=256m, --cpus=0.5"]
    end

    GHcom["github.com<br/>(public repos)"]
    Browser_OAuth["GitHub / Google OAuth<br/>(authorization servers)"]

    UI -- "REST + Bearer JWT" --> Auth
    UI --> RoomCtrl
    UI --> FileCtrl
    UI --> Run
    Chat -- "SSE (text/event-stream)" --> ChatCtrl
    UI --> Idx
    UI --> GH
    UI -- "OAuth2 redirect" --> Auth
    UI -. "Browser redirect" .-> Browser_OAuth
    Browser_OAuth -. "callback" .-> OAuth
    OAuth -- "JWT redirect to /auth/callback" --> UI

    Yjs -- "WebSocket binary frames" --> WS
    Auth --> PG
    OAuth --> PG
    RoomCtrl --> PG
    FileCtrl --> PG
    WS --> Redis

    ChatCtrl -- "embed + chat (HTTP)" --> Ollama
    ChatCtrl -- "search (HTTP)" --> Qdrant
    Idx -- "embed (HTTP)" --> Ollama
    Idx -- "upsert (HTTP)" --> Qdrant

    Run -- "spawn" --> DockerRun

    GH -- "GET archive.zip" --> GHcom
```

## Notes for the defense

- **Single Y.Doc per room with N Y.Texts.** One byte[] snapshot persists
  every file at once. The WebSocket handler is a pure binary relay — it
  does not run a server-side Y.Doc, which is why Yjs's "sync step 2"
  never fires server-side and the frontend marks the room ready as soon
  as the WS opens (the prior state is restored from REST snapshot).
- **OAuth registers both providers programmatically.** A
  `@ConditionalOnExpression`-gated bean assembles the
  `ClientRegistrationRepository` only from providers whose env vars are
  set, so the backend boots clean even with no credentials configured
  and the frontend hides the relevant buttons via `/auth/providers`.
- **The sandbox container has `--network=none`.** User-supplied Python
  code cannot reach Postgres / Ollama / Qdrant or the public internet.
- **Ollama and Qdrant live behind the `ai` Docker compose profile.** The
  core stack (Postgres + Redis) boots without them; flipping
  `AI_ENABLED=true` and bringing up the profile turns on RAG.
