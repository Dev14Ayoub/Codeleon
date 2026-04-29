# Codeleon

Codeleon is a collaborative programming platform with real-time rooms, code execution, and a local AI assistant built around RAG.

Tagline: **Code adapts. Teams thrive.**

## Modules

- `backend`: Spring Boot API with JWT authentication.
- `frontend-web`: React + Vite web client.
- `docs`: architecture, UML, and Merise documentation.
- `infra`: local infrastructure notes and future service setup.

## Local Services

```bash
docker compose up -d
```

The first local stack starts PostgreSQL 16 and Redis 7. Qdrant and Ollama will be added during the RAG milestone.

## Development

Backend:

```bash
cd backend
mvn spring-boot:run
```

Frontend:

```bash
cd frontend-web
npm install
npm run dev
```
