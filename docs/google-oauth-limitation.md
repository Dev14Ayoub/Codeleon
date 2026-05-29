# Google OAuth — production limitation and remediation path

> Documentation of an issue encountered while attempting to enable Google
> as a sign-in provider for the production deployment on Hetzner +
> Tailscale. The investigation is preserved here so future work knows
> exactly what was tried, why it didn't ship, and what would be needed
> to finish it.

---

## TL;DR

Google OAuth is **implemented in the codebase** and **operational in the
local development environment** (`http://localhost:5173` ↔ `http://localhost:8080`).
It is **deliberately disabled in production** because Google's OAuth 2.0
policies refuse a `redirect_uri` that does not satisfy their secure
response handling rules — specifically, an `http://<IP>` URI on a
non-localhost address. The Tailscale-only architecture made the HTTPS
path land on a `.ts.net` hostname that Spring did not consistently
construct as the `redirect_uri`, so the OAuth request failed with
`invalid_request`.

In production only **email/password** and **GitHub** sign-in are exposed.

---

## What we observed

Google rejected the authorisation request with HTTP 400 / `invalid_request`,
returning the following payload (decoded from the base64 blob in the
error URL):

> You can't sign in to this app because it doesn't comply with Google's
> OAuth 2.0 policy for keeping apps secure.
>
> See https://developers.google.com/identity/protocols/oauth2/policies#secure-response-handling
>
> redirect_uri: `http://100.106.32.95/api/v1/login/oauth2/code/google`

The `redirect_uri` Spring sent to Google was the **HTTP IP form**, even
though the browser was accessing the application via the HTTPS Tailscale
hostname `https://ubuntu-8gb-hel1-1.taild1d23e.ts.net`. Google's policy
requires HTTPS for all non-localhost callbacks, so the request was
refused at the policy gate.

## Why the `redirect_uri` came out wrong

Spring's `CommonOAuth2Provider.GOOGLE.getBuilder("google")` does not
hardcode a `redirect_uri`; instead Spring derives it from the incoming
HTTP request's host and scheme at the moment the user clicks
"Sign in with Google". The production request path is:

```
Browser (HTTPS)
  → Tailscale Serve  [terminates TLS on :443]
    → Caddy          [HTTP on :80 reverse-proxy]
      → backend      [Spring Boot on :8080]
```

By the time the request reaches Spring, the TLS termination and at least
two reverse-proxy hops have rewritten / collapsed the original host and
scheme. Without explicit handling of the `X-Forwarded-Proto` and
`X-Forwarded-Host` headers — propagated correctly through **both**
Tailscale Serve **and** Caddy — Spring sees the request as `HTTP` on
whatever upstream IP Caddy used, which is the Tailscale IP. Hence the
`http://100.106.32.95/...` callback.

GitHub OAuth still worked because GitHub's policy is more permissive: it
accepts the HTTP IP `redirect_uri` as long as it matches the URI
registered on the OAuth app page exactly. Google enforces an additional
"must use HTTPS" rule that GitHub does not.

## Possible fixes (none applied, in order of effort)

1. **Hardcode the `redirect_uri`** in `OAuth2ClientConfig` via
   `.redirectUri("https://ubuntu-8gb-hel1-1.taild1d23e.ts.net/api/v1/login/oauth2/code/google")`.
   *Drawback*: bakes the production hostname into the Java code, hurts
   the "deploy anywhere" property of the image.

2. **Configure forwarded-headers handling in Spring** by adding
   `server.forward-headers-strategy: framework` to `application.yml`
   and making sure Caddy passes `X-Forwarded-Proto: https` and
   `X-Forwarded-Host: ubuntu-8gb-hel1-1.taild1d23e.ts.net` end-to-end.
   This requires verifying that Tailscale Serve sets the forwarded
   headers correctly on its hop, which is not documented behaviour and
   varies by Tailscale version.

3. **Acquire a public domain with HTTPS** (Let's Encrypt via Caddy) and
   expose the backend publicly. Google then accepts the redirect URI
   trivially. *Drawback*: forfeits the Tailscale-only security posture,
   requires the rate-limiting and abuse-monitoring layer the project
   explicitly chose to skip (see `docs/deployment-rationale.md`).

For the PFE timeline, the cost of any of these three fixes was
disproportionate to the value of adding a second social-login provider
on top of a working GitHub flow.

## What is shipped in production today

| Sign-in method | Status |
|----------------|--------|
| Email + password | Available |
| GitHub (`Sign in with GitHub`) | Available |
| Google (`Sign in with Google`) | **Disabled** — credentials not set in `/opt/codeleon/.env` |

The `OAuth2ClientConfig` is gated on the credentials being non-blank:

```java
@ConditionalOnExpression(
        "!'${codeleon.oauth.github.client-id:}'.isBlank() || " +
        "!'${codeleon.oauth.google.client-id:}'.isBlank()"
)
```

So removing `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` from `.env` is
enough to make the Google button disappear from the SPA. The code path
itself remains in the JAR and is verified by the local-dev demo on
`localhost:5173`.

## How to re-enable in the future

When the project acquires a public HTTPS domain (e.g. `codeleon.tld`):

1. Update the Google Cloud Console OAuth client:
   - Authorized JavaScript origins: `https://codeleon.tld`
   - Authorized redirect URIs: `https://codeleon.tld/api/v1/login/oauth2/code/google`
2. Add credentials back to `/opt/codeleon/.env`:
   ```
   GOOGLE_CLIENT_ID=...
   GOOGLE_CLIENT_SECRET=...
   ```
3. Update `CORS_ALLOWED_ORIGINS` in the same file to point to the new
   domain (also picked up by `OAuth2LoginSuccessHandler#frontendOrigin`
   for the post-login redirect).
4. Force-recreate the backend container so it re-reads the environment:
   ```
   docker compose -f docker-compose.prod.yml up -d --force-recreate backend
   ```
5. `GET /api/v1/auth/providers` should now return
   `{"providers":["github","google"]}` and the SPA renders the Google
   button.

No code change is required.

## Defense for the PFE jury

> Google OAuth is implemented and demonstrated in the development
> environment. In production it is intentionally disabled because the
> Tailscale-fronted deployment uses an `*.ts.net` hostname that does not
> satisfy Google's OAuth 2.0 secure-response-handling policy without
> either (a) a public domain with HTTPS or (b) intrusive code changes to
> hardcode the redirect URI. The choice to skip both options preserves
> the Tailscale-only security posture; the code path remains exercised
> in dev so the implementation is not vapor.

That paragraph is suitable to paste verbatim into the security or
deployment chapter of the report.
