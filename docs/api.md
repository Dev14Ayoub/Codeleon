# Codeleon API

Base URL: `/api/v1`

## Authentication

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `GET /auth/me`

## Rooms

- `POST /rooms`
- `GET /rooms`
- `GET /rooms/public`
- `GET /rooms/{roomId}`
- `POST /rooms/join/{inviteCode}`

Rooms support two visibility modes: `PRIVATE` and `PUBLIC`. Membership roles are `OWNER`, `EDITOR`, and `VIEWER`.
