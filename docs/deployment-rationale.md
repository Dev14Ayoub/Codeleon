# Deployment infrastructure — why Hetzner + Tailscale

> Narrative justification of the production infrastructure choices for the
> Codeleon PFE project. Intended as a chapter input for the final report,
> covering the problem, the constraints, the alternatives that were
> rejected, and the architecture that was kept.

---

## 1. The problem

Codeleon needs a production deployment for the PFE defense. Three reasons:

1. **Demonstrate that the project is more than a `localhost:5173` POC.** A
   jury that sees only `npm run dev` cannot evaluate operational maturity.
2. **Make the demo robust at the venue.** Depending on the dev laptop +
   the venue Wi-Fi for a 30-minute defense is a single point of failure.
3. **Run the full stack with predictable resources.** Postgres + Redis +
   Qdrant + Ollama (qwen2.5-coder 1.5B and 7B + nomic-embed) + Spring Boot
   + Vite-built React + Caddy already exceeds what most laptops handle
   smoothly alongside a browser, a few IDE windows and a demo recording.

## 2. Constraints

| Constraint | Concrete impact |
|------------|-----------------|
| Student budget | ≤ ~10 €/month all-in |
| AI workloads | ≥ 8 GB RAM, CPU-only acceptable |
| Solo operator | No SRE team — operational complexity must stay manageable |
| Demo audience | Jury may be on personal devices, possibly unfamiliar networks |
| Data sovereignty | European hosting preferred (RGPD-friendly for a French-language PFE) |
| Iteration speed | 4-week sprint — no time for cloud-native learning curves |

## 3. Why a plain VPS instead of a managed cloud platform

### Rejected — AWS / GCP / Azure

- `t3.medium` (~equivalent of an 8 GB box) costs ≈ 30 USD/month, before
  storage and egress. Six times the Hetzner budget for the same workload.
- The complexity tax (VPC, IAM, security groups, load balancers,
  CloudWatch, ...) consumes time that should go into the product.
- Free tiers do not cover AI workloads — they assume bursty, short-lived
  compute, not a 7B model resident in RAM for a 5-minute defense.
- Vendor-specific tooling (CloudFormation, CDK, ...) creates lock-in that
  is hard to justify for a student project.

### Rejected — Render / Fly.io / Railway

- Free tiers sleep services after inactivity. A jury that opens the URL
  mid-defense and waits 30 seconds for cold-start is a UX failure.
- Persistent compute with 8 GB RAM exceeds every free quota.
- Pay-as-you-go pricing burns through credits unpredictably with AI
  workloads — a single demo session loading the 7B model can spike usage.
- These platforms encourage stateless, container-native designs. Codeleon
  uses Postgres + Qdrant + Ollama with persistent storage, which fits
  uncomfortably.

### Selected — Hetzner Cloud CX22

- **Specs**: 4 vCPU, 8 GB RAM, 40 GB SSD.
- **Price**: ~5 €/month, predictable, billed by the hour.
- **Location**: Helsinki datacenter — RGPD-compliant by default, decent
  latency to Morocco (≈ 80 ms).
- **Honest hardware**: no aggressive vCPU over-subscription, no surprise
  throttling. Performance matches advertised specs.
- **Plain Ubuntu**: no proprietary management layer, no agent installed
  by the provider, no surprise cron jobs. The OS is the OS.
- **Reasonable provider reputation**: no credit-card-verification
  gymnastics, no unexpected suspensions for AI workloads.

## 4. The exposure problem

A bare VPS with a public IP is a magnet for the internet. We observed
this directly during the initial 3-hour window when SSH was open to the
world:

- **174 failed SSH authentication attempts** from 20 distinct IPs
- A single offender (`221.8.39.178`) re-tried **12 times** across
  ban/unban cycles
- Drive-by port scans from automated tooling (recorded in nginx-style
  access logs of unrelated services)

The threat surface for an exposed Codeleon deployment is wider than a
typical web app:

| Endpoint | What an attacker could try |
|----------|----------------------------|
| `/auth/register` | Mass account creation, spam wave |
| `/rooms/{id}/run` | Free compute via the Docker sandbox runner |
| `/rooms/{id}/chat` | Free LLM inference via Ollama |
| `/rooms/{id}/index/all` | DoS against Qdrant by indexing nonsense |
| `/admin/**` | Privilege escalation if JWT key is leaked |

Exposing all of this publicly demands a real defensive stack — rate
limiting, WAF rules, Captcha, abuse monitoring, on-call rotation for
incidents. For a 4-week PFE sprint, that engineering investment competes
directly with shipping features the jury will actually grade.

### Options considered

1. **Public + TLS + WAF + rate limiting** — high-complexity defence,
   every endpoint becomes an attack vector, the runner Docker becomes a
   critical security boundary that must hold against sustained probing.
2. **Public + IP allowlist** — brittle (the developer moves between
   networks; the jury arrives from unknown IPs), requires constant
   maintenance.
3. **VPN bastion host** — adds another server, more cost, manual key
   management, no clear win over option 4.
4. **Overlay network** (Tailscale, ZeroTier, Nebula) — the app stays
   completely off the public internet, identity-first access, no public
   ports to harden.

Option 4 was selected.

## 5. Why Tailscale specifically

### Rejected — OpenVPN / hand-rolled WireGuard

- Requires running your own coordinator (or trusting one).
- Manual key distribution to every device — error-prone.
- NAT traversal is fragile on Moroccan mobile carrier networks
  (CGNAT + double NAT very common).
- No mobile app polish — the demo phone (Redmi Note 14 Pro) is essential
  and onboarding to a self-hosted VPN on Android is friction.

### Rejected — ZeroTier

- Comparable feature set to Tailscale.
- Slightly more complex bootstrapping (network IDs to share, slightly
  rougher mobile experience).
- Smaller ecosystem of integrations (no `tailscale serve` equivalent
  out of the box).

### Rejected — Cloudflare Tunnel

- Requires owning a domain (extra ~10 €/year + setup).
- Ties identity to Cloudflare's auth stack.
- Tunnel destinations are public-facing by default — would still need
  Cloudflare Access policies to restrict (more complexity).
- DDoS protection / WAF features are bonuses the project does not need
  at PFE scale.

### Selected — Tailscale

| Property | Why it matters here |
|----------|---------------------|
| WireGuard under the hood | State-of-the-art kernel-level VPN, audited, minimal CPU overhead |
| Identity-first onboarding | Devices join the tailnet by signing in with Google/GitHub — no shared secret can leak |
| Magic DNS | Every node gets a stable `*.ts.net` hostname, resolved only by tailnet members |
| Magic Cert | Free auto-renewing Let's Encrypt certs for `*.ts.net` — real HTTPS without owning a domain |
| `tailscale serve` | Native HTTPS reverse proxy on the tailnet, terminated at the server, no Caddy reconfiguration |
| CGNAT-tolerant | Works through any NAT (essential on the dev's mobile-tethered networks) |
| Mobile app | One-tap onboarding for the demo phone |
| Free tier | Covers up to 3 users / 100 devices — well within the project's scope |
| Maturity | Production-used at NVIDIA, Hugging Face, Roblox — defensible to the jury as a serious choice |

## 6. The resulting security posture

The combination of UFW + fail2ban + Tailscale produces three layers of
defence:

| Layer | What it stops |
|-------|---------------|
| **UFW** (deny-by-default incoming) | Internet-wide port scans, drive-by exploitation attempts |
| **fail2ban** (sshd jail) | SSH brute force if UFW is ever misconfigured (belt-and-suspenders) |
| **Tailscale** (per-device auth) | All app-level access — only tailnet members reach the API |

| Threat | Mitigation |
|--------|------------|
| Internet-wide port scans | UFW deny + only WireGuard UDP open |
| SSH brute force on public IP | UFW closes port 22 publicly; fail2ban covers the failure mode |
| TLS / cert renewal toil | Tailscale auto-provisions and rotates Let's Encrypt certs |
| App-level abuse (chat / runner) | Only tailnet members reach the API |
| Service discovery via DNS | `*.ts.net` resolution is private to the tailnet |
| Compromised tailnet device | Tailscale admin console revokes devices instantly; MFA via OAuth provider |
| Insider threat | Tailscale ACLs can restrict per-device access; audit log in admin console |

The single residual public attack surface is the WireGuard UDP port
(`41641/udp`) — a well-audited protocol with no known practical
exploits. The public IP `89.167.65.180` returns a connection refused on
every other port.

## 7. Trade-offs we accepted

| Trade-off | How we defended it |
|-----------|--------------------|
| Jury cannot reach the app over the public internet | Treat the deployment as an *internal tool*. Demo from the developer's laptop, or add the jury to the tailnet in one tap. This is exactly how real companies expose internal infrastructure (Replit, Vercel, Linear all run substantial internal services this way). |
| Google OAuth cannot be enabled in production | Google's OAuth refuses non-HTTPS / non-public-domain callbacks. The fix would be a public domain, which we declined for security reasons. Google sign-in is demonstrated in the dev environment; the production path is documented. |
| MagicDNS hostname is not memorable | `ubuntu-8gb-hel1-1.taild1d23e.ts.net` is ugly. Acceptable for an internal tool. A public deployment would acquire a real domain. |
| Tailscale free tier limits to 3 users | Sufficient for the PFE scope (developer + jury devices). Beyond, the tier is 5 USD/user/month — still cheaper than running an own coordinator. |

## 8. Cost summary

| Line item | Monthly cost |
|-----------|-------------|
| Hetzner CX22 (4 vCPU, 8 GB, 40 GB SSD) | ~5 € |
| Tailscale free tier | 0 € |
| Domain | 0 € (none acquired) |
| **Total** | **~5 €/month** |

For comparison, the equivalent AWS deployment (t3.medium + EBS + ALB +
Route 53 + data transfer) would run between 35 and 50 USD/month — an
order of magnitude more, for a strictly inferior security posture (the
load balancer would be public-facing by default).

## 9. Summary statement (for the soutenance)

> Codeleon's production deployment demonstrates an understanding that
> **not every application needs to be on the public internet**. For a
> collaborative coding tool with a defined user set (the developer, the
> jury, future collaborators added on demand), a private overlay network
> provides the same operational guarantees as a public deployment — TLS,
> stable hostnames, real authentication — with a fraction of the
> hardening cost and zero public attack surface beyond a single UDP port
> running an audited protocol. The total infrastructure budget is ≈ 5 €
> per month, the deployment is reproducible from version control
> (`docker-compose.prod.yml` + `.env`), and the security model is
> defensible: three concentric layers (UFW, fail2ban, Tailscale) covering
> the failure modes of each.
