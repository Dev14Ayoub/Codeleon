# Deployment — the problem, the alternatives, and the solution

> Narrative chapter for the PFE final report. Captures the deployment
> problem encountered during the Codeleon project, the research carried
> out to find a fix, every alternative that was evaluated and rejected,
> and the step-by-step application of the solution that was kept.

---

## 1. The problem

By week three of the four-week PFE sprint, Codeleon worked end-to-end on
the developer's laptop (`localhost:5173` + a local Docker stack). The
remaining question was how to expose it for the soutenance:

- The jury needs to see the project running on a stable URL, not on a
  laptop tethered to the venue's WiFi.
- The full stack (Postgres + Redis + Qdrant + Ollama with 1.5B and 7B
  models + Spring Boot backend + Vite frontend + Caddy) needs predictable
  resources — at least 8 GB of RAM resident plus headroom.
- The deployment has to be reproducible from version control so the work
  is defensible to the jury.
- Whatever is exposed has to be **safe** — within minutes of standing up
  a VPS with SSH open, the host was hit by **174 brute-force attempts
  from 20 distinct IPs** (documented in `docs/fail2ban-report.md`).

The developer is a solo Licence Pro student in Fès, with a strict
~10 €/month infrastructure budget. No SRE team, no time for cloud-native
learning curves, no domain to start with.

## 2. Constraints to respect simultaneously

| Constraint | Concrete requirement |
|------------|----------------------|
| Budget | ≤ ~10 €/month total |
| RAM | ≥ 8 GB for the AI workloads |
| Compute | CPU-only acceptable (no GPU budget) |
| Availability | Stable URL the day of the defense; no cold-start of 30 s |
| Security | Public attack surface must stay minimal — the runner endpoint can execute user code, the chat endpoint runs the LLM |
| Operator | A single solo developer — operational complexity must stay manageable |
| Iteration speed | Four-week PFE sprint, no time for vendor-specific tooling |
| Data sovereignty | European hosting preferred (RGPD-friendly for a French-language PFE) |

These constraints rule out most of the obvious answers at once. The bulk
of the work was discovering that, and finding the combination that
honours all of them.

---

## 3. Research and alternatives evaluated

The search proceeded in three layers: **where does the project live**,
**how do users reach it**, and later **how do we add OAuth providers
that have stricter rules**. Each layer was a separate evaluation.

### 3.1 Where does the project live — hosting alternatives

#### Rejected — keep it on the laptop

Demoing from `localhost:5173` was discarded early:

- The defense venue's WiFi cannot be trusted with a 4 GB Ollama download
  if the model isn't already loaded.
- The laptop battery, suspend behaviour, and surprise OS update could
  derail a 30-minute defense.
- The jury cannot grade operational maturity by looking at a dev server.

#### Rejected — AWS, GCP, Azure

A typical AWS `t3.medium` (4 GB RAM, 2 vCPU) costs around **30 USD/month**
plus EBS, plus egress, plus optional ALB. To handle 8 GB of RAM the bill
rises further. On top of the cost there is a complexity tax: VPC, IAM
policies, security groups, load balancers, CloudWatch agents — none of
which add value to a student project, but all of which steal sprint time
from features the jury actually grades. Vendor-specific tooling
(CloudFormation, CDK, Pulumi) creates lock-in that is hard to justify
for a one-shot PFE deployment.

#### Rejected — Render

Render's free tier sleeps services after 15 minutes of inactivity. A
jury that opens the URL mid-defense and waits 30 seconds for cold-start
is a UX failure that no rehearsal can prevent. The paid tier removes
the sleep but adds about 25 USD/month and still does not provide a
persistent disk large enough for Postgres + Qdrant + Ollama in
combination.

#### Rejected — Fly.io

Fly's free tier caps free RAM at 256 MB across all machines. Ollama's
smallest useful coder model is 1.2 GB resident; the 7 B model needed
for reliable tool-calling is 5.5 GB. The math does not work without
moving to a paid plan where AI workloads become unpredictable burn.

#### Rejected — Railway

Pay-as-you-go pricing burns through credits with AI workloads. A single
demo session that loads the 7 B model into RAM for ten minutes can
spike usage. Without careful watch the bill grows past the budget
during the soutenance itself. Railway also assumes a stateless,
short-lived workload model that suits API services more than persistent
Postgres + Qdrant + Ollama state.

#### Rejected — OVH / Scaleway / DigitalOcean

European VPS alternatives are acceptable on price but their 8 GB tier
hovers around 7–10 €/month, against ~5 €/month for Hetzner. The other
specs (vCPU, disk, network) are equivalent. Hetzner wins on raw value
without any vendor-specific catch.

#### Selected — Hetzner Cloud CX22

| Property | Value | Why it matters |
|----------|-------|----------------|
| RAM | 8 GB | Fits the AI workloads with headroom |
| vCPU | 4 (shared) | Honest specs — no surprise throttling at the noisy-neighbour limit |
| Disk | 40 GB NVMe SSD | Enough for the two Ollama models (~6.5 GB) plus Postgres, Qdrant, Docker images |
| Location | Helsinki (Finland) | RGPD-compliant by default, ~80 ms ping to Morocco |
| Cost | ~5 €/month, billed hourly | Inside the budget by a wide margin |
| OS | Plain Ubuntu 26.04 LTS | No proprietary management agent, no surprise cron jobs |
| Onboarding | No credit-card verification gymnastics | Critical for a student |

### 3.2 How users reach the deployment — exposure alternatives

A bare VPS with a public IP is a magnet for the internet. The 174 SSH
brute-force attempts captured during the first three hours of public
exposure (see `docs/fail2ban-report.md`) made the threat concrete. The
question shifted from "can we expose it" to "how do we *not* expose it
while still serving the jury".

#### Rejected — public IP + TLS + WAF + rate limiting

A public-facing setup demands a real defensive stack:

- TLS via Let's Encrypt (acquire a domain first, set up DNS, automate
  renewal),
- WAF rules (ModSecurity or a managed CDN),
- per-endpoint rate limiting (Codeleon's `/run` endpoint executes user
  code in a Docker sandbox; `/chat` runs Ollama inference; both are
  abuse magnets),
- Captcha on registration to fight spam waves,
- abuse monitoring and an on-call rotation for incidents.

For a four-week PFE sprint this engineering investment competes
directly with shipping features the jury will actually grade.

#### Rejected — IP allowlist

Hardcoding a list of authorised IPs is brittle. The developer moves
between networks (home, university, mobile tether). The jury arrives
from IPs that nobody knows in advance. Keeping the list correct
becomes a constant maintenance burden, and any mistake locks somebody
out of their own deployment.

#### Rejected — VPN bastion host

A dedicated SSH bastion requires another paid server, manual key
distribution to every device, and ongoing key rotation. It adds an
extra hop in the architecture without giving anything Tailscale doesn't
give for free.

#### Rejected — self-hosted OpenVPN or hand-rolled WireGuard

Running your own VPN coordinator means:

- a public-facing control plane that itself must be hardened,
- manual key distribution to every authorised device,
- manual IP allocation in the VPN subnet,
- broken NAT traversal on Moroccan mobile carrier networks (CGNAT and
  double-NAT are common, the developer often tethers from a phone).

The result is high operational cost for a feature an off-the-shelf
overlay network handles natively.

#### Rejected — ZeroTier

Functionally comparable to Tailscale. Slightly more complex
bootstrapping (network IDs to share, manual approval per device), and a
rougher mobile experience. The Tailscale `ts.net` MagicCert feature
(free Let's Encrypt certs for `*.ts.net` hostnames) has no equivalent
on ZeroTier.

#### Rejected — Cloudflare Tunnel

Requires owning a domain (extra 10 €/year plus DNS setup), ties the
deployment's identity to Cloudflare's auth stack, and produces a tunnel
that is *public-facing by default*. Cloudflare Access can restrict
who reaches the origin, but that adds another layer of policy to
maintain. The result is more complexity for a project that does not
need Cloudflare's CDN or DDoS-protection bonuses.

#### Selected — Tailscale

| Property | Why it fits Codeleon's constraints |
|----------|-------------------------------------|
| WireGuard under the hood | State-of-the-art kernel-level VPN, audited, minimal CPU overhead |
| Identity-first onboarding | Devices join the tailnet by signing in with Google or GitHub — no shared secret can be leaked |
| Magic DNS | Every node gets a stable `<host>.<tailnet>.ts.net` hostname, resolved only inside the tailnet |
| Magic Cert | Free auto-renewing Let's Encrypt certs for the MagicDNS hostnames — real HTTPS without owning a domain |
| `tailscale serve` | Native HTTPS reverse proxy on the tailnet — used briefly during the Google OAuth attempt (see 3.3) |
| CGNAT-tolerant | Works through every NAT including Moroccan mobile carrier networks |
| Mobile app | One-tap onboarding for the demo Redmi phone (essential — the project ships a mobile-friendly UI) |
| Free tier | Up to 3 users / 100 devices — covers developer + jury devices |
| Maturity | Production-used at NVIDIA, Hugging Face, Roblox — defensible to the jury |

### 3.3 OAuth Google — a separate sub-problem

GitHub OAuth was wired up successfully on the Tailscale-only IP
`http://100.106.32.95`. Google OAuth turned out to be harder: Google's
"secure-response-handling" policy refuses non-HTTPS callbacks except
for explicit `localhost`, and rejects private CGNAT IPs entirely.
Adding the provider required a different approach.

#### Rejected — buy a public domain + Let's Encrypt

Would cost ~10 €/year and force opening ports 80/443 publicly. That
defeats the Tailscale-only posture that everything else was built
around. It also reintroduces the abuse-mitigation stack the project
explicitly avoided.

#### Tried then reverted — Tailscale Serve + MagicDNS HTTPS

A Let's Encrypt cert was provisioned for the MagicDNS hostname
`ubuntu-8gb-hel1-1.taild1d23e.ts.net`. Tailscale Serve terminated HTTPS
on the tailnet, proxying to the existing Caddy on port 80. The
hostname was accepted by Google Cloud Console as an Authorized
JavaScript Origin and as a redirect URI.

At runtime the flow still failed with Google's `invalid_request`
error. The root cause: Spring's OAuth2 client constructs the
`redirect_uri` from the incoming request's `Host` header. With the
chain `Tailscale Serve (HTTPS 443) → Caddy (HTTP 80) → backend`, the
`X-Forwarded-Proto` and `X-Forwarded-Host` were not propagated end to
end, and Spring built a redirect_uri starting with `http://100.106.32.95/…`.
Google rejected that URI as non-secure.

The fix would have been either to wire `forward-headers-strategy=framework`
on Spring plus a Caddy header rewrite, or to hardcode the redirect URI
in the OAuth client config. Both fixes were within reach, but introduced
operational surface that the project did not need: the Tailscale Serve
config has to stay healthy, the cert renewal has to keep working, and
any future Caddy or backend tweak has to preserve the header chain.

The decision was to revert: roll back to HTTP on the Tailscale IP,
keep GitHub OAuth enabled in production, demo Google OAuth from the
local development environment (where `localhost` is special-cased and
accepted by Google), and document the constraint. The reverted state
is what runs on the soutenance day.

#### Rejected — Dynamic DNS (DuckDNS, NoIP, etc.)

Free `<name>.duckdns.org` subdomains resolving to the Hetzner public IP
would have unlocked Let's Encrypt cert issuance, but only by opening
ports 80 and 443 publicly. The Tailscale-only goal is undone the same
way as the "buy a domain" alternative.

---

## 4. The chosen solution — Hetzner CX22 + Tailscale + GitHub OAuth

The combination that fits every constraint at once:

```
                    INTERNET
                       │
                       │  (only WireGuard UDP/41641 is open)
                       │
            ┌──────────▼──────────┐
            │   Hetzner CX22 VM   │  Ubuntu 26.04, 4 vCPU, 8 GB RAM
            │   89.167.65.180     │  UFW: deny-by-default incoming
            │                     │  fail2ban: sshd jail
            └──────────┬──────────┘
                       │
                  WireGuard
                       │
            ┌──────────▼──────────┐
            │ Tailscale interface │  100.106.32.95 (CGNAT)
            └──────────┬──────────┘
                       │
                       │  Caddy reverse-proxies /api/* → backend
                       │                       *       → frontend
                       │
                  Tailnet devices only
                       │
             ┌─────────┼─────────┐
             │         │         │
         laptop   Redmi phone   jury laptops (added on demand)
```

- **Public attack surface**: a single UDP port (41641) running the
  WireGuard protocol. Every other port is dropped by UFW before sshd or
  Docker even sees the packet.
- **Authentication for the deployment**: only authenticated tailnet
  members reach the application. Identity proof goes through the user's
  Google or GitHub account on Tailscale's side.
- **Auth inside the application**: email/password (BCrypt) and GitHub
  OAuth, both reachable at `http://100.106.32.95`.
- **Cost**: 5 €/month, billed hourly, with the option to scale up the
  VM for the soutenance week and scale back down after.
- **Tailscale**: free tier, no card on file.

---

## 5. Step-by-step application of the solution

### Step 1 — Acquire the VM and install Tailscale

```bash
# On Hetzner Cloud Console, provision a CX22 with Ubuntu 26.04.
# SSH in from the dev laptop:
ssh root@89.167.65.180

# Install Tailscale and join the tailnet (browser opens for OAuth login):
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up

# Note the assigned 100.x.y.z address:
tailscale status
# 100.106.32.95   ubuntu-8gb-hel1-1   user@email   linux    -
```

### Step 2 — Lock down the public surface

```bash
# Allow only WireGuard (UDP/41641) and tailnet-interface traffic:
ufw default deny incoming
ufw default allow outgoing
ufw allow 41641/udp
ufw allow in on tailscale0
ufw enable

# Belt-and-suspenders: fail2ban watches sshd for brute force:
apt-get install -y fail2ban
systemctl enable --now fail2ban
```

After this step, `nmap` from any host outside the tailnet returns
"filtered" on every port except 41641/UDP — the attack surface is one
audited protocol.

### Step 3 — Clone the project repo

```bash
cd /opt
git clone https://github.com/Dev14Ayoub/Codeleon.git codeleon
cd codeleon
```

### Step 4 — Materialise the production infrastructure files

Five files are needed to bring up the stack. They live in version
control under the project repo:

1. `backend/Dockerfile` — multi-stage Maven build, JRE-only runtime image
   running as a non-root `codeleon` user.
2. `frontend-web/Dockerfile` — Vite production build served by nginx,
   with the SPA fallback wired up.
3. `Caddyfile` — reverse proxy: `/api/*` to the backend, everything else
   to the frontend. HTTP only since traffic stays inside the tailnet.
4. `docker-compose.prod.yml` — service graph (Postgres, Redis, Qdrant,
   Ollama, backend, frontend, Caddy) on a private Docker network. Only
   Caddy publishes a host port, bound explicitly to the Tailscale IP via
   the `TAILSCALE_IP` env variable.
5. `.env` (chmod 600, never committed) — `POSTGRES_PASSWORD`,
   `JWT_SECRET`, `ADMIN_EMAIL`, `TAILSCALE_IP`, plus the Ollama model
   names. The `.env` is what production docker-compose interpolates.

### Step 5 — Generate strong secrets

```bash
cd /opt/codeleon

# 32 alphanumeric chars for Postgres:
POSTGRES_PWD=$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 32)

# 64 hex chars = 256 bits for the JWT signing key:
JWT_SECRET=$(openssl rand -hex 32)

# Compose the .env from the values above (note: never paste these
# anywhere they can be logged):
cat > .env <<EOF
POSTGRES_PASSWORD=$POSTGRES_PWD
JWT_SECRET=$JWT_SECRET
ADMIN_EMAIL=your.real.email@example.com
TAILSCALE_IP=100.106.32.95
OLLAMA_CHAT_MODEL=qwen2.5-coder:7b-instruct-q4_K_M
OLLAMA_AGENT_MODEL=qwen2.5-coder:7b-instruct-q4_K_M
OLLAMA_EMBED_MODEL=nomic-embed-text
GITHUB_CLIENT_ID=<from github.com/settings/developers>
GITHUB_CLIENT_SECRET=<from github.com/settings/developers>
EOF

chmod 600 .env
```

The backend refuses to boot if `JWT_SECRET` is missing or matches the
placeholder default — see `JwtService.validateSecret`. This catches the
class of mistake where a deploy forgets to source `.env`.

### Step 6 — Bring the stack up

```bash
cd /opt/codeleon
docker compose -f docker-compose.prod.yml up -d --build
```

First-time build takes 5–10 minutes (Maven dependency download, npm
install, Vite production build, Docker image assembly). Subsequent
builds are layer-cached and finish in seconds.

### Step 7 — Pull the Ollama models inside the container

```bash
docker exec codeleon-ollama ollama pull qwen2.5-coder:7b-instruct-q4_K_M
docker exec codeleon-ollama ollama pull nomic-embed-text
```

The 7B coder model is ~4.7 GB on disk, ~5.5 GB resident in RAM. With
the 0.3 GB embed model and ~1.6 GB of JVM + Postgres + supporting
services, the box runs at ~7.5 GB resident — within the 8 GB envelope.

### Step 8 — Verify

```bash
docker compose -f docker-compose.prod.yml ps
# All services Up; Caddy listening on 100.106.32.95:80.

curl -s http://100.106.32.95/api/v1/auth/providers
# {"providers":["github"]}
```

From a tailnet-connected device (laptop, phone) the URL
`http://100.106.32.95` now serves Codeleon. Sign in with GitHub works
end-to-end. The jury phone joins the tailnet in one tap during the
soutenance.

---

## 6. Final state and trade-offs accepted

### Working in production today

- Email/password auth + GitHub OAuth, on `http://100.106.32.95`.
- Full RAG pipeline (hybrid retrieval, AST chunking, agent mode with
  qwen2.5-coder:7b-instruct-q4_K_M).
- Python and Java code runner sandboxed via Docker.
- Real-time collaborative editing (Yjs over WebSocket).
- Admin dashboard with observability metrics.

### Trade-offs that were knowingly accepted

| Trade-off | How it is defended |
|-----------|--------------------|
| Jury cannot reach the app over the public internet | The deployment behaves as an *internal tool*. Demo from the developer's laptop on the tailnet, or add the jury to the tailnet in one tap. This is how real companies expose internal infrastructure. |
| Google OAuth disabled in production | Google's policy rejects non-HTTPS / non-public-domain callbacks. Demonstrated in the dev environment with `localhost`. Producing it would require a public domain and reintroducing the abuse-mitigation stack. |
| MagicDNS hostname is not memorable | `ubuntu-8gb-hel1-1.taild1d23e.ts.net` is ugly. Acceptable for an internal tool. A future public deployment would acquire a real domain. |
| Tailscale free tier caps at 3 users | Sufficient for the PFE scope. Beyond it the tier is ~5 USD/user/month — still cheaper than running an own coordinator. |
| Sometimes-slow inference on CPU | qwen2.5-coder:7b-instruct-q4_K_M on a 4-vCPU box generates at 3–5 tokens/s. A long agent turn can take 2–3 minutes. Acceptable for a defense demo; the project explicitly defends "local sovereign AI" as a design value. |

### Cost summary

| Line item | Monthly cost |
|-----------|-------------|
| Hetzner CX22 (4 vCPU, 8 GB, 40 GB SSD, Helsinki) | ~5 € |
| Tailscale free tier | 0 € |
| Domain | 0 € (none acquired) |
| **Total** | **~5 €/month** |

Equivalent AWS deployment (t3.medium + EBS + ALB + Route 53 + data
transfer) is in the 35–50 USD/month range for a strictly inferior
security posture — the load balancer would be public-facing by
default.

### Statement for the soutenance

> Codeleon demonstrates an understanding that *not every application
> needs to be on the public internet*. For a collaborative coding tool
> with a defined user set, a private overlay network provides the same
> operational guarantees as a public deployment — TLS-equivalent
> encryption (WireGuard), stable hostnames, real authentication — with
> a fraction of the hardening cost and zero public attack surface
> beyond a single UDP port running an audited protocol. The total
> infrastructure budget is approximately 5 € per month, the deployment
> is reproducible from version control (`docker-compose.prod.yml` plus
> a 600-perm `.env`), and the security model is defensible: three
> concentric layers (UFW deny-by-default, fail2ban as belt-and-
> suspenders, Tailscale identity-based access) covering the failure
> modes of each.
