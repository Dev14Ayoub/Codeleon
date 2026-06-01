# Mémoire PFE — Writing Plan, Project Brief, and Ghostwriter Prompts

> Single-source briefing document for handing off the writing of the
> PFE final report to an external AI assistant (ChatGPT, Claude,
> Gemini, …). The reader of this file should be able to write the
> entire mémoire by pasting the prompts of § 7 one chapter at a time,
> attaching the listed `docs/*.md` files, and dropping the diagrams
> from § 8 and screenshots from § 9 at the indicated spots.
>
> This file replaces the older "writing plan" with a deeper structure
> that covers (a) the project's value, (b) a logical taxonomy of every
> piece of content, (c) an index of every problem encountered and how
> it was solved, and (d) the chapter prompts themselves.

---

## Table of contents

1. How to use this guide
2. **Project vision and value proposition** (read this first)
3. Content taxonomy — every topic, classified by logic
4. **Problems encountered and solutions** (the engineering journey)
5. The five thematic pillars of the report
6. Mémoire structure (chapters and page budget)
7. Per-chapter ghostwriter prompts
8. Master list of diagrams (UML, MCD, Merise, schémas)
9. Master list of screenshots
10. Bibliography skeleton
11. Workflow recommended
12. Final printing checklist

---

## 1. How to use this guide

1. Read § 2, § 3, and § 4 once — they give you the full briefing on
   the project and the engineering journey. Everything that follows
   refers back to them.
2. Pick a chapter from § 6, copy the prompt from § 7, attach the
   listed `docs/*.md` files in your AI assistant.
3. Save the AI's output as `memoire/chapitre-N.md`.
4. Insert the figures listed in § 8 and the screenshots from § 9 at
   the marked positions.
5. Assemble the chapters, reread top-to-bottom, fix continuity, print.

### Standard style preamble (paste at the top of every prompt)

```
Tu écris une section d'un mémoire de fin d'études (PFE) pour la
Licence Pro Génie Logiciel, Faculté Polydisciplinaire de Taza,
année académique 2025-2026. La langue est le français académique.
Tu utilises le « nous » de modestie. Tes phrases font moins de 25
mots en moyenne. Tu définis tout terme technique à sa première
occurrence. Tu cites les sources (frameworks, articles, RFC) en
notes de bas de page format `[^N]`. Tu n'inventes rien : si une
information n'est pas dans les documents joints, tu la marques
explicitement `[À COMPLÉTER PAR L'AUTEUR]` plutôt que de l'inventer.
Le ton est sobre et honnête : tu n'embellis pas, tu n'enjolives pas.
```

---

## 2. Project vision and value proposition

> **Read this section before writing anything else.** It is the
> material every chapter should circle back to — the *why* of the
> project. Without it, the mémoire becomes a description of code
> instead of a defense of choices.

### 2.1 The one-sentence pitch

**Codeleon is a self-hosted collaborative coding platform that lets a
small team write, run, and discuss code together — with an AI
assistant grounded in their own files — without sending a byte of
that code or that conversation to a cloud LLM provider.**

### 2.2 The five problems Codeleon solves

| Problem in the market | What people do today (and why it hurts) | Codeleon's answer |
|---|---|---|
| **Pair programming over the wire** is fragmented | Live Share on VS Code requires desktop install; Replit hosts your code; Google Docs has no language awareness | A web room with multi-cursor editing, no install, no vendor lock-in |
| **AI coding assistants leak your code** | Copilot, Cursor, Codeium send your buffer to a remote provider; even "enterprise" tiers transmit telemetry | All inference is local Ollama; nothing leaves the host |
| **Cloud LLMs cost money you don't have as a student** | $20/month minimum for GPT-4, Claude, etc. — multiplied by every collaborator | One 5 €/month VM serves the whole team with an unlimited 7 B local model |
| **Running shared code safely is hard** | "Just run my script real quick" turns into a config nightmare or a security incident | One-click Docker sandbox, `--network=none`, `--cap-drop=ALL`, time-bounded |
| **Deployment of any of the above is a project on its own** | Spinning up a public web app requires domain, TLS, WAF, abuse mitigation — all before the first feature ships | Hetzner VM + Tailscale: zero public ports, identity-based access, no domain needed |

### 2.3 What makes Codeleon different from its closest neighbours

| Competitor | Strength | Where it falls short | Where Codeleon wins |
|---|---|---|---|
| **VS Code + Live Share** | Best editor, native | Requires install on every machine; no AI in the loop; no sandbox | Web-based; AI panel built in; sandbox built in |
| **Replit** | Zero-install web IDE; built-in run; AI agent | Cloud-hosted; subscription tiers; data lives on Replit's servers | Self-hosted; one-time VM cost; data stays in your tailnet |
| **GitHub Codespaces** | VS Code in browser; mature | Per-hour billing; ties identity + storage to GitHub | Flat 5 €/month VM; identity decoupled (any OAuth provider) |
| **Cursor / Cody / Continue** | Powerful AI in the editor | Cloud LLM API key required; code sent to model provider | RAG over your own files, served by a local 7B model |
| **JupyterHub** | Multi-user notebook | No collaborative live editing; no chat between users | Real-time multi-cursor; built-in peer chat with attachments |

### 2.4 The unifying thesis (one paragraph for the introduction)

> Coding is increasingly a collaborative act. The tools that make
> collaboration smooth — Replit, Codespaces, Cursor — also make it
> *expensive* and *non-sovereign*: every keystroke and every chat
> turn flows through a third-party API, with metered cost and
> uncertain data residency. Codeleon's bet is that a small team can
> have the same experience on a 5 €/month European VM, with a local
> AI model, without surrendering their code or their bill. The
> project demonstrates that bet end-to-end: a web editor with
> CRDT-based multi-cursor, a Docker-sandboxed runner, a hybrid RAG
> assistant, an agent loop that can propose CRDT-applied patches, and
> a production deployment behind Tailscale that has zero open ports
> on the public internet.

### 2.5 What "value" looks like for each stakeholder

| Stakeholder | Value delivered |
|---|---|
| **The student team** | A workspace where they can pair-program on a homework problem, ask the assistant about their own code, run the result safely — without paying or installing anything |
| **The teaching staff** | A demo platform that does not depend on a credit-card-funded cloud account; rooms can be torn down by deleting one entity in the DB |
| **The PFE jury** | Concrete evidence of architecture maturity: every layer (frontend / API / data / AI / runtime / network) is defended with explicit trade-offs |
| **The author (Licence Pro candidate)** | Solo demonstration across the full stack — frontend, backend, AI pipeline, security operations, deployment, documentation |

---

## 3. Content taxonomy — every topic, classified by logic

Every piece of content the mémoire will discuss falls into one of
five layers. Knowing which layer something belongs to tells you
which chapter to put it in.

### 3.1 Layer 1 — Product

The user-facing surface. *What does Codeleon let me do?*

- Authentification (email/password, OAuth GitHub)
- Tableau de bord (cartes projet, templates, search/sort, activity feed)
- Workspace de room (éditeur, fichiers, run, AI panel, peer chat, output panel)
- Import de projet (local folder ou GitHub repo)
- Tableau d'administration (users, rooms, AI metrics)

→ **Chapitre 1 (problème) + Chapitre 4 (réalisation)**

### 3.2 Layer 2 — Architecture

The static structure. *How are the pieces wired?*

- Découpage frontend/backend/data/IA/sandbox
- Modèle de composants (UML)
- Modèle conceptuel de données (MCD Merise)
- Schéma logique relationnel (MLD)
- Patterns de communication (REST + JWT, SSE, WebSocket binaire)

→ **Chapitre 3 (conception)**

### 3.3 Layer 3 — Algorithms and data flows

The dynamic logic. *What happens at runtime?*

- Pipeline RAG : chunking AST → embedding → recherche vectorielle +
  BM25 → fusion RRF → assemblage de prompt → streaming SSE
- Boucle agent : tool calls, parser de fallback, gestion d'itérations
- CRDT Yjs : édition collaborative sans conflit, persistance par
  snapshot
- Two-layer sync du peer chat : Y.Array temps-réel + Postgres
  source-of-truth

→ **Chapitre 3 (conception) avec extraits dans Chapitre 4**

### 3.4 Layer 4 — Sécurité et opérations

The defense layer. *How does it stay safe and observable ?*

- Authentification : JWT HS512 + refresh tokens SHA-256, validation
  de secret au boot, OAuth2 conditionnel
- Sandbox d'exécution : `--network=none`, `--cap-drop=ALL`,
  `--security-opt=no-new-privileges`, path traversal blocking
- Posture réseau : UFW deny-by-default, fail2ban, Tailscale auth
- Observabilité : `AiMetricsService`, latency histogram, banlist
  fail2ban

→ **Chapitre 5 (déploiement et sécurité)**

### 3.5 Layer 5 — Process and documentation

The meta layer. *How was the project run ?*

- Méthodologie Scrum solo en sprints d'1 semaine
- Versionnage Git + GitHub
- Documentation continue (README + docs/*.md)
- Tests (100+ JUnit, type-check frontend)
- Roadmap et retrospectives

→ **Chapitre 1 (conduite du projet) + Annexes**

---

## 4. Problems encountered and solutions

The mémoire's chapter 4 and chapter 5 must explicitly walk the jury
through real engineering decisions — not just describe the final
state, but show *why* it became the final state. This table is the
exhaustive index. Each row links to the `docs/*.md` file that
captures the decision.

### 4.1 Deployment journey

| # | Problem | Resolution | Documented in |
|---|---|---|---|
| D-1 | The laptop is too weak and too unreliable for a defense demo (battery, WiFi, perf, Ollama 7 B fits but only just) | Provision a Hetzner CX22 VM (5 €/month) — 4 vCPU, 8 GB RAM, RGPD-friendly | `docs/deployment-problem-and-solution.md` § 3.1 |
| D-2 | AWS, GCP, Azure cost 30 USD/month minimum and force a complexity tax (VPC, IAM, ELB) | Rejected — see comparative tables | `docs/deployment-rationale.md` § 3 |
| D-3 | Render, Fly.io, Railway either sleep services or don't fit 8 GB AI workloads | Rejected | `docs/deployment-problem-and-solution.md` § 3.1 |
| D-4 | A bare VPS gets 174 SSH brute-force attempts in the first 3 hours of exposure | Lock down UFW deny-by-default + fail2ban progressive bantime | `docs/fail2ban-report.md` |
| D-5 | Public exposure with TLS would require a domain, WAF, rate limiting, on-call rotation — none of which add value for a PFE | Move to an overlay network model | `docs/deployment-problem-and-solution.md` § 3.2 |
| D-6 | OpenVPN / hand-rolled WireGuard require running a coordinator and break on Moroccan CGNAT | Reject in favour of Tailscale | `docs/deployment-rationale.md` § 5 |
| D-7 | ZeroTier is comparable to Tailscale but lacks MagicDNS HTTPS and a polished mobile app | Reject | idem |
| D-8 | Cloudflare Tunnel requires a domain and ties identity to Cloudflare | Reject | idem |
| D-9 | Google OAuth rejects HTTP redirect URIs and private CGNAT IPs | Tried Tailscale Serve + MagicDNS HTTPS, reverted (Spring forwarded-headers chain was fragile); kept Google OAuth in dev only | `docs/google-oauth-limitation.md` (if present) and the writing plan itself |
| D-10 | The first `docker compose ps` showed `WARN: POSTGRES_PASSWORD is not set` — env interpolation failing without `--env-file` | Renamed `.env.production` → `.env` (auto-loaded by Compose). Added `@PostConstruct` validation in `JwtService` that refuses to boot if `JWT_SECRET` is blank or matches the placeholder. | This document + `docs/deployment-problem-and-solution.md` § 5 |
| D-11 | The frontend container was perpetually `(unhealthy)` despite serving traffic | nginx-alpine was IPv4-only because the base image's IPv6 init script only mutates `default.conf` when its checksum matches the packaged one. Healthcheck used `localhost` which resolves to `::1`. Fix: switch the healthcheck URL to `http://127.0.0.1/`. | This document |
| D-12 | The code runner was broken in production — the backend container had no `docker` CLI and no socket access | Phase 1: install Docker CLI 26.1.4 as a static binary in the backend image, mount `/var/run/docker.sock`, join `codeleon` to the host's `docker` group via build-arg GID | This document |
| D-13 | Maven and Nix runs broke because workspace bind mounts pointed at `/tmp/...` inside the backend container — a path the host Docker daemon could not see | Phase 2: add a `workspaceBaseDir` configuration (`/opt/codeleon/runs`), bind-mounted at the SAME path on host and container, and have `Files.createTempDirectory(baseDir, ...)` create per-run dirs there | `docs/deployment-problem-and-solution.md` (and the implementation) |

### 4.2 AI and RAG

| # | Problem | Resolution | Documented in |
|---|---|---|---|
| AI-1 | qwen2.5-coder:1.5b loops on similar prompts because of small-model context blindness | Bumped chat to qwen2.5-coder:7b-instruct-q4_K_M (same model as the agent) | `.env.example` § OLLAMA_AGENT_MODEL comment |
| AI-2 | The agent model would type `{"name":"…","arguments":{…}}` as plain text instead of emitting Ollama's structured `tool_calls` JSON | Added an `extractToolCalls` fallback in `AgentLoop`: looks at the model's content, scans for `<tool_call>{…}</tool_call>` blocks AND top-level JSON objects, gates on registry-known tool names | `AgentLoop.java` (the function + tests) |
| AI-3 | The 7B model is too slow on CPU (3-5 tok/s) — long agent loops take 2-3 minutes | Accepted trade-off, documented: "local sovereign AI has this cost; the alternative is a 30 USD/month cloud LLM" | Memoire chapter 5 |
| AI-4 | Tool calling is unreliable below 7B parameters — multiple iterations of the same call, missing fields | Same fix as AI-2 (fallback parser) + bumped to 7B Q4_K_M as the floor | `.env.example`, AgentLoop tests |
| AI-5 | The first RAG version used a sliding-window text chunker that produced poor citations | Switched to AST chunking (per-symbol) with line attribution, then layered BM25 in parallel with the dense vector search, then merged with Reciprocal Rank Fusion (k=60) and an active-file boost (×1.3) | `HybridRetriever.java`, `JavaCodeChunker.java` |

### 4.3 OAuth integration

| # | Problem | Resolution | Documented in |
|---|---|---|---|
| O-1 | GitHub OAuth was registered with an HTTP IP callback `http://100.106.32.95/...` — Tailscale-only access, no public domain | Worked first time because GitHub does not validate redirect URI reachability at registration; it only matches what the app sends at runtime | This document |
| O-2 | Google OAuth refused our HTTP IP callback ("invalid_request: keep apps secure" policy) | Tried Tailscale Serve to terminate HTTPS at `https://ubuntu-8gb-hel1-1.taild1d23e.ts.net`; flow then failed because Spring's `OAuth2LoginSuccessHandler` builds `redirect_uri` from the request's `Host` header, and the forwarded-headers chain (Tailscale Serve HTTPS → Caddy HTTP → backend) lost the original `https://`. Reverted to HTTP-on-Tailscale + GitHub OAuth only. | `docs/google-oauth-limitation.md` (if kept) and this document |
| O-3 | OAuth client credentials were in `.env` but not forwarded into the backend container — `auth/providers` returned `[]` | Added `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` to the `environment:` block of `docker-compose.prod.yml` | `docker-compose.prod.yml` |
| O-4 | OAuth success redirect went to `http://localhost:5173/auth/callback` because `CORS_ALLOWED_ORIGINS` defaulted to dev | Set `CORS_ALLOWED_ORIGINS=http://100.106.32.95` in the `.env`; Spring's `OAuth2LoginSuccessHandler` uses the first entry as the SPA redirect target | `OAuth2LoginSuccessHandler.java` |
| O-5 | An access + refresh token pair was accidentally leaked in chat during testing | Revoked the refresh token directly in Postgres (`UPDATE refresh_tokens SET revoked = true WHERE user_id = …`), and added a procedure note for future incidents | This document |

### 4.4 UI / UX problems

| # | Problem | Resolution |
|---|---|---|
| UI-1 | Room workspace was visually busy: redundant menu bar, status chips on top of editor, fragmented Run buttons | Refactor: hide MenuBar entirely, move status to a thin VSCode-style bar at the bottom, unify Run actions, add panel-toggle icon buttons in the top bar |
| UI-2 | Dashboard had a horizontal scrollbar (overflow) and truncated project names ("Test_…", "moro…") | Added `overflow-x-hidden` to `<main>`, switched `truncate` to `line-clamp-2` on project card names, made the activity panel collapsible via a header toggle |
| UI-3 | Activity feed timestamps, "Owner: …" placeholder, and "No description" were too dim (WCAG contrast borderline) | Bumped colour ramps one step up (`zinc-600` → `zinc-500`, etc.) |
| UI-4 | App was unusable on the Redmi phone — sidebar hidden, top-bar buttons overflowing | Added a hamburger nav drawer to the dashboard, made the panel toggles in the room visible at every breakpoint, collapsed button labels to icon-only below `sm:` |
| UI-5 | Visual design felt cheap — no motion, no polish | Added an animated mesh-gradient backdrop (3 indigo/cyan/violet orbs, pure CSS keyframes, GPU-cheap) with two variants — `subtle` for the dashboard, `showcase` for the auth pages |
| UI-6 | AI panel was cramped on a laptop — the chat answer area was tiny, the "Index project" button took 80 px | Default sidebar width 320 → 420 px, combined index status + refresh button on one compact row, shorter labels, tighter margins; **added a fullscreen toggle (Maximize2 / Minimize2) that detaches the panel into a modal overlay** |
| UI-7 | Everything felt too big after the fix — buttons, cards, headings, padding | Dense pass: Button `h-10` → `h-9`, Input `h-11` → `h-9`, ProjectCard `p-5` → `p-4`, StatTile padding reduced, headings down one notch (`text-xl` → `text-base`), Activity feed rows from `py-3` to `py-2` |
| UI-8 | Output panel was always-visible and ate vertical space; user wanted it on demand | Added a thin "OutputStrip" toggle at the bottom of the editor area; auto-opens when a run starts; drag the border up/down to resize (clamped to 120–600 px, persisted in `localStorage`) |

### 4.5 Persistence and data

| # | Problem | Resolution | Documented in |
|---|---|---|---|
| P-1 | The first peer-chat used a Y.Array inside the room's Y.Doc — fast and free for sync, but messages would have vanished if the snapshot were ever reset | Added a Postgres-backed history (`room_peer_chat_messages` table, Flyway V9) and a two-layer sync: send writes to both, mount fetches from DB and merges with the Y.Array, dedupe by id | `docs/room-peer-chat.md` § 4 |
| P-2 | Users wanted to share images, PDFs, and code snippets in the chat | Added file upload via multipart, 5 MB cap, MIME allow-list, bytes stored inline in `BYTEA`. Frontend renders inline image preview (auth-aware blob URL) or a download chip for non-images | `docs/room-peer-chat.md` § 6-7 |
| P-3 | Frontend SPA tried `new URL("/api/v1")` and threw "Invalid URL" because the relative path needs a base | Switched to `new URL(apiBase, window.location.origin)` whenever the base is relative | `useCollabRoom.ts` |

### 4.6 Documentation drift

| # | Problem | Resolution |
|---|---|---|
| DOC-1 | `docs/NEXT-SESSION.md` was a session handoff that froze at 2026-05-01, claiming a commit was "half-done" while we had shipped 60 commits since | Deleted entirely |
| DOC-2 | `docs/progress.md` advertised "Next planned work: Backend Ollama + Qdrant clients" — done for months | Added a "Historical milestone log" banner at the top, removed the misleading "Next Planned Work" section, pointed to `ROADMAP.md` for the current status |

---

## 5. The five thematic pillars of the report

When the jury asks "what is your project really about", you should
be able to recite these five pillars in order:

| # | Pillar | One-liner | Where it shines in the report |
|---|---|---|---|
| 1 | **Sovereignty** | Code, conversation, and identity all stay on a server you own. | Chapitre 1 (problématique) + Chapitre 5 (déploiement) |
| 2 | **Real-time collaboration** | Multi-cursor editing via CRDTs over a dumb WebSocket relay. | Chapitre 3.4.3 + Chapitre 4.3 |
| 3 | **Local RAG with hybrid retrieval** | Vector + BM25 fused with RRF, AST chunking, agent loop with proposed patches. | Chapitre 3.6, 3.7 + Chapitre 4.4, 4.5 |
| 4 | **Sandboxed execution** | One-click run inside a Docker container with `--network=none`, `--cap-drop=ALL`. | Chapitre 4.6 |
| 5 | **Defensible deployment** | 5 €/month VM, zero public ports, three concentric security layers. | Chapitre 5 entire |

The conclusion (chapitre final) ties the five back to the unifying
thesis of § 2.4.

---

## 6. Mémoire structure (chapters and page budget)

```
Couverture
Dédicace
Remerciements
Résumé (FR) + Abstract (EN)
Sommaire
Liste des figures
Liste des tableaux
Liste des acronymes

Introduction générale ........................................ ~ 2 pages

Chapitre 1 — Contexte général, problématique et conduite ..... ~ 6 pages
    1.1 Présentation de l'organisme d'accueil (FP Taza)
    1.2 Cadre du projet PFE
    1.3 Problématique : coder ensemble sans céder sa souveraineté
        (synthèse des 5 problèmes du § 2.2 du writing plan)
    1.4 Objectifs et valeur (référence § 2.3 du writing plan)
    1.5 Conduite du projet (Scrum solo, sprint 4 semaines, Gantt)

Chapitre 2 — Étude technique et choix technologiques ......... ~ 8 pages
    2.1 État de l'art : éditeurs collaboratifs et IDE cloud
    2.2 Contraintes du projet (budget, RAM, sécurité, autonomie)
    2.3 Stack frontend
    2.4 Stack backend
    2.5 Moteur IA local
    2.6 Collaboration temps-réel (CRDT vs OT)
    2.7 Sandbox d'exécution
    2.8 Comparatif synthétique

Chapitre 3 — Conception du système ........................... ~ 12 pages
    3.1 Architecture globale (UML composants)
    3.2 Merise — MCD complet
    3.3 Modèle logique relationnel (MLD)
    3.4 Diagrammes de séquence
          3.4.1 Authentification JWT
          3.4.2 OAuth2 GitHub
          3.4.3 Édition collaborative Yjs
          3.4.4 RAG chat streaming SSE
          3.4.5 Mode agent avec propose_patch
          3.4.6 Peer chat avec attachment
    3.5 Modèle d'autorisations et de sécurité
    3.6 Pipeline RAG hybride
    3.7 Mode agent (tool calling + fallback parser)
    3.8 Communications temps-réel (Yjs relay)
    3.9 Persistance peer-chat (deux couches)

Chapitre 4 — Réalisation, retours d'expérience et résolutions ~ 12 pages
    4.1 Environnement de développement
    4.2 Authentification (JWT + OAuth2 GitHub)
    4.3 Workspace room (éditeur, tabs, file explorer)
    4.4 RAG chat (streaming SSE, hybrid retrieval)
    4.5 Mode agent (tool calls, propose_patch CRDT)
    4.6 Code runner (Docker sandbox, Maven, Nix)
    4.7 Peer chat avec attachments (DB-backed, 5 MB cap)
    4.8 Dashboard + activity feed + admin
    4.9 Refonte UI/UX (responsive, dense pass, animated backdrop)
    4.10 Tests (100+ JUnit, type-check frontend)
    4.11 **Synthèse des problèmes résolus** (recopier la table § 4.4 + 4.5)

Chapitre 5 — Déploiement et sécurité ......................... ~ 8 pages
    5.1 Le problème : exposer la plateforme pour la soutenance
    5.2 Étude des alternatives (hébergement + exposition)
    5.3 La solution retenue : Hetzner CX22 + Tailscale
    5.4 Mise en œuvre étape par étape
    5.5 Hardening sécurité (UFW + fail2ban + Tailscale)
    5.6 Limites assumées (OAuth Google, perf CPU, free tier)
    5.7 Incidents et résolutions (table § 4.1 du writing plan)
    5.8 Bilan opérationnel et coûts

Conclusion générale et perspectives ........................... ~ 2 pages

Bibliographie / Webographie
Annexes
    A. Captures d'écran complémentaires
    B. Extraits de code représentatifs
    C. Configurations Docker Compose et Caddyfile
    D. Schémas SQL complets (toutes les migrations Flyway)
    E. Configurations Tailscale / UFW / fail2ban
```

Total cible : **~ 50 pages** typeset.

---

## 7. Per-chapter ghostwriter prompts

> Pour chaque prompt : (1) coller le préambule de style du § 1, (2)
> joindre les fichiers source listés, (3) coller le prompt
> spécifique. L'AI écrit ; tu sauves ; tu insères figures et
> screenshots.

### 7.0 Introduction générale (~ 2 pages)

**Source files**: `README.md`, `docs/ROADMAP.md`, le § 2 de ce
writing plan (copier-coller dans le prompt).

```
[Préambule de style]

Écris l'introduction générale d'un mémoire PFE sur Codeleon. Cible :
2 pages, ~ 800 mots.

Le projet en une phrase est :
« Codeleon est une plateforme web collaborative auto-hébergée pour
coder à plusieurs, exécuter le code dans un sandbox, et discuter avec
un assistant IA local — sans qu'aucun octet ne soit envoyé à un LLM
cloud. »

Les cinq problèmes que Codeleon résout (les développer brièvement) :
1. Le pair programming en ligne est fragmenté.
2. Les assistants IA commerciaux exfiltrent le code utilisateur.
3. Les LLM cloud coûtent cher pour un étudiant.
4. Exécuter du code partagé en toute sécurité est compliqué.
5. Déployer une appli web publique demande domaine, TLS, WAF, etc.

Structure :
- Paragraphe 1 : poser le constat (3-4 phrases).
- Paragraphe 2 : présenter Codeleon comme réponse (3-4 phrases).
- Paragraphe 3 : positionnement dans le contexte PFE (FP Taza,
  Licence Pro Génie Logiciel, sprint 4 semaines).
- Paragraphe 4 : annonce du plan en 5 chapitres + conclusion.

Pas de figure dans l'introduction. Ne réfère à aucun chapitre par
son numéro — utilise plutôt « le premier chapitre », « le chapitre
suivant », etc., pour rester robuste à la renumérotation.
```

### 7.1 Chapitre 1 — Contexte général, problématique et conduite

**Source files**: `README.md`, `docs/ROADMAP.md`, le § 2 (vision)
et le § 5 (5 piliers) de ce writing plan.

```
[Préambule de style]

Écris le chapitre 1 du mémoire PFE Codeleon. Cible : 6 pages, ~ 2 500
mots.

Sections :

1.1 Présentation de l'organisme d'accueil
    Texte sur la Faculté Polydisciplinaire de Taza (mission,
    licences, encadrement). Si tu n'as pas les éléments précis,
    laisse `[À COMPLÉTER PAR L'AUTEUR]`.

1.2 Cadre du projet PFE
    Définir ce qu'est un PFE en Licence Pro. Durée (4 semaines
    sprint), modalités de soutenance, critères d'évaluation.

1.3 Problématique
    Articuler en trois axes (1-2 paragraphes chacun) :
    (a) friction technique des outils collaboratifs actuels
    (b) dépendance cloud des assistants IA modernes
    (c) coût et complexité d'un déploiement public sécurisé
    Pour chaque axe, donner un exemple concret (Replit, Cursor,
    AWS t3.medium à 30 USD/mois).

1.4 Objectifs et valeur
    Reprendre les 5 problèmes résolus de la section "value
    proposition" du writing plan (jointe). Pour chaque problème,
    cite la solution Codeleon en une phrase. Termine par une
    section "stakeholders" qui liste qui gagne quoi.

1.5 Conduite du projet
    Méthodologie Scrum solo, sprints d'1 semaine, outils (GitHub
    Issues, Git, Docker), backlog initial, gestion des risques.
    Insérer un diagramme de Gantt simplifié (figure 1.1 — placeholder
    pour l'instant).

Figures :
- Figure 1.1 : Gantt simplifié 4 semaines (à créer)
- Figure 1.2 (optionnel) : logo / organigramme FP Taza

Documents joints : README.md, ROADMAP.md, section 2 et section 5 du
writing plan.
```

### 7.2 Chapitre 2 — Étude technique et choix technologiques

**Source files**: `README.md` (section « Tools and Technologies »),
`docs/deployment-rationale.md`, `docs/deployment-problem-and-solution.md`,
`docs/architecture.md`.

```
[Préambule de style]

Écris le chapitre 2 du mémoire. Cible : 8 pages, ~ 3 500 mots.

Sections :

2.1 État de l'art — comparer 5 plateformes :
    VS Code + Live Share, Replit, GitHub Codespaces, Cursor,
    JupyterHub. Pour chacune : strengths, weaknesses, et le besoin
    Codeleon qu'elle ne couvre PAS. Conclus avec un tableau
    récapitulatif (Tableau 2.1).

2.2 Contraintes du projet — synthèse :
    budget ≤ 10 €/mois, RAM ≥ 8 GB, opérateur solo, RGPD,
    iteration speed 4 semaines, défense le jour J.

2.3 Stack frontend — pour CHAQUE composant (React 18, Vite 5,
    TypeScript 5.4, Tailwind 3, CodeMirror 6, Yjs, Zustand,
    React Query, Radix UI, Framer Motion, lucide-react, axios),
    explique en 2-3 phrases le rôle ET la raison du choix vs son
    alternative la plus crédible.

2.4 Stack backend — idem (Spring Boot 3.2, JDK 21, Spring Security 6,
    Spring Data JPA, Flyway, Postgres 16, Redis 7, JJWT, Lombok).

2.5 Moteur IA local — Ollama (vs vllm, llama.cpp), Qdrant (vs FAISS,
    pgvector, ChromaDB), Lucene (pour BM25), justification du choix
    de qwen2.5-coder:7b-instruct-q4_K_M comme modèle par défaut.

2.6 Collaboration temps-réel — Yjs CRDT vs Operational Transform.
    Explication didactique en 1 paragraphe + figure simple
    (figure 2.3 placeholder).

2.7 Sandbox d'exécution — Docker run avec --cap-drop=ALL,
    --network=none. Comparer brièvement à gVisor, Firecracker.

2.8 Comparatif synthétique — un grand tableau récapitulatif
    (Tableau 2.2).

Figures :
- Figure 2.1 : matrice des plateformes concurrentes
- Figure 2.2 : pile technologique en couches
- Figure 2.3 : CRDT vs OT illustration

Documents joints : README.md, deployment-rationale.md,
deployment-problem-and-solution.md, architecture.md.
```

### 7.3 Chapitre 3 — Conception du système

**Source files**: `docs/uml/component-diagram.md`,
`docs/uml/sequence-rag-chat.md`, `docs/uml/sequence-realtime-collab.md`,
`docs/merise/mcd.md`, `docs/room-peer-chat.md` (sections 4-5),
`docs/fail2ban-report.md` (section 1).

```
[Préambule de style]

Écris le chapitre 3 — Conception. Cible : 12 pages, ~ 5 000 mots.
C'est le chapitre le plus technique du mémoire ; les schémas doivent
dominer le texte. Pour chaque diagramme, écris une légende de 2-3
phrases sous la figure.

Sections :

3.1 Architecture globale — Figure 3.1 (diagramme de composants UML
    de docs/uml/component-diagram.md). Texte présentant les couches
    Client / Caddy / Backend / Data / IA / Sandbox.

3.2 Merise — MCD — Figure 3.2 (extrait de docs/merise/mcd.md, 8
    entités). Tableau 3.1 (cardinalités), Tableau 3.2 (contraintes
    d'intégrité). Justifier la modélisation (snapshot Y.Doc au niveau
    Room, message peer chat sans table d'attachment séparée, etc.).

3.3 Modèle logique relationnel — transformation MCD → MLD avec FK
    explicites. Le schéma SQL complet va en annexe D.

3.4 Diagrammes de séquence — six scénarios critiques :
    3.4.1 Inscription + login JWT (Figure 3.3, à créer)
    3.4.2 OAuth2 GitHub (Figure 3.4, à créer)
    3.4.3 Édition Yjs (Figure 3.5, depuis sequence-realtime-collab.md)
    3.4.4 RAG chat SSE (Figure 3.6, depuis sequence-rag-chat.md)
    3.4.5 Mode agent + propose_patch (Figure 3.7, à créer)
    3.4.6 Peer chat avec attachment (Figure 3.8, depuis
          room-peer-chat.md § 4)

3.5 Modèle de sécurité — Figure 3.9 (trois couches : UFW + fail2ban
    + Tailscale). Tableau 3.3 (menaces × mitigations, depuis
    fail2ban-report.md § 1).

3.6 Pipeline RAG hybride — Figure 3.10 (flowchart : chunking AST →
    embed → search dense + BM25 → RRF → prompt). Équation 3.1 : la
    formule RRF (score(d) = Σ 1/(k + rank_r(d)), k=60). Justifier
    k=60 (Cormack et al., 2009).

3.7 Mode agent — Figure 3.11 (diagramme d'activité de AgentLoop.run).
    Expliquer le fallback parser : pourquoi qwen 7B Q4 n'émet pas
    toujours le format <tool_call>{…}</tool_call>, comment on parse
    le contenu pour le récupérer.

3.8 Communications temps-réel — backend dumb relay, pas de Y.Doc
    serveur, broadcasting binaire. Figure 3.12.

3.9 Persistance peer-chat — Figure 3.13 (two-layer sync : Y.Array
    live + Postgres source-of-truth). Tableau récap des flux send /
    mount / receive.

Documents joints : component-diagram.md, sequence-rag-chat.md,
sequence-realtime-collab.md, mcd.md, room-peer-chat.md,
fail2ban-report.md.
```

### 7.4 Chapitre 4 — Réalisation, retours d'expérience et résolutions

**Source files**: `README.md`, `docs/room-peer-chat.md`,
`docs/progress.md`, ce writing plan (section 4 — problems index).

```
[Préambule de style]

Écris le chapitre 4 — Réalisation. Cible : 12 pages, ~ 4 800 mots.
Ce chapitre montre le « comment » avec des captures d'écran, des
extraits de code (max 15 lignes par snippet), et — important — une
section finale qui recense les problèmes rencontrés et leur
résolution.

Pour chaque sous-section de 4.2 à 4.9, suis le format :
1. Paragraphe d'introduction (ce qu'on a réalisé)
2. Une capture d'écran annotée
3. Un extrait de code représentatif (méthode/composant clé)
4. Paragraphe de justification (pourquoi cette implémentation)

Sections :
4.1 Environnement de développement — IDE (IntelliJ + VS Code), Docker
    Desktop + WSL2, scripts PowerShell start.ps1/stop.ps1.
4.2 Authentification — JWT HS512 + refresh token SHA-256, OAuth2
    GitHub. Capture : login.png. Code : JwtService.generateAccessToken.
4.3 Workspace room — éditeur CodeMirror, tabs, file explorer.
    Capture : room.png. Code : useCollabRoom hook.
4.4 RAG chat — pipeline indexation, streaming SSE. Capture : chat.png.
    Code : RoomChatService.streamChat extrait.
4.5 Mode agent — boucle d'outils, propose_patch CRDT. Capture :
    agent.png. Code : AgentLoop.run + extractToolCalls.
4.6 Code runner Docker + Maven + Nix — sandbox flags. Capture :
    output.png. Code : DockerCodeRunnerService.runPython flags.
4.7 Peer chat avec attachments — Y.Array + Postgres, upload multipart,
    inline image preview. Captures : people.png, people-image.png.
    Code : RoomPeerChatService.postWithFile.
4.8 Dashboard + activity feed + admin — cards, search, admin metrics.
    Captures : dashboard.png, admin-ai-metrics.png.
4.9 Refonte UI/UX — responsive, dense pass, animated backdrop, output
    panel collapsible, AI panel fullscreen toggle. Captures :
    dashboard-mobile.png et un avant/après si possible.
4.10 Tests — pyramide (100+ tests JUnit backend, type-check frontend,
    smoke tests prod). Citer 3-4 tests phares (RRF fusion,
    Bm25Searcher races, AgentLoop extractToolCalls, JwtService
    validateSecret).
4.11 Synthèse des problèmes résolus
    Cette section est CENTRALE pour la soutenance. Reprends les
    tables 4.2 (AI), 4.3 (OAuth), 4.4 (UI/UX), 4.5 (Persistence),
    4.6 (Doc drift) du writing plan joint. Pour chaque problème,
    présente en une ligne : symptôme observé → cause racine →
    solution appliquée. Cite le commit ou le fichier source.

Documents joints : README.md, room-peer-chat.md, progress.md, et
ce writing plan (section 4 « Problems encountered and solutions »).
```

### 7.5 Chapitre 5 — Déploiement et sécurité

**Source files**: `docs/deployment-problem-and-solution.md`,
`docs/deployment-rationale.md`, `docs/fail2ban-report.md`,
ce writing plan (section 4.1 — deployment journey).

```
[Préambule de style]

Écris le chapitre 5 — Déploiement et sécurité. Cible : 8 pages, ~
3 400 mots.

Sections :

5.1 Le problème — résumer en 1 page :
    Pourquoi déployer ? La soutenance demande une URL stable. Le
    laptop n'est pas fiable. Citer le chiffre : « 174 tentatives
    SSH en 3 heures dès la première heure d'exposition publique ».

5.2 Étude des alternatives — hébergement ET exposition.
    Tableau 5.1 : alternatives hébergement (laptop, AWS, Render,
    Fly, Railway, OVH, Hetzner). Tableau 5.2 : alternatives
    exposition (public TLS, IP allowlist, bastion, OpenVPN,
    ZeroTier, Cloudflare Tunnel, Tailscale).
    Pour chaque ligne, 1-2 phrases de raison du rejet.

5.3 La solution retenue — Hetzner CX22 + Tailscale.
    Justifier en trois axes : coût (5€/mois), souveraineté (RGPD UE),
    surface d'attaque (un seul port UDP).
    Figure 5.1 : architecture de déploiement.

5.4 Mise en œuvre étape par étape :
    1. provision VM Hetzner
    2. install Tailscale + tailnet join
    3. UFW deny-by-default + fail2ban setup
    4. clone repo + Dockerfile backend + Dockerfile frontend
    5. Caddyfile reverse proxy
    6. docker-compose.prod.yml
    7. .env avec secrets générés via openssl
    8. pull modèles Ollama
    9. bring-up de la stack
    Figure 5.2 : flux d'une requête (browser → Tailscale →
    Caddy → backend → data).

5.5 Hardening sécurité —
    UFW : deny-by-default, allow 41641/udp WireGuard + tailscale0
    interface. Capture docker-ps.png pour montrer "nothing on the
    public IP".
    fail2ban : sshd jail, bantime progressif, log d'incidents.
    Capture fail2ban-status.png.
    Tailscale : MFA Google/GitHub, device approval, ACLs.
    Tableau 5.3 : matrice menaces × mitigations.

5.6 Limites assumées :
    a) OAuth Google indisponible en prod (raconter l'épisode
       Tailscale Serve qu'on a essayé puis reverted)
    b) Performance CPU (3-5 tok/s sur le 7B Q4)
    c) Free tier Tailscale (3 users / 100 devices)

5.7 Incidents et résolutions — recopier la table 4.1 (deployment
    journey) du writing plan : D-10 env-file warn, D-11 frontend
    unhealthy IPv6, D-12 code runner cassé, D-13 Maven bind mount.
    Pour chaque incident, raconte symptôme → diagnostic → fix.

5.8 Bilan opérationnel et coûts :
    Tableau coût (5€/mois vs ~40 USD AWS), métriques opérationnelles
    (20 IPs bannies historiquement, 0 tentative depuis lockdown,
    100% uptime depuis la migration).

Documents joints : deployment-problem-and-solution.md (principal),
deployment-rationale.md, fail2ban-report.md, section 4.1 du writing
plan.
```

### 7.6 Conclusion générale et perspectives

**Source files**: `README.md` (section Roadmap), `docs/ROADMAP.md`,
section 2 du writing plan (project vision).

```
[Préambule de style]

Écris la conclusion générale du mémoire. Cible : 2 pages, ~ 800 mots.

Structure :
- Synthèse des contributions (3-4 paragraphes) :
    rappeler les 5 piliers (souveraineté, collab temps-réel, RAG
    hybride local, sandbox sécurisé, déploiement défendable). Pour
    chaque pilier, citer 1 livrable concret.
- Limites du travail (1-2 paragraphes) :
    OAuth Google désactivé en prod, modèle 7B sur CPU = inférence
    lente, hiérarchie de dossiers absente du file explorer, mobile
    app native non livrée.
- Perspectives (2-3 paragraphes) :
    support GPU, indexation continue avec debounce smart, mode
    "playground" public, exposition publique via domaine + Caddy +
    Let's Encrypt, RGPD-compliance audit, mode "classe" pour la
    pédagogie.
- Mot de la fin :
    ce que le projet m'a appris, gratitude envers l'encadrement,
    transition vers la soutenance.

Pas de figure dans la conclusion.

Documents joints : README.md (section Roadmap), ROADMAP.md, section
2 du writing plan.
```

---

## 8. Master diagrams list

Toutes les figures sont à recenser dans la « Liste des figures » du
mémoire. Les figures issues de `docs/` sont déjà en Mermaid ; pour la
version finale (Word ou PDF) exporte-les en PNG via mermaid.live ou
la CLI mermaid-cli.

| # | Figure | Source | Insérer dans | Format |
|---|---|---|---|---|
| 1.1 | Logo / organigramme FP Taza | [À FOURNIR] | Ch 1.1 | PNG |
| 1.2 | Gantt simplifié sprint 4 semaines | [À CRÉER] | Ch 1.5 | PNG (Mermaid gantt) |
| 2.1 | Matrice plateformes concurrentes | [À CRÉER] | Ch 2.1 | PNG |
| 2.2 | Pile technologique en couches | [À CRÉER] | Ch 2.2 | PNG (Excalidraw) |
| 2.3 | CRDT vs OT illustration | [À CRÉER] | Ch 2.6 | PNG |
| 3.1 | Diagramme de composants UML | `docs/uml/component-diagram.md` | Ch 3.1 | Mermaid → PNG |
| 3.2 | MCD Merise complet | `docs/merise/mcd.md` | Ch 3.2 | Mermaid erDiagram → PNG |
| 3.3 | Séquence inscription + JWT | [À CRÉER depuis AuthService] | Ch 3.4.1 | Mermaid sequenceDiagram |
| 3.4 | Séquence OAuth2 GitHub | [À CRÉER depuis OAuth2LoginSuccessHandler] | Ch 3.4.2 | Mermaid sequenceDiagram |
| 3.5 | Séquence édition collab Yjs | `docs/uml/sequence-realtime-collab.md` | Ch 3.4.3 | Mermaid → PNG |
| 3.6 | Séquence RAG chat SSE | `docs/uml/sequence-rag-chat.md` | Ch 3.4.4 | Mermaid → PNG |
| 3.7 | Séquence agent + propose_patch | [À CRÉER depuis AgentLoop] | Ch 3.4.5 | Mermaid sequenceDiagram |
| 3.8 | Séquence peer chat avec fichier | `docs/room-peer-chat.md` § 4 | Ch 3.4.6 | Mermaid sequenceDiagram |
| 3.9 | Trois couches sécurité | `docs/fail2ban-report.md` § 1 | Ch 3.5 | PNG schématique |
| 3.10 | Pipeline RAG hybride | [À CRÉER] | Ch 3.6 | Mermaid flowchart |
| 3.11 | Diagramme d'activité AgentLoop | [À CRÉER depuis AgentLoop.run] | Ch 3.7 | Mermaid stateDiagram-v2 |
| 3.12 | Relay WebSocket Yjs (backend dumb) | `docs/uml/sequence-realtime-collab.md` simplifié | Ch 3.8 | Mermaid sequenceDiagram |
| 3.13 | Two-layer sync peer chat | `docs/room-peer-chat.md` § 4 | Ch 3.9 | Mermaid flowchart |
| 5.1 | Architecture déploiement Hetzner + Tailscale | `docs/deployment-problem-and-solution.md` § 4 | Ch 5.3 | ASCII ou PNG |
| 5.2 | Flux d'une requête (browser → Caddy → backend) | [À CRÉER] | Ch 5.4 | Mermaid flowchart |

---

## 9. Master screenshot list

Stocker tous les PNG dans `docs/screenshots/`. Le guide de capture
détaillé est dans `docs/screenshots/README.md`. Convention de caption :
`Figure N.M : <titre court>` — description en une phrase.

| Fichier | Description | Insérer dans |
|---|---|---|
| `landing.png` | Page d'accueil avec backdrop animé | Ch 1.4 (valeur) ou Ch 4.2 |
| `login.png` | Page de connexion avec OAuth GitHub + email/password | Ch 4.2 |
| `signup.png` | Page d'inscription | Annexe A |
| `dashboard.png` | Dashboard avec stats, project cards, activity feed | Ch 4.8 |
| `dashboard-mobile.png` | Dashboard Redmi avec hamburger nav | Ch 4.9 (responsive) |
| `room.png` | Workspace room (éditeur + file explorer + AI panel) | Ch 4.3 |
| `room-multi-cursor.png` | 2 curseurs visibles (collab live) | Ch 4.3 |
| `chat.png` | AI chat avec réponse streamée + context drawer | Ch 4.4 |
| `chat-fullscreen.png` | AI panel en mode fullscreen modal | Ch 4.9 |
| `agent.png` | AI panel en mode agent avec tool calls + propose_patch | Ch 4.5 |
| `output.png` | Output panel après un run Python (exit 0) | Ch 4.6 |
| `output-collapsed.png` | Output strip en mode replié | Ch 4.9 |
| `output-maven.png` | Output panel après un run Maven | Annexe |
| `people.png` | Tab People avec liste participants + room chat actif | Ch 4.7 |
| `people-image.png` | Échange peer chat avec image partagée inline | Ch 4.7 |
| `people-file.png` | Échange peer chat avec PDF/ZIP partagé | Ch 4.7 |
| `admin-users.png` | Admin → tab Users avec dropdown role | Ch 4.8 |
| `admin-rooms.png` | Admin → tab Rooms | Ch 4.8 |
| `admin-stats.png` | Admin → tab Stats | Ch 4.8 |
| `admin-ai-metrics.png` | Admin → tab AI metrics avec latency histogram | Ch 4.5 |
| `tailscale-admin.png` | Console Tailscale montrant le tailnet | Ch 5.3 |
| `hetzner-cx22.png` | Page Hetzner Cloud avec la VM | Ch 5.3 |
| `docker-ps.png` | `docker compose ps` sur le serveur (tous healthy) | Ch 5.5 |
| `fail2ban-status.png` | `fail2ban-client status sshd` | Ch 5.5 |
| `nmap-public-ip.png` | `nmap -Pn 89.167.65.180` (filtered partout sauf 41641/udp) | Ch 5.5 |

---

## 10. Bibliographie skeleton

Format BibTeX ou liste numérotée selon ce que ta fac exige. À enrichir
au fur et à mesure de l'écriture.

```
[1]  M. Shapiro et al., "Conflict-free Replicated Data Types",
     INRIA, 2011. https://hal.inria.fr/inria-00609399v1
[2]  G. V. Cormack, C. L. A. Clarke, S. Buettcher, "Reciprocal rank
     fusion outperforms Condorcet and individual rank learning
     methods", SIGIR 2009, ACM, pp. 758-759.
[3]  S. E. Robertson, S. Walker, "Some simple effective approximations
     to the 2-Poisson model for probabilistic weighted retrieval",
     SIGIR 1994.
[4]  Tailscale Inc., "How Tailscale works",
     https://tailscale.com/blog/how-tailscale-works
[5]  Tailscale, "MagicDNS and HTTPS for ts.net",
     https://tailscale.com/kb/1153/enabling-https
[6]  Spring Security Reference,
     https://docs.spring.io/spring-security/reference/
[7]  Ollama project, https://ollama.com/library/qwen2.5-coder
[8]  Qdrant documentation, https://qdrant.tech/documentation/
[9]  Yjs documentation,
     https://docs.yjs.dev/api/about-shared-types
[10] Hetzner Cloud, "CX-series specifications",
     https://www.hetzner.com/cloud
[11] IETF, "JSON Web Token (JWT)", RFC 7519, 2015,
     https://datatracker.ietf.org/doc/html/rfc7519
[12] D. Engelbart, J. Sutherland, R. Newman et al., "Operational
     Transformation: A Survey", ACM Computing Surveys, 2017.
[13] OWASP Foundation, "OWASP Top Ten 2021",
     https://owasp.org/Top10/
[14] WireGuard, "Cryptographic Network Protocol",
     https://www.wireguard.com/protocol/
[15] Replit, "Multiplayer Repls",
     https://docs.replit.com/teams/multiplayer
[16] GitHub Docs, "About GitHub Codespaces",
     https://docs.github.com/codespaces
[17] Anthropic, "Claude Code Documentation",
     https://docs.claude.com/en/docs/claude-code
```

---

## 11. Workflow recommandé

1. **Préparer les artefacts** (1 journée)
   - Capturer les screenshots du § 9 (Chrome, viewport 1920×1080).
   - Exporter les diagrammes Mermaid en PNG (mermaid.live, copier
     le code, télécharger l'export).
   - Compléter les `[À COMPLÉTER PAR L'AUTEUR]` (info FP Taza,
     encadrant, etc.).
2. **Écrire chapitre par chapitre** (~ 1 jour par chapitre)
   - Coller le préambule de style (§ 1) + le prompt du chapitre (§ 7).
   - Joindre les fichiers sources listés dans le prompt.
   - Sauver le brouillon comme `memoire/chapitre-N.md`.
   - Insérer les figures et screenshots aux positions marquées.
   - Relire et corriger.
3. **Assembler** (1 journée)
   - Concaténer tous les chapitres dans Word ou un projet LaTeX
     (Overleaf recommandé pour la mise en page académique).
   - Générer la table des matières, la liste des figures, la liste
     des tableaux, la liste des acronymes.
   - Vérifier toutes les références croisées (Figure 3.5, Tableau 5.2,
     etc.).
   - Relecture finale par un proche (orthographe, fluidité).
4. **Soutenance** (jour J)
   - Slides : 15-20 max, palette Codeleon (indigo / cyan / violet).
   - Démo : URL `http://100.106.32.95` depuis le laptop (Tailscale
     actif), scénario scripté répété 3 fois.
   - Backup video 5 min au cas où le réseau du venue échoue.

---

## 12. Final printing checklist

- [ ] Page de garde avec logo FP Taza, photo, dates, nom du jury
- [ ] Dédicace
- [ ] Remerciements (encadrant, jury, famille, AI ghostwriter
       acknowledged si tu choisis de le mentionner)
- [ ] Résumé FR + abstract EN (½ page chacun)
- [ ] Sommaire avec numéros de page corrects
- [ ] Liste des figures (Figure 1.1, 1.2, …, 5.2)
- [ ] Liste des tableaux (Tableau 2.1, 2.2, 3.1, …)
- [ ] Liste des acronymes : PFE, RAG, CRDT, SSE, JWT, OAuth, MIME,
       RGPD, UFW, MFA, BM25, RRF, AST, CRDT, MCD, MLD, UML
- [ ] Tous les screenshots de § 9 intégrés avec captions
- [ ] Toutes les figures de § 8 intégrées avec captions
- [ ] Bibliographie complète (§ 10 enrichie)
- [ ] Annexes A à E remplies (captures complémentaires, code,
       configs, SQL)
- [ ] Pagination cohérente
- [ ] Reliure / impression finale
- [ ] PDF de secours sur clé USB (et sur Google Drive / Dropbox)
- [ ] Backup video de la démo enregistré
- [ ] Slides exportées en PDF (pas seulement en .pptx ou .key)
- [ ] Wifi du venue testé en amont (au moins ping
       100.106.32.95 depuis ton laptop sur le tailnet)

---

## 13. Final note for the ghostwriter AI

Le projet Codeleon a été développé par un seul étudiant en 4 semaines
intenses, avec l'assistance d'un AI pair-programmer (Claude Code).
Cette assistance est mentionnée dans les commits via `Co-Authored-By`
et peut être citée dans les remerciements ou la section
« Conduite du projet » du chapitre 1. Le ton du mémoire reste celui de
l'étudiant — sobre, honnête, qui défend ses choix sans les survendre.
Si tu écris une phrase qui sonne marketing (« révolutionnaire »,
« incroyable », « unique au monde »), supprime-la et écris la version
factuelle.
