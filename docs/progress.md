# Codeleon Progress Log

This document tracks what has been completed so far in the Codeleon PFE project.

## Current Repository

- Local folder: `D:\Codeleon`
- GitHub repository: `https://github.com/Dev14Ayoub/Codeleon.git`
- Main branch: `main`

## Completed Work

### 1. Project Foundation

Commit: `9ea55b4 feat: bootstrap codeleon foundation`

Created the initial monorepo structure:

- `backend`: Spring Boot API
- `frontend-web`: React + Vite web application
- `docs`: project documentation
- `infra`: infrastructure folder
- `docker-compose.yml`: local PostgreSQL and Redis setup

Added root configuration files:

- `.editorconfig`
- `.gitignore`
- `.env.example`
- `README.md`

### 2. Backend Authentication

Implemented JWT authentication with Spring Boot:

- User registration
- User login
- Access token generation
- Refresh token generation and storage
- Token refresh
- Logout by refresh token revocation
- Current user profile endpoint

Main endpoints:

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`

Added backend structure:

- `auth`
- `user`
- `room`
- `config`
- `common`

Added database migration:

- `users`
- `refresh_tokens`
- `rooms`
- `room_members`

Added tests:

- Register user
- Login user
- Get authenticated profile

### 3. Frontend Authentication UI

Created the initial React frontend:

- Landing page
- Login page
- Signup page
- Protected dashboard page
- Zustand auth store
- Axios API client
- Zod validation
- React Hook Form integration
- TanStack Query setup

Routes:

- `/`
- `/login`
- `/signup`
- `/dashboard`

### 4. Room Management

Commit: `475067a feat: add room management`

Implemented room CRUD foundation.

Backend endpoints:

- `POST /api/v1/rooms`
- `GET /api/v1/rooms`
- `GET /api/v1/rooms/public`
- `GET /api/v1/rooms/{roomId}`
- `POST /api/v1/rooms/join/{inviteCode}`

Room features:

- Create private or public rooms
- Generate invite codes
- List current user's rooms
- List public rooms
- Join a room by invite code
- Assign owner role on creation
- Assign editor role on invite join

Roles:

- `OWNER`
- `EDITOR`
- `VIEWER`

Frontend dashboard updates:

- Create room form
- Join room by invite code form
- My rooms list
- Public rooms list
- Room cards connected to real backend data

Added tests:

- Create room and owner membership
- List public rooms
- Join room by invite code

### 5. Room Editor Page

Commit: `c7eac9b feat: add room editor page`

Added Monaco Editor integration.

Installed frontend dependencies:

- `@monaco-editor/react`
- `monaco-editor`

Added route:

- `/rooms/:roomId`

Room editor features:

- Monaco Editor with Codeleon dark theme
- Starter `Main.java` file
- Header with room name
- Back to dashboard button
- Invite code copy button
- Disabled Run button prepared for future Code Runner
- Files sidebar
- Participants panel
- AI context placeholder panel

Dashboard room cards now open the room editor page.

## Verification Performed

Backend:

```bash
mvn test -Djava.version=17
```

Current result:

- 6 tests passing
- Auth tests passing
- Room tests passing

Frontend:

```bash
npm run build
```

Current result:

- Production build passing

Known note:

- The local machine currently has OpenJDK 17.
- The project target is Java 21.
- Standard `mvn test` will require JDK 21.
- Temporary validation was done with `-Djava.version=17`.

### 6. Real-time Collaboration (Yjs + WebSocket)

Added live collaborative editing on top of Monaco using Yjs CRDT.

Backend:

- Migration `V2__room_files.sql` adds the `room_files` table (binary `state_update` snapshots).
- New entity `RoomFile`, repository, and `RoomFileService` enforce read/edit access by room role.
- REST endpoints for snapshot persistence:
  - `GET /api/v1/rooms/{roomId}/snapshot` returns the binary Y.Doc state.
  - `PUT /api/v1/rooms/{roomId}/snapshot` saves the binary Y.Doc state.
- `spring-boot-starter-websocket` added to `pom.xml`.
- WebSocket endpoint `/ws/rooms/{roomId}` with:
  - JWT handshake authentication via `?token=` query param.
  - Room membership and edit-role check at handshake time.
  - Pure binary relay: each `BinaryMessage` is forwarded to every other connected peer of the same room.
  - Viewers can read updates but cannot push CRDT or awareness frames.
- Security config now permits `/ws/**` (handshake interceptor handles auth).

Frontend:

- New dependencies: `yjs`, `y-monaco`, `y-websocket`, `y-protocols`.
- `useCollabRoom(roomId)` hook:
  - Loads the persisted snapshot via REST and applies it to a fresh `Y.Doc`.
  - Connects through `WebsocketProvider` with the JWT in the URL params.
  - Publishes the local user (id, name, color) via Yjs awareness.
  - Pushes a debounced (3s) snapshot back to the backend, plus a final snapshot on unmount.
  - Exposes `isConnected`, `isReady`, and the live `peers` list.
- Room editor page (`/rooms/:roomId`):
  - Monaco model bound to `Y.Text` via `MonacoBinding`.
  - Live participants panel driven by Yjs awareness (colored dot per peer).
  - Connection status pill (Live / Offline) in the header.

### 7. Code Runner (Python in Docker sandbox)

Added safe per-execution Python sandboxing driven from the room editor.

Backend:

- New `runner` package with:
  - `RunLanguage` enum (currently `PYTHON`).
  - `RunRequest` / `RunResult` records (validated input, stdout/stderr/exitCode/durationMs/timedOut output).
  - `CodeRunnerService` interface and `DockerCodeRunnerService` implementation.
  - `CodeRunnerProperties` bound to `codeleon.runner.*` (image, timeout, memory, cpus, pids-limit, max-output-bytes, enabled flag).
- `DockerCodeRunnerService` spawns a one-shot container per run with hardened flags:
  - `--rm -i --network=none --cap-drop=ALL --security-opt=no-new-privileges`.
  - `--memory`, `--memory-swap`, `--cpus`, `--pids-limit` from properties.
  - Source code is piped to `python -` via stdin so nothing is written to disk.
  - Stdout/stderr captured asynchronously with a hard byte cap to prevent flooding.
  - `process.waitFor(timeoutMs)` enforces the wall clock; `docker kill <name>` runs on timeout.
- `RunController` exposes `POST /api/v1/rooms/{roomId}/run`:
  - `@AuthenticationPrincipal User` resolved from the JWT.
  - Reuses `RoomFileService.canEdit` to enforce OWNER/EDITOR membership; returns 404 otherwise.
- `application.yml` and `application-test.yml` ship sane defaults.

Frontend:

- `runCode(roomId, payload)` API helper added to `lib/api.ts` with `RunRequest` / `RunResult` types.
- Room editor `Run` button is now wired:
  - Sends the current Monaco buffer to the backend via TanStack Query mutation.
  - New `OutputPanel` shows stdout, stderr (red), exit code, duration, and timeout state.
  - Loading spinner during execution, error rendering on Axios failures.

Tests:

- `RunControllerTest` (Spring Boot + MockMvc):
  - Member can run code; mocked `CodeRunnerService` returns the canned `RunResult`.
  - Non-member receives 404 and the runner is never invoked.

Verification:

- `mvn test` — 8 / 8 tests passing on JDK 23 (Java 21 release target).
- `tsc -b` on `frontend-web` — type-check clean.

## Current Git History

```text
b2bfb94 docs: log real-time collaboration milestone
e00e1ae feat: add real-time collaborative editing with Yjs and WebSocket
1905e82 docs: add project progress log
c7eac9b feat: add room editor page
475067a feat: add room management
9ea55b4 feat: bootstrap codeleon foundation
```

## Next Planned Work

1. Extend the Code Runner to JavaScript (Node) and Java sandboxes.
2. Add Ollama and Qdrant to `docker-compose.yml`.
3. Build the RAG indexing pipeline (embeddings via Nomic, vector store in Qdrant).
4. Add the AI chat panel with streaming responses.
5. Add the text chat (re-uses the WebSocket plumbing already in place).

Immediate next technical milestone:

**RAG stack bootstrap — Ollama + Qdrant in `docker-compose.yml`, embedding pipeline scaffold.**
