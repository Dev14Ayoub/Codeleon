# Codeleon Architecture

## First Milestone

The first milestone establishes the project foundation:

- Spring Boot backend with modular packages.
- JWT authentication with refresh tokens.
- PostgreSQL schema managed by Flyway.
- React web frontend with protected routes.
- Docker Compose for PostgreSQL and Redis.

## Module Boundaries

- `auth`: registration, login, refresh tokens, logout.
- `user`: current user profile and user persistence.
- `room`: room and membership domain model.
- `config`: security, CORS, JWT filter.
- `common`: shared exceptions and API responses.
