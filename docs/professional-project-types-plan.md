# Codeleon Professional Project Types Implementation Plan

## Summary

Create a professional project creation flow with a searchable project-type dropdown, seeded starter templates, broader Nix runner support, static web preview, and database-oriented starters.

## Progress

- [x] Phase 1: Planning doc
- [x] Phase 1: Template content foundation
- [x] Phase 2: Professional project type dropdown
- [x] Phase 3: Seeded starter projects
- [x] Phase 4: Nix runner expansion
- [x] Phase 5: Static web preview
- [x] Phase 6: Database sidecars

## Phase 1: Template Content Foundation

- Extend backend template JSON files so each file can include starter `content`.
- Add richer template metadata: `category`, `runtime`, `packageManager`, `defaultCommand`, `runnable`, `preview`, `services`, and `tags`.
- Add `GET /templates/{id}` for full template metadata and file contents.
- Keep `GET /templates` and `POST /rooms` backward compatible.

## Phase 2: Professional Project Type Dropdown

- Replace dashboard template chips with a searchable grouped dropdown.
- Groups: Web, Frontend, Backend, CLI/Scripting, Systems, Databases, Custom Nix.
- Show selected project type preview with stack, command, Nix support, preview support, and service badges.
- Preserve Local Device and GitHub import flows.

## Phase 3: Seeded Starter Projects

- Add starter contents for HTML/CSS/JS, Bootstrap, React Vite TypeScript, Python, Node, Java Maven, Spring Boot, Rust, Go, C/CMake, C++/CMake, SQLite SQL, PostgreSQL, MySQL/MariaDB, MongoDB, Redis, and custom Nix flake.
- On create, fetch the full template and save starter contents through the existing Yjs snapshot endpoint.
- Ensure created rooms open with populated files.

## Phase 4: Nix Runner Expansion

- Extend detection/default commands for Gradle, Rust/Cargo, Go, CMake, PHP/Composer, Ruby/Bundler, .NET, SQLite, and SQL-only projects.
- Add generated Nix environments for these stacks.
- Keep `flake.nix` as the universal advanced path.
- Add tests for detection, generated flakes, default commands, and Docker command construction.

## Phase 5: Static Web Preview

- Add sandboxed iframe preview for HTML/CSS/JS and Bootstrap projects.
- Preview should use room files, preserve nested paths, and refresh when files change.
- React live preview remains a follow-up; React should build/run through Nix in this pass.

## Phase 6: Database Sidecars

- Add ephemeral Docker sidecars for PostgreSQL, MySQL, MariaDB, MongoDB, and Redis project runs.
- Create an isolated Docker network per run.
- Start services, wait for health, inject connection env vars, run the project command, and clean up containers/network.
- Do not expose host ports by default.
- SQLite runs directly inside Nix without sidecars.

## Validation Checklist

- [x] Backend template catalogue tests pass.
- [x] Full template endpoint tests pass.
- [x] Project type dropdown builds.
- [x] Template creation seeds editor content.
- [x] Nix detection tests cover supported manifests.
- [x] Static preview renders HTML/CSS/Bootstrap.
- [x] Database sidecars clean up after runs.
- [x] `npm run build` passes.
- [x] Full backend `mvn test` passes.
