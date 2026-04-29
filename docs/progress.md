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

## Current Git History

```text
c7eac9b feat: add room editor page
475067a feat: add room management
9ea55b4 feat: bootstrap codeleon foundation
```

## Next Planned Work

According to the MVP plan, the next steps are:

1. Add basic Yjs collaboration on the frontend.
2. Add backend WebSocket support for room collaboration.
3. Add user awareness in rooms.
4. Add persistent room files/code model.
5. Add Code Runner service, starting with Python.
6. Add JavaScript and Java execution.
7. Add Ollama and Qdrant services.
8. Build the RAG indexing pipeline.
9. Add AI chat panel and streaming responses.

Immediate next technical milestone:

**Basic Yjs collaboration inside the Monaco room editor.**
