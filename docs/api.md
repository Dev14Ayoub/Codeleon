# Codeleon API

Base URL: `/api/v1`

All protected endpoints require:

```http
Authorization: Bearer <accessToken>
```

Dates are ISO-8601 instants. Room IDs, user IDs, and file IDs are UUIDs.

## Authentication

### `POST /auth/register`

Creates an email/password account and returns tokens.

```json
{
  "fullName": "Ayoub Bznitar",
  "email": "ayoub@example.com",
  "password": "Password123"
}
```

### `POST /auth/login`

Authenticates an existing email/password account.

```json
{
  "email": "ayoub@example.com",
  "password": "Password123"
}
```

### `POST /auth/refresh`

Exchanges a refresh token for a new access token.

```json
{
  "refreshToken": "<refreshToken>"
}
```

### `POST /auth/logout`

Revokes a refresh token.

```json
{
  "refreshToken": "<refreshToken>"
}
```

### `GET /auth/me`

Returns the authenticated user.

### `GET /auth/providers`

Returns the enabled OAuth2 providers for the login/signup screens.

OAuth2 browser entrypoints are handled by Spring Security:

- `GET /oauth2/authorization/github`
- `GET /oauth2/authorization/google`
- `GET /login/oauth2/code/{provider}`

## Users

### `GET /users/me`

Returns the authenticated user profile.

### `PATCH /users/me`

Updates the current user's profile.

```json
{
  "fullName": "Ayoub Bznitar"
}
```

## Rooms / Projects

Rooms are the backend primitive. The frontend presents them as projects.

Visibility values:

- `PRIVATE`
- `PUBLIC`

Membership roles:

- `OWNER`
- `EDITOR`
- `VIEWER`

### `POST /rooms`

Creates a room/project. `templateId` is optional.

```json
{
  "name": "Algorithms Lab",
  "description": "Python practice room",
  "visibility": "PRIVATE",
  "templateId": "algorithms-py"
}
```

### `GET /rooms?includeArchived=false`

Lists rooms where the caller is a member. The response includes project-card metadata:

- `fileCount`
- `memberCount`
- `pinned`
- `archived`
- `lastEditedById`
- `lastEditedByName`

### `GET /rooms/public`

Lists public, non-archived rooms.

### `GET /rooms/{roomId}`

Returns one room if the caller can read it.

### `POST /rooms/join/{inviteCode}`

Joins a room by invite code.

### `POST /rooms/{roomId}/pin`

Pins a room for the current user.

### `DELETE /rooms/{roomId}/pin`

Unpins a room for the current user.

### `POST /rooms/{roomId}/archive`

Archives a room. Owner only.

### `DELETE /rooms/{roomId}/archive`

Unarchives a room. Owner only.

## Templates

### `GET /templates`

Returns the shipped project template catalogue.

Response item shape:

```json
{
  "id": "algorithms-py",
  "name": "Algorithms playground",
  "description": "Classic algorithms in Python",
  "language": "python",
  "fileCount": 3
}
```

Current template IDs:

- `algorithms-py`
- `empty-java`
- `empty-python`
- `hello-react`

## Room Files and Collaboration Snapshots

### `GET /rooms/{roomId}/files`

Lists room files.

### `POST /rooms/{roomId}/files`

Creates a file.

```json
{
  "path": "src/main.py"
}
```

### `PATCH /rooms/{roomId}/files/{fileId}`

Renames a file.

```json
{
  "path": "src/app.py"
}
```

### `DELETE /rooms/{roomId}/files/{fileId}`

Deletes a file.

### `GET /rooms/{roomId}/snapshot`

Returns the persisted binary Yjs document snapshot.

### `PUT /rooms/{roomId}/snapshot`

Persists the binary Yjs document snapshot.

### `WS /ws/rooms/{roomId}?token=<accessToken>`

Binary WebSocket relay for Yjs updates and awareness. The JWT is supplied as a query parameter during the handshake.

## Imports

### `POST /rooms/{roomId}/import/github`

Imports text files from a public GitHub repository archive. Accepts full URLs or `owner/repo` shorthand.

```json
{
  "repoUrl": "octocat/Hello-World",
  "branch": "main"
}
```

The service skips binary files, oversized files, ignored directories, and invalid paths. It imports at most 200 files.

## Code Runner

### `POST /rooms/{roomId}/run`

Runs the current editor buffer in the Docker sandbox. Currently only Python is supported.

```json
{
  "language": "PYTHON",
  "code": "print('Hello, Codeleon')",
  "stdin": ""
}
```

Response:

```json
{
  "stdout": "Hello, Codeleon\n",
  "stderr": "",
  "exitCode": 0,
  "durationMs": 420,
  "timedOut": false
}
```

## AI Indexing and Chat

### `POST /rooms/{roomId}/index`

Indexes one file into Qdrant.

```json
{
  "path": "main.py",
  "text": "def fib(n): return n if n < 2 else fib(n-1) + fib(n-2)"
}
```

### `POST /rooms/{roomId}/index/all`

Indexes up to 200 files in one request. The frontend uses this before chat so the assistant sees the whole project.

```json
{
  "files": [
    {
      "path": "main.py",
      "text": "print('hello')"
    }
  ]
}
```

### `POST /rooms/{roomId}/chat`

Streams an AI answer as Server-Sent Events.

```json
{
  "query": "Why does this file fail?",
  "topK": 5,
  "history": [],
  "activeFilePath": "main.py",
  "activeFileContent": "print(name)",
  "lastRunStderr": "NameError: name 'name' is not defined"
}
```

SSE event types:

- `context`: retrieved chunks used as grounding context
- `token`: streamed assistant text chunks
- `done`: final metadata after the answer is complete
- `error`: failure details

### `GET /rooms/{roomId}/chat/history`

Returns the caller's persisted chat history for the room.

### `GET /rooms/{roomId}/chat/history?userId={userId}`

Returns another member's chat history. Only the room owner can read another user's thread.

### `GET /rooms/{roomId}/chat/threads`

Lists room members who have chat history. Owner only.

## Activity Feed

### `GET /events`

Returns recent activity across rooms the caller belongs to.

### `GET /events?since=2026-05-19T12:00:00Z`

Returns activity newer than the supplied instant. Used by the dashboard polling feed.

## Admin

Admin endpoints require an authenticated user with role `ADMIN`.

### `GET /admin/users`

Lists users.

### `GET /admin/users/{userId}`

Returns one user.

### `PATCH /admin/users/{userId}/role`

Updates a user's role.

```json
{
  "role": "ADMIN"
}
```

### `DELETE /admin/users/{userId}`

Deletes a user.

### `GET /admin/rooms`

Lists rooms for the admin dashboard.

### `DELETE /admin/rooms/{roomId}`

Deletes a room.

### `GET /admin/stats`

Returns dashboard statistics, including app counts and AI/vector-store status where available.
