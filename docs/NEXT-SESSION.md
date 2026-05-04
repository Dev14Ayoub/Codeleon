# Codeleon — Session Handoff

> Last session ended: **2026-05-01**, mid-implementation of RAG commit #3.

## TL;DR — where to resume tomorrow

Commit #3 of the RAG milestone (`feat: add RAG chat endpoint with SSE streaming`)
is **half-done**. Code is written but **not yet tested or committed**.

To resume, run these in order:

```powershell
cd D:\Codeleon\backend
$env:JAVA_HOME = "C:\Users\pc\.jdks\openjdk-23.0.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn test
```

Expected: **30/30 tests green** (26 existing + 4 RoomChatServiceTest + 0 ChatControllerTest currently because the controller test only checks status codes, no SSE assertions).

If green → smoke test live → commit + push. If red → fix, then proceed.

---

## What is on disk but **not yet committed**

Six new files in `com.codeleon.ai`:

```
backend/src/main/java/com/codeleon/ai/
  OllamaStreamer.java        # NDJSON streaming via java.net.http.HttpClient
  ChatRequest.java           # DTO: query, topK, history
  RoomChatService.java       # embed -> search -> buildPrompt -> stream
  ChatController.java        # POST /rooms/{id}/chat -> SseEmitter

backend/src/test/java/com/codeleon/ai/
  RoomChatServiceTest.java   # 5 tests with mocked clients
  ChatControllerTest.java    # 2 tests: member starts SSE / non-member 404
```

`git status` confirms: 6 untracked files, no modifications to committed files.

## Resume checklist (commit #3 final stretch)

1. **Run tests**
   ```powershell
   cd D:\Codeleon\backend
   $env:JAVA_HOME = "C:\Users\pc\.jdks\openjdk-23.0.1"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   mvn test
   ```
   Watch for the new `RoomChatServiceTest` and `ChatControllerTest` results. Likely friction points if any:
   - SSE async dispatch quirk in `ChatControllerTest` — if it hangs, replace
     `asyncDispatch(mvcResult)` with a simpler check (just verify `request().asyncStarted()`).
   - Mockito on records: should be fine with `-Dnet.bytebuddy.experimental=true`
     already in surefire config.

2. **Live smoke test** — start the full stack:
   ```powershell
   cd D:\Codeleon
   .\scripts\start.ps1 -Ai
   ```
   Then in a 4th terminal:
   ```bash
   # 1. Register, create room, index some code (re-use the recipe from commit #2 smoke test).
   # 2. Hit the chat endpoint with curl + SSE:
   curl -N -X POST http://localhost:8080/api/v1/rooms/<ROOM_ID>/chat \
     -H "Authorization: Bearer <TOKEN>" \
     -H "Content-Type: application/json" \
     -d '{"query":"What does the fibonacci function do?"}'
   ```
   You should see: `event: context`, then a stream of `event: token` lines, then `event: done`.

3. **Commit + push** with this message:
   ```
   feat: add RAG chat endpoint with SSE streaming

   Adds the third RAG milestone: a chat endpoint that grounds Ollama's
   response in the user's indexed code. Pipeline: embed query -> top-K
   Qdrant search filtered by roomId -> assemble system prompt with
   excerpts -> stream tokens via Server-Sent Events.

   - OllamaStreamer: java.net.http.HttpClient consumes Ollama's NDJSON
     /api/chat response line-by-line. No spring-boot-starter-webflux
     dependency added.
   - RoomChatService: orchestrates embed/search/prompt/stream. Emits
     three SSE event types: 'context' (retrieved chunks), 'token' (each
     assistant chunk), 'done' (stats).
   - ChatController: POST /rooms/{roomId}/chat returns SseEmitter, runs
     the streaming on a cached executor so the request thread is freed
     immediately. Read-membership check via RoomFileService.canRead.
   - Tests: 5 unit tests for the service (prompt assembly, context
     truncation, happy path, error path) + 2 controller tests (auth flow).
   ```

4. **Update progress.md** with a "Milestone 7: RAG chat endpoint" section
   following the existing convention.

---

## Roadmap status (after commit #3 lands)

### Done
- Foundation (auth, JWT, rooms, members, invite codes)
- Real-time collab (Yjs + WebSocket + Monaco binding, multi-cursors visible)
- Code runner (Python via Docker sandbox)
- RAG infrastructure (Qdrant + Ollama under "ai" compose profile)
- **RAG commit #1** — Ollama and Qdrant Spring clients (`1012d87`)
- **RAG commit #2** — file indexing pipeline (`5291c2f`)
- **RAG commit #3** — chat endpoint with SSE streaming (`5bac642`)
- **RAG commit #4** — ChatPanel UI (`2afe043`)
- One-shot Windows launcher (`1982305`, fixed quoting in `a475981`)

### Remaining for the PFE (4-week plan agreed 2026-05-01)

#### Week 1 — finish RAG (~10h)
- [x] commit #3: chat endpoint backend
- [x] commit #4: ChatPanel React component
- ✅ **WEEK 1 COMPLETE**

#### Week 2 — branding + visual docs (~10h)
- [ ] **Logo Caméléon** (SVG + favicon, replace `<Braces />` everywhere)
      → being delegated to Codex (prompt drafted; user will paste it).
- [ ] **OAuth2 social login** (GitHub + Google) — full plan in
      [docs/oauth-plan.md](oauth-plan.md). Two commits, ~5h. Wait until
      after the logo lands so the LoginPage looks final before adding
      the social buttons.
- [ ] **README pro** with screenshots, badges, feature list
- [ ] **3 Mermaid UML diagrams** (component, collab sequence, RAG sequence)
- [ ] **1 Merise MCD** (User, Room, RoomMember, RoomFile, RefreshToken)

#### Week 3 — polish + missing deliverables (~10h)
- [ ] `docs/api.md` with curl examples + payload schemas
- [ ] Seed SQL with 3 demo users + 2 rooms
- [ ] Membership check on `GET /rooms/{id}` itself (currently only auth, not membership)
- [ ] Profile edit page + `PATCH /users/me`
- [ ] LICENSE (MIT) + CONTRIBUTING.md

#### Week 4 — defense prep (~10h)
- [ ] Memoire PFE document (~30-50 pages)
- [ ] Defense slides (~15-20 slides)
- [ ] Scripted demo flow
- [ ] Backup demo video (5 min)

### Decisions still pending
- **Memoire writing**: user writes themselves vs. Claude provides detailed outline
- **Mobile app**: officially out of scope (frontend-mobile/ does not exist)

---

## Key project facts to remember

- **JAVA_HOME points to JDK 17 which fails to build** — must export
  `JAVA_HOME=C:\Users\pc\.jdks\openjdk-23.0.1` before any `mvn` command.
- **Postgres host port = 5433** (not the default 5432) to avoid the native
  Windows Postgres install conflict. `POSTGRES_PORT=5433` is in `.env` and
  the default in `docker-compose.yml`.
- **Surefire needs `-Dnet.bytebuddy.experimental=true`** for Mockito on JDK 23
  (already wired in `pom.xml`).
- **Codeleon AI flag** is `codeleon.ai.enabled=false` by default. Set
  `AI_ENABLED=true` env var to bootstrap the Qdrant collection at startup.
- **Launcher**: `.\scripts\start.ps1 -Ai` does docker + backend + frontend + browser.
