# Mémoire PFE — Writing Plan and Ghostwriter Prompts

> Operational guide for handing off the writing of the PFE report to
> another AI assistant (ChatGPT, Claude, Gemini, …). Each chapter has
> a self-contained French-language prompt you can paste verbatim,
> a list of source `docs/*.md` files to attach so the assistant works
> from your own real documentation, the diagrams to insert at specific
> points, and the screenshots to place with captions.
>
> **How to use this file**:
> 1. Read § 1 to pick a target page count and tone.
> 2. For each chapter (§ 3), copy the prompt, attach the listed source
>    files, and run it. Save the output as `chapitre-N.md`.
> 3. Assemble the chapters, insert the diagrams from § 4 and the
>    screenshots from § 5 in the indicated spots.
> 4. Read top-to-bottom, fix style / continuity, and you have a
>    mémoire ready for the soutenance.

---

## 1. Scope and ground rules

| Item | Value |
|---|---|
| Target language | French (PFE en Licence Pro Génie Logiciel, FP Taza) |
| Target length | ~ 50 pages typeset (A4, Computer Modern 11pt, marges 2.5cm). Equivalent: ~ 18 000 – 22 000 mots. |
| Tone | Académique mais pas pompeux. « Nous » de modestie. Phrases courtes (≤ 25 mots). Pas de jargon non défini. |
| Structure | Plan classique PFE : page de garde → remerciements → sommaire → listes des figures et tableaux → introduction → 5 chapitres → conclusion → bibliographie → annexes |
| Citations | Footnotes en notes de bas de page (ou format `[1]` style auteur-date selon ce que ta fac exige) |
| Diagrammes | Mermaid pour l'inclusion dans le `.md` source ; exporter en PNG si la version finale est en Word/PDF |
| Code | Snippets en `\begin{lstlisting}` (LaTeX) ou bloc fenced Markdown, sans dépasser 20 lignes par snippet |

### Style consignes globales à donner systématiquement à l'AI

Ajoute ce préambule à chaque prompt :

> Tu écris une section d'un mémoire de fin d'études (PFE) pour la
> Licence Pro Génie Logiciel, Faculté Polydisciplinaire de Taza,
> année 2025-2026. La langue est le français académique. Tu utilises
> le « nous » de modestie. Tes phrases font moins de 25 mots. Tu
> définis tout terme technique à sa première occurrence. Tu cites les
> sources (frameworks, articles, RFC) en notes de bas de page format
> `[^N]`. Tu n'inventes rien : si une information n'est pas dans les
> documents joints, tu la marques `[À COMPLÉTER]`.

---

## 2. Mémoire structure (table of contents)

```
Couverture
Dédicace
Remerciements
Résumé (FR)
Abstract (EN)
Sommaire
Liste des figures
Liste des tableaux
Liste des acronymes

Introduction générale .......................................... ~ 2 pages

Chapitre 1 — Contexte général et problématique ................. ~ 6 pages
    1.1 Présentation de l'organisme d'accueil (FP Taza)
    1.2 Cadre du projet PFE
    1.3 Problématique : coder ensemble sans céder sa souveraineté
    1.4 Objectifs du projet
    1.5 Conduite du projet (Scrum solo, sprint 4 semaines)

Chapitre 2 — Étude technique et choix technologiques ........... ~ 8 pages
    2.1 État de l'art : éditeurs collaboratifs (Replit, Codespaces, …)
    2.2 Architecture cible et contraintes
    2.3 Choix de la stack frontend (React + Vite + CodeMirror + Yjs)
    2.4 Choix de la stack backend (Spring Boot + JDK 21)
    2.5 Choix du moteur IA local (Ollama + Qdrant + Lucene)
    2.6 Choix de la collaboration temps-réel (Yjs CRDT vs OT)
    2.7 Choix de la sandbox d'exécution (Docker + cap-drop)
    2.8 Comparatif synthétique (tableau)

Chapitre 3 — Conception du système ............................ ~ 12 pages
    3.1 Architecture globale (diagramme de composants UML)
    3.2 Modèle conceptuel de données — Merise MCD
    3.3 Modèle logique relationnel (MLD)
    3.4 Diagrammes de séquence (auth, collab, RAG, agent, peer chat)
    3.5 Modèle d'autorisations et de sécurité
    3.6 Pipeline RAG hybride (chunking AST, vector + BM25, RRF)
    3.7 Mode agent (tool calling + propose_patch)
    3.8 Communications temps-réel (Yjs + WebSocket)
    3.9 Persistance peer-chat (deux couches Y.Array + Postgres)

Chapitre 4 — Réalisation ...................................... ~ 10 pages
    4.1 Environnement de développement
    4.2 Implémentation : authentification (JWT + OAuth2)
    4.3 Implémentation : workspace room (éditeur + tabs + file explorer)
    4.4 Implémentation : RAG chat (SSE streaming)
    4.5 Implémentation : agent mode (tool calls)
    4.6 Implémentation : code runner Docker + Maven + Nix
    4.7 Implémentation : peer chat avec attachments
    4.8 Implémentation : dashboard + activity feed + admin
    4.9 Tests (100+ tests JUnit, type-check frontend)

Chapitre 5 — Déploiement et sécurité .......................... ~ 7 pages
    5.1 Problème de déploiement (résumé étapes essayées)
    5.2 Choix : Hetzner + Tailscale (justification du modèle zero-trust)
    5.3 Stack Docker Compose en production (Caddy reverse proxy)
    5.4 Hardening : UFW deny-by-default + fail2ban + Tailscale auth
    5.5 Secrets management (.env + validation au boot)
    5.6 Limites OAuth2 et choix de désactiver Google en prod
    5.7 Bilan opérationnel (174 fails SSH bloqués, etc.)

Conclusion générale ........................................... ~ 2 pages
    Synthèse des contributions
    Limites du travail
    Perspectives d'évolution

Bibliographie / Webographie
Annexes
    A. Captures d'écran complémentaires
    B. Extraits de code représentatifs
    C. Configurations Docker Compose et Caddyfile
    D. Schémas SQL complets (toutes les migrations Flyway)
```

---

## 3. Per-chapter prompts and source attachments

### Introduction générale

**Objective**: Mettre en contexte le projet en 2 pages, donner envie de lire la suite, annoncer le plan.

**Source files to attach**:
- `README.md`
- `docs/ROADMAP.md`

**Prompt to give to the AI**:

```text
[Préambule de style]

Écris l'introduction générale d'un mémoire PFE sur Codeleon, une
plateforme web collaborative de programmation avec assistant IA local.
Cible : 2 pages, environ 800 mots.

Structure attendue :
- Paragraphe 1 (3-4 phrases) : poser le constat — coder ensemble est
  devenu courant, mais la plupart des outils (Replit, Codespaces,
  Cursor) délèguent le code et les conversations à des LLMs cloud
  privés. Souveraineté et coût en pâtissent.
- Paragraphe 2 (3-4 phrases) : présenter Codeleon comme une réponse —
  self-hosted, RAG local sur Ollama, sandbox d'exécution, édition
  collaborative temps-réel.
- Paragraphe 3 (2-3 phrases) : positionner dans le contexte PFE
  (Licence Pro Génie Logiciel, FP Taza, 2025-2026).
- Paragraphe 4 : annoncer le plan en 5 chapitres.

Documents joints : README.md, ROADMAP.md.
Pas de figure dans l'introduction.
```

---

### Chapitre 1 — Contexte général et problématique

**Objective**: Présenter l'organisme d'accueil, le cadre PFE, et la problématique. ~ 6 pages.

**Source files to attach**:
- `README.md` (section « Why "Codeleon"? » et description)
- `docs/deployment-problem-and-solution.md` (sections 1 et 2 pour la problématique de déploiement)

**Prompt**:

```text
[Préambule de style]

Écris le chapitre 1 du mémoire PFE sur Codeleon. Cible : 6 pages,
environ 2 500 mots.

Sections à couvrir :
1.1 Présentation de l'organisme d'accueil — Faculté Polydisciplinaire
    de Taza, Licence Pro Génie Logiciel. Mission de formation,
    spécialités, encadrement académique.  [À COMPLÉTER] avec les
    informations officielles que je te fournirai (nom du tuteur, etc.)
1.2 Cadre du projet PFE — qu'est-ce qu'un PFE en Licence Pro,
    durée (4 semaines sprint), critères d'évaluation, livrables.
1.3 Problématique — articuler en trois axes :
      (a) friction technique des outils collaboratifs actuels
      (b) dépendance cloud des assistants IA modernes
      (c) coût et complexité d'un déploiement public sécurisé
1.4 Objectifs — fonctionnels (édition multi-curseur, sandbox, AI),
    non-fonctionnels (RGPD, sovereign, budget étudiant 5€/mois,
    posture sécurité).
1.5 Conduite du projet — méthodologie Scrum solo, sprints d'1 semaine,
    burndown du backlog, outil de gestion (GitHub Issues, etc.).

Inclus deux figures (placeholders) :
- Figure 1.1 : logo / organigramme de l'organisme d'accueil
- Figure 1.2 : diagramme de Gantt simplifié du sprint 4 semaines

Documents joints : README.md, deployment-problem-and-solution.md.
```

---

### Chapitre 2 — Étude technique et choix technologiques

**Objective**: État de l'art + justification des choix de stack. ~ 8 pages.

**Source files to attach**:
- `README.md` (section « Tools and Technologies »)
- `docs/deployment-rationale.md`
- `docs/deployment-problem-and-solution.md` (section 3 sur les alternatives)
- `docs/architecture.md`

**Prompt**:

```text
[Préambule de style]

Écris le chapitre 2 du mémoire PFE sur Codeleon. Cible : 8 pages,
environ 3 500 mots.

Sections :
2.1 État de l'art — passer en revue les plateformes existantes :
      • VSCode + Live Share (édition collaborative côté local)
      • Replit (cloud-first, sandbox éphémère)
      • GitHub Codespaces (VS Code dans le navigateur)
      • Cursor (IDE assisté par LLM, mais cloud)
    Pour chacune : strengths, weaknesses, et quel besoin de Codeleon
    elle ne couvre PAS (souveraineté, prix étudiant, AI local).
2.2 Architecture cible et contraintes — résumer les contraintes
    fonctionnelles et non-fonctionnelles du chapitre 1 sous forme de
    tableau.
2.3 Stack frontend — pour CHAQUE composant (React, Vite, TypeScript,
    Tailwind, CodeMirror 6, Yjs, Zustand, React Query, Radix UI),
    explique en 2-3 phrases le rôle ET la raison du choix vs son
    alternative la plus crédible.
2.4 Stack backend — idem pour Spring Boot 3.2, JDK 21, JPA + Hibernate,
    Flyway, Postgres 16, Redis 7.
2.5 Moteur IA local — Ollama (vs vllm, llama.cpp), Qdrant (vs FAISS,
    pgvector, ChromaDB), Lucene (pour BM25 in-memory).
2.6 Collaboration temps-réel — Yjs CRDT vs Operational Transform.
    Expliquer pourquoi CRDT gagne pour ce cas d'usage.
2.7 Sandbox d'exécution — Docker run avec --cap-drop=ALL, --network=none.
    Comparer brièvement à gVisor, Firecracker (et expliquer pourquoi
    on n'en a pas eu besoin à l'échelle PFE).
2.8 Comparatif synthétique — un grand tableau récapitulatif des choix.

Insère trois figures :
- Figure 2.1 : comparatif visuel des plateformes (matrice 2x2)
- Figure 2.2 : pile technologique en couches (frontend / API / data / AI)
- Figure 2.3 : modèle CRDT vs OT (illustration simple)

Documents joints : README.md, deployment-rationale.md,
deployment-problem-and-solution.md, architecture.md.
```

---

### Chapitre 3 — Conception du système

**Objective**: Diagrammes UML, MCD, sécurité, pipelines critiques. ~ 12 pages.

**Source files to attach**:
- `docs/uml/component-diagram.md`
- `docs/uml/sequence-rag-chat.md`
- `docs/uml/sequence-realtime-collab.md`
- `docs/merise/mcd.md`
- `docs/room-peer-chat.md` (sections 4-5 architecture + DB)
- `docs/fail2ban-report.md` (section 1 modèle de sécurité)

**Prompt**:

```text
[Préambule de style]

Écris le chapitre 3 — Conception du système. Cible : 12 pages,
environ 5 000 mots. C'est le cœur académique du mémoire ; les
schémas doivent dominer le texte.

Sections (avec figures dans chaque) :

3.1 Architecture globale
    Texte : présenter les couches client / passerelle / backend /
    données / IA / sandbox.
    Figure 3.1 : diagramme de composants UML (extrait de
    docs/uml/component-diagram.md, conservé en Mermaid OU exporté
    en PNG).

3.2 Modèle conceptuel de données — Merise MCD
    Texte : présenter les 8 entités (USER, ROOM, ROOM_MEMBER, ROOM_FILE,
    REFRESH_TOKEN, OAUTH_ACCOUNT, ROOM_EVENT, ROOM_CHAT_MESSAGE,
    ROOM_PEER_CHAT_MESSAGE) et justifier les cardinalités.
    Figure 3.2 : MCD complet (extrait de docs/merise/mcd.md, en
    Mermaid erDiagram ou export PNG).
    Tableau 3.1 : cardinalités explicites (recopier le tableau du MCD).
    Tableau 3.2 : contraintes d'intégrité (UNIQUE, ON DELETE CASCADE,
    etc.).

3.3 Modèle logique relationnel (MLD)
    Texte : montrer la transformation MCD → MLD (FK explicites).
    Schéma SQL synthétique en annexe.

3.4 Diagrammes de séquence
    Texte : présenter cinq scénarios critiques avec leur diagramme.
      3.4.1 Inscription + connexion JWT (figure 3.3)
      3.4.2 Connexion OAuth2 GitHub (figure 3.4)
      3.4.3 Édition collaborative temps-réel (figure 3.5)
      3.4.4 RAG chat streaming SSE (figure 3.6)
      3.4.5 Peer chat avec attachment (figure 3.7)
    Utilise les diagrammes Mermaid déjà écrits dans docs/uml/.

3.5 Modèle d'autorisations et de sécurité
    Texte : présenter les trois couches (UFW + fail2ban + Tailscale)
    et le rôle de chaque dans la défense en profondeur.
    Figure 3.8 : schéma des trois couches.
    Tableau 3.3 : matrice menaces × mitigations (recopier du
    fail2ban-report.md).

3.6 Pipeline RAG hybride
    Texte : expliquer le chunking AST (par symbole, pas par sliding
    window), la double indexation (Qdrant dense + Lucene BM25), et
    la fusion par RRF (formule). Justifier la valeur k=60.
    Figure 3.9 : pipeline RAG en flowchart.
    Équation 3.1 : la formule RRF.

3.7 Mode agent (tool calling + propose_patch)
    Texte : décrire l'AgentLoop, la liste d'outils, la limite à 5
    itérations, le fallback parser pour le tool-calling non strict.
    Figure 3.10 : diagramme d'activité du loop agent.

3.8 Communications temps-réel
    Texte : expliquer le pattern « backend dumb relay » — pas de Y.Doc
    serveur, juste un broadcast WebSocket binaire.
    Figure 3.11 : diagramme de séquence simplifié du relay.

3.9 Persistance peer-chat (deux couches)
    Texte : décrire le pattern Y.Array + Postgres source-of-truth,
    la reconcillation par id, le sense du double-write.
    Figure 3.12 : flowchart du send et du mount.

Documents joints : component-diagram.md, sequence-rag-chat.md,
sequence-realtime-collab.md, mcd.md, room-peer-chat.md (sections 4-5),
fail2ban-report.md (section 1).
```

---

### Chapitre 4 — Réalisation

**Objective**: Code + captures d'écran de chaque feature. ~ 10 pages.

**Source files to attach**:
- `README.md` (section Highlights + Tools)
- `docs/room-peer-chat.md` (toutes sections)
- `docs/progress.md`
- Les fichiers `frontend-web/src/pages/RoomPage.tsx`,
  `frontend-web/src/components/chat/ChatPanel.tsx`,
  `frontend-web/src/components/chat/RoomChat.tsx`
  (ou des extraits ciblés que tu joindras)

**Prompt**:

```text
[Préambule de style]

Écris le chapitre 4 — Réalisation. Cible : 10 pages, environ
4 000 mots. Ce chapitre doit montrer le « comment » avec des captures
d'écran et des extraits de code (max 15 lignes par snippet).

Pour chaque sous-section (4.2 à 4.8), respecte le format :
- 1 paragraphe d'introduction (« ce qu'on a réalisé »)
- 1 capture d'écran annotée (cf. liste § 5 du writing-plan)
- 1 extrait de code représentatif (méthode/composant clé)
- 1 paragraphe de justification (« pourquoi cette implémentation »)

Sections :
4.1 Environnement de développement — IDE (IntelliJ + VS Code),
    Docker Desktop + WSL2, scripts PowerShell start.ps1/stop.ps1.
4.2 Authentification — JWT HS512 + refresh token SHA-256, OAuth2
    GitHub. Capture : page Login. Code : JwtService.generateAccessToken.
4.3 Workspace room — éditeur CodeMirror, tabs, file explorer.
    Capture : RoomPage avec un fichier ouvert. Code : useCollabRoom hook.
4.4 RAG chat — pipeline d'indexation, streaming SSE.
    Capture : ChatPanel avec réponse en cours de streaming.
    Code : RoomChatService.streamChat (extrait, 15 lignes max).
4.5 Mode agent — boucle d'outils, propose_patch CRDT.
    Capture : AI panel en mode agent avec carte de patch proposée.
    Code : AgentLoop.run (loop + extractToolCalls).
4.6 Code runner Docker + Maven + Nix — sandbox flags.
    Capture : output panel après run avec exit 0.
    Code : DockerCodeRunnerService.runPython (les flags --network=none etc).
4.7 Peer chat avec attachments — double-write Y.Array + Postgres,
    upload multipart, preview image inline.
    Capture : tab People avec un échange de messages dont une image.
    Code : RoomPeerChatService.postWithFile.
4.8 Dashboard + activity feed + admin — cards, search, admin metrics.
    Capture : Dashboard et Admin (AI metrics tab).
4.9 Tests — résumer la pyramide (100+ tests JUnit backend, type-check
    frontend, smoke tests de prod). Citer 3-4 tests phares (RRF fusion,
    Bm25Searcher races, AgentLoop extractToolCalls).

Documents joints : README.md, room-peer-chat.md, progress.md,
extraits de code que je te fournirai séparément.
```

---

### Chapitre 5 — Déploiement et sécurité

**Objective**: Story complète du déploiement Hetzner + Tailscale, posture sécurité défendable. ~ 7 pages.

**Source files to attach**:
- `docs/deployment-problem-and-solution.md` (PRINCIPAL — narratif complet)
- `docs/deployment-rationale.md`
- `docs/fail2ban-report.md`

**Prompt**:

```text
[Préambule de style]

Écris le chapitre 5 — Déploiement et sécurité. Cible : 7 pages,
environ 3 000 mots. Ce chapitre raconte le voyage : problème →
recherche → solution → application → bilan.

Sections :
5.1 Le problème — résumer en 1 page : besoin d'une URL stable
    pour la soutenance, contraintes (budget, RAM, sécurité). Cite la
    statistique "174 tentatives SSH en 3h dès la première heure
    d'exposition publique" tirée de fail2ban-report.md.

5.2 Étude des alternatives — recopier le tableau d'évaluation de
    deployment-problem-and-solution.md (section 3) :
      Hébergement : laptop / AWS / Render / Fly / Railway / Hetzner
      Exposition : public+TLS / IP allowlist / bastion / OpenVPN /
        ZeroTier / Cloudflare Tunnel / Tailscale
    Pour chaque ligne, donner la raison du rejet (1-2 phrases).
    Tableau 5.1 : alternatives hébergement.
    Tableau 5.2 : alternatives exposition.

5.3 La solution retenue — Hetzner CX22 + Tailscale.
    Justifier en 3 axes : coût (5€/mois), souveraineté (RGPD UE),
    surface d'attaque (un seul port UDP).
    Figure 5.1 : architecture déploiement en diagramme.

5.4 Mise en œuvre — étapes pratiques :
      • provision VM Hetzner
      • install Tailscale
      • UFW deny-by-default + fail2ban
      • clone repo + Dockerfiles backend + frontend
      • Caddyfile reverse proxy
      • docker-compose.prod.yml
      • .env avec secrets générés via openssl
      • pull modèles Ollama
      • bring-up de la stack
    Inclure une figure de l'architecture finale (schéma de bloc).
    Figure 5.2 : flux d'une requête depuis le navigateur jusqu'au backend.

5.5 Hardening sécurité — UFW + fail2ban + Tailscale détaillés.
    Recopier la table « threats × mitigations » de
    deployment-problem-and-solution.md (section 6).
    Tableau 5.3 : matrice menaces × mitigations.

5.6 Limites assumées — l'épisode OAuth Google (essayé avec Tailscale
    Serve HTTPS, revenu en arrière pour garder Tailscale-only).
    Expliquer le trade-off honnêtement, montre la maturité.

5.7 Bilan opérationnel — chiffres clés :
      • 174 tentatives SSH bloquées en 3h avant le lock-down UFW
      • 20 IPs uniques bannies, 1 IP retentée 12 fois
      • aucune nouvelle tentative depuis 1 an (preuve que la posture
        marche)
      • coût total : 5€/mois (à comparer à ~40 USD/mois sur AWS)

Documents joints : deployment-problem-and-solution.md (le plus
important), deployment-rationale.md, fail2ban-report.md.
```

---

### Conclusion générale

**Objective**: Synthèse, limites, perspectives. ~ 2 pages.

**Source files to attach**:
- `README.md` (section Roadmap)
- `docs/ROADMAP.md`

**Prompt**:

```text
[Préambule de style]

Écris la conclusion générale du mémoire. Cible : 2 pages,
environ 800 mots.

Structure :
- Synthèse des contributions (3-4 paragraphes) :
    • ce qui a été conçu et livré
    • les choix techniques notables (RAG hybride, agent fallback parser,
      Tailscale-only)
    • les preuves de qualité (100+ tests, déploiement réel, sécurité
      défendable)
- Limites du travail (1-2 paragraphes) :
    • OAuth Google désactivé en prod (faute de domaine HTTPS public)
    • Modèle 7B sur CPU = inférence lente (3-5 tok/s)
    • Hiérarchie de dossiers absente dans le file explorer
    • Mobile app native non livrée (PWA fonctionne)
- Perspectives (2-3 paragraphes) :
    • support GPU pour accélérer les modèles
    • indexation continue (debounce smart sur les saves)
    • mode "playground" pour partager un room sans compte
    • exposer publiquement avec un domaine + Caddy + Let's Encrypt
- Mot de la fin : ce que le projet m'a appris, gratitude envers
  l'encadrement.

Pas de figure dans la conclusion.

Documents joints : README.md (section Roadmap), ROADMAP.md.
```

---

## 4. Master diagrams list

Chaque diagramme existe déjà dans le repo sous `docs/`. Pour la
version mémoire (Word ou PDF), exporte les Mermaid en PNG via
[mermaid.live](https://mermaid.live) ou la CLI `mermaid-cli`.

| # | Figure | Source | Où l'insérer | Format conseillé |
|---|---|---|---|---|
| 1.1 | Logo / organigramme FP Taza | [À FOURNIR] | Chapitre 1.1 | PNG |
| 1.2 | Diagramme de Gantt du sprint 4 semaines | [À CRÉER] | Chapitre 1.5 | PNG (Excalidraw ou Mermaid gantt) |
| 2.1 | Matrice des plateformes concurrentes | [À CRÉER] | Chapitre 2.1 | PNG |
| 2.2 | Pile technologique en couches | [À CRÉER] | Chapitre 2.2 | PNG (Excalidraw) |
| 2.3 | CRDT vs OT | [À CRÉER] | Chapitre 2.6 | PNG schématique |
| 3.1 | Diagramme de composants UML | `docs/uml/component-diagram.md` | Chapitre 3.1 | Mermaid export PNG |
| 3.2 | MCD complet (Merise) | `docs/merise/mcd.md` | Chapitre 3.2 | Mermaid erDiagram export PNG |
| 3.3 | Séquence inscription + JWT | [À CRÉER, à partir d'AuthService] | Chapitre 3.4.1 | Mermaid sequenceDiagram |
| 3.4 | Séquence OAuth2 GitHub | [À CRÉER] | Chapitre 3.4.2 | Mermaid sequenceDiagram |
| 3.5 | Séquence édition collab Yjs | `docs/uml/sequence-realtime-collab.md` | Chapitre 3.4.3 | Mermaid export PNG |
| 3.6 | Séquence RAG chat SSE | `docs/uml/sequence-rag-chat.md` | Chapitre 3.4.4 | Mermaid export PNG |
| 3.7 | Séquence peer chat avec fichier | `docs/room-peer-chat.md` § 4 | Chapitre 3.4.5 | Mermaid sequenceDiagram |
| 3.8 | Trois couches sécurité (UFW + fail2ban + Tailscale) | `docs/fail2ban-report.md` § 1 | Chapitre 3.5 | PNG schématique |
| 3.9 | Pipeline RAG hybride (chunking → embed → search → RRF → prompt) | [À CRÉER] | Chapitre 3.6 | Mermaid flowchart |
| 3.10 | Diagramme d'activité du AgentLoop | [À CRÉER, à partir d'AgentLoop.run] | Chapitre 3.7 | Mermaid stateDiagram-v2 |
| 3.11 | Relay WebSocket Yjs (backend dumb) | `docs/uml/sequence-realtime-collab.md` (simplifié) | Chapitre 3.8 | Mermaid sequenceDiagram |
| 3.12 | Two-layer sync peer chat (Y.Array + Postgres) | `docs/room-peer-chat.md` § 4 | Chapitre 3.9 | Mermaid flowchart |
| 5.1 | Architecture déploiement Hetzner + Tailscale | `docs/deployment-problem-and-solution.md` § 4 | Chapitre 5.3 | ASCII or PNG export |
| 5.2 | Flux d'une requête (browser → Caddy → backend) | [À CRÉER] | Chapitre 5.4 | Mermaid flowchart |

---

## 5. Master screenshot list

Stocker tous les PNG dans `docs/screenshots/`. Le guide de capture
détaillé est dans `docs/screenshots/README.md`. Voici la table de
correspondance vers les chapitres du mémoire :

| Fichier | Description / Caption | À placer dans |
|---|---|---|
| `docs/screenshots/landing.png` | Page d'accueil publique avec backdrop animé indigo/cyan/violet | Chapitre 4.2 (auth) |
| `docs/screenshots/login.png` | Page de connexion avec OAuth GitHub | Chapitre 4.2 |
| `docs/screenshots/signup.png` | Page d'inscription email/password | Annexe A |
| `docs/screenshots/dashboard.png` | Dashboard avec stats, project cards, activity feed | Chapitre 4.8 |
| `docs/screenshots/dashboard-mobile.png` | Dashboard Redmi avec hamburger nav | Annexe A (responsive) |
| `docs/screenshots/room.png` | Workspace room avec éditeur ouvert, file explorer, AI panel | Chapitre 4.3 |
| `docs/screenshots/room-multi-cursor.png` | Deux curseurs visibles (collab live) — prendre via 2 navigateurs côte-à-côte | Chapitre 4.3 |
| `docs/screenshots/chat.png` | AI chat avec réponse en streaming + context drawer ouvert | Chapitre 4.4 |
| `docs/screenshots/agent.png` | AI panel en mode agent avec tool calls + propose_patch card | Chapitre 4.5 |
| `docs/screenshots/output.png` | Output panel après un run Python (exit 0 vert) | Chapitre 4.6 |
| `docs/screenshots/output-maven.png` | Output panel après un run Maven (avec PROJECT COMMAND) | Chapitre 4.6 ou Annexe |
| `docs/screenshots/people.png` | Tab People avec liste participants + room chat actif | Chapitre 4.7 |
| `docs/screenshots/people-image.png` | Échange peer chat avec une image partagée inline | Chapitre 4.7 |
| `docs/screenshots/people-file.png` | Échange peer chat avec un PDF / ZIP partagé (chip cliquable) | Chapitre 4.7 |
| `docs/screenshots/admin-users.png` | Admin → tab Users avec dropdown role ouvert | Chapitre 4.8 |
| `docs/screenshots/admin-rooms.png` | Admin → tab Rooms | Chapitre 4.8 |
| `docs/screenshots/admin-stats.png` | Admin → tab Stats (compteurs RAG chunks, users joined) | Chapitre 4.8 |
| `docs/screenshots/admin-ai-metrics.png` | Admin → tab AI metrics (latency histogram + recent queries) | Chapitre 4.5 ou 4.8 |
| `docs/screenshots/tailscale-admin.png` | Console Tailscale montrant le tailnet (Hetzner + laptop + Redmi) | Chapitre 5.3 |
| `docs/screenshots/hetzner-cx22.png` | Page Hetzner Cloud avec la VM CX22 (specs visibles) | Chapitre 5.3 |
| `docs/screenshots/docker-ps.png` | Capture du `docker compose ps` sur le serveur (tous healthy) | Chapitre 5.4 |
| `docs/screenshots/fail2ban-status.png` | Capture du `fail2ban-client status sshd` montrant le total banned | Chapitre 5.7 |

### Convention de captions

Format suggéré pour les légendes : **Figure N.M : Titre court** —
description en une phrase, lisible isolément.

Exemple :
> **Figure 4.5 : L'éditeur Codeleon en édition collaborative** —
> Deux participants éditent simultanément le fichier `fibonacci.py`,
> chacun avec un curseur de couleur unique fourni par l'API Yjs
> Awareness. Le file explorer (gauche) et le panel AI (droite) sont
> ouverts.

---

## 6. Bibliographie suggérée

À enrichir au fur et à mesure de l'écriture. Format BibTeX ou
liste numérotée selon les conventions de ta fac.

```
[1]  N. Shapiro et al., "Conflict-free Replicated Data Types",
     INRIA, 2011. https://hal.inria.fr/inria-00609399v1
[2]  K. Cormack, C. Clarke, S. Buettcher, "Reciprocal rank fusion
     outperforms Condorcet and individual rank learning methods",
     SIGIR, 2009.
[3]  Tailscale Inc., "How Tailscale works",
     https://tailscale.com/blog/how-tailscale-works
[4]  Spring Security Reference, https://docs.spring.io/spring-security/reference/
[5]  Y. Asseman et al., "Yjs: A Framework for Collaborative Applications",
     [npm package documentation, 2024]
[6]  Tailscale, "MagicDNS and HTTPS for ts.net",
     https://tailscale.com/kb/1153/enabling-https
[7]  Hetzner Cloud, "CX-series specifications",
     https://www.hetzner.com/cloud
[8]  RFC 7519, "JSON Web Token (JWT)", IETF, 2015.
[9]  Ollama project, https://ollama.com/library/qwen2.5-coder
[10] Qdrant documentation, "Vector search at scale",
     https://qdrant.tech/documentation/
[11] Cormack et al., "BM25 and Beyond", FnTIR, 2009.
[12] OWASP, "Top 10 Web Application Security Risks (2021)",
     https://owasp.org/Top10/
```

---

## 7. Workflow recommandé

1. **Préparer les artefacts** (1 journée)
   - Capturer les screenshots du § 5 (ouvrir le repo, lancer la stack
     en dev, prendre les captures Chrome).
   - Exporter les Mermaid en PNG via mermaid.live.
   - Récupérer les informations administratives FP Taza ([À COMPLÉTER]).
2. **Écrire chapitre par chapitre** (~ 1 jour par chapitre)
   - Coller le prompt § 3 dans l'AI choisi.
   - Joindre les fichiers source listés.
   - Recevoir le brouillon, le sauvegarder en `memoire/chapitre-N.md`.
   - Relire, corriger, inserer les figures et screenshots aux
     emplacements indiqués (§ 4 et § 5).
3. **Assembler** (1 journée)
   - Concaténer tous les chapitres dans un document Word ou un projet
     LaTeX (Overleaf).
   - Vérifier les références croisées (Figure 3.5, Tableau 5.2, etc.).
   - Générer la table des matières, la liste des figures, la liste
     des tableaux.
   - Relecture finale par un proche (orthographe, syntaxe).
4. **Soutenance** (jour J)
   - Slides : 15-20 slides max, format Codeleon (palette indigo/cyan/violet).
   - Démo : laptop sur le tailnet, URL `https://...ts.net` ou
     `http://100.106.32.95`, scénario scripté.
   - Backup video 5 min au cas où.

---

## 8. Checklist finale avant impression

- [ ] Page de garde avec logo FP Taza + photo de l'étudiant
- [ ] Dédicace
- [ ] Remerciements (encadrant, jury, famille)
- [ ] Résumé FR + abstract EN (½ page chacun)
- [ ] Sommaire avec numéros de page corrects
- [ ] Liste des figures (Figure 1.1, 1.2, …)
- [ ] Liste des tableaux
- [ ] Liste des acronymes (PFE, RAG, CRDT, SSE, JWT, MIME, OAuth, etc.)
- [ ] Tous les screenshots intégrés (cf. § 5)
- [ ] Toutes les figures intégrées (cf. § 4)
- [ ] Bibliographie complète
- [ ] Annexes (code, configs, schémas SQL)
- [ ] Pagination
- [ ] Reliure / impression
- [ ] PDF de secours sur clé USB
