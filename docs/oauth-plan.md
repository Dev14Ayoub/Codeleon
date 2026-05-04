# OAuth2 social login plan — GitHub + Google

> Status: **NOT STARTED**. This document captures the full plan so the
> work is queued and won't get lost. Estimated effort: **4-6 hours**
> (3-4h backend, 1-2h frontend, ~30 min credential setup by the user).

## Why this matters for the PFE

Adding "Sign in with GitHub" and "Sign in with Google" is a high-impact,
low-risk feature for the defense:

- **Visible wow effect** in the demo: a single click and the user is
  inside the dashboard.
- Demonstrates real understanding of OAuth2 / OIDC, redirect URIs,
  state/PKCE, and integration with an existing JWT-based session model.
- Spring Security has a first-class `oauth2-client` starter, so the
  backend code stays small and idiomatic — easy to defend.

Recommended slot in the PFE roadmap: **week 2**, right after the logo
work (the login screen will look polished by then, OAuth buttons fit
visually).

## Current state

- Auth is email + password only:
  - `POST /auth/register` → bcrypt hash, JWT access + refresh
  - `POST /auth/login` → verifies password, JWT access + refresh
- `users` table has columns: `id, full_name, email (unique), password,
  avatar_url, created_at, updated_at`. `password` is `NOT NULL`.
- `SecurityConfig` wires a custom `JwtAuthenticationFilter`; there is
  no `oauth2Login()` configured anywhere.
- Frontend `LoginPage` and `SignupPage` only show email/password forms.

## Backend changes (~3-4h)

### 1. Maven dependency

Add to `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

### 2. Flyway migration `V3__oauth_users.sql`

```sql
ALTER TABLE users
    ADD COLUMN oauth_provider VARCHAR(20),
    ADD COLUMN oauth_subject  VARCHAR(255);

ALTER TABLE users
    ALTER COLUMN password DROP NOT NULL;

CREATE UNIQUE INDEX users_oauth_idx
    ON users (oauth_provider, oauth_subject)
    WHERE oauth_provider IS NOT NULL;
```

Update `User` entity: `password` becomes nullable, add `oauthProvider`
and `oauthSubject` columns.

### 3. Configuration in `application.yml`

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: ${GITHUB_CLIENT_ID:}
            client-secret: ${GITHUB_CLIENT_SECRET:}
            scope: read:user, user:email
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
          google:
            client-id: ${GOOGLE_CLIENT_ID:}
            client-secret: ${GOOGLE_CLIENT_SECRET:}
            scope: openid, profile, email
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

Add the four env vars to `.env.example`:

```
# OAuth2 (optional — leave blank to disable social login)
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
```

### 4. New code

- **`UserService.findOrCreateByOAuth(provider, subject, email, name)`**:
  - Lookup by `(oauthProvider, oauthSubject)` → return existing.
  - Else lookup by `email`:
    - If exists with `password != null` → throw "this email has a
      password-based account, log in normally" (prevents account
      hijacking via OAuth email collision).
    - If exists with `password == null` (rare race) → link to provider.
  - Else create new user with `password = null`, `oauthProvider`,
    `oauthSubject`.

- **`OAuth2LoginSuccessHandler` (extends `SimpleUrlAuthenticationSuccessHandler`)**:
  - Reads the `OAuth2User` principal.
  - Extracts `provider` from `OAuth2AuthenticationToken.getAuthorizedClientRegistrationId()`.
  - Extracts `subject`, `email`, `name` from `OAuth2User.getAttributes()`
    (different field names per provider — handle both).
  - Calls `userService.findOrCreateByOAuth(...)`.
  - Generates a Codeleon JWT access + refresh.
  - Redirects to `${frontendUrl}/auth/callback?accessToken=...&refreshToken=...`.

- **`SecurityConfig`** updates:
  - `.oauth2Login(o -> o.successHandler(oauth2LoginSuccessHandler))`.
  - Permit `/oauth2/**` and `/login/oauth2/code/**` (Spring's default
    OAuth2 endpoints) — they must not go through the JWT filter.
  - Add `frontend-url` config property for the redirect target.

### 5. Tests

- `OAuth2LoginSuccessHandlerTest`: mock OAuth2User, verify
  `findOrCreateByOAuth` is called with extracted attrs and that the
  redirect URL contains a JWT.
- `UserServiceOAuthTest`: cover the three branches (existing OAuth user,
  email collision with password, fresh signup).
- Skip the live OAuth handshake test — too brittle, costs setup of fake
  authorization server. Instead test the success handler in isolation.

## Frontend changes (~1-2h)

### 1. Auth pages

In `LoginPage.tsx` and `SignupPage.tsx`, add a divider and two buttons
above the email/password form:

```tsx
<a
  href={`${API_BASE_URL}/oauth2/authorization/github`}
  className="...primary-style..."
>
  <Github className="h-4 w-4" /> Continue with GitHub
</a>
<a
  href={`${API_BASE_URL}/oauth2/authorization/google`}
  className="...secondary-style..."
>
  <Mail className="h-4 w-4" /> Continue with Google
</a>

<div className="my-4 flex items-center gap-3">
  <hr className="flex-1 border-zinc-800" />
  <span className="text-xs text-zinc-500">or</span>
  <hr className="flex-1 border-zinc-800" />
</div>
```

### 2. Callback page

New route `/auth/callback`:

```tsx
export function AuthCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const setSession = useAuthStore((s) => s.setSession);

  useEffect(() => {
    const accessToken = params.get("accessToken");
    const refreshToken = params.get("refreshToken");
    if (!accessToken || !refreshToken) {
      navigate("/login?error=oauth_failed");
      return;
    }
    setSession({ accessToken, refreshToken });
    fetchCurrentUser()
      .then((user) => {
        useAuthStore.getState().setUser(user);
        navigate("/dashboard");
      })
      .catch(() => navigate("/login?error=oauth_failed"));
  }, []);

  return <p>Signing you in...</p>;
}
```

### 3. Hide the buttons gracefully when env is empty

If `GITHUB_CLIENT_ID` is empty server-side, hitting the button will get
a Spring 500. Cleanest: expose a `GET /auth/providers` endpoint that
returns `{github: bool, google: bool}` based on which credentials are
configured, and conditionally render the buttons.

## Credential setup (user task, ~30 min)

### GitHub

1. Visit `https://github.com/settings/developers` → **New OAuth App**.
2. Application name: `Codeleon (dev)`.
3. Homepage URL: `http://localhost:5173`.
4. Authorization callback URL:
   `http://localhost:8080/api/v1/login/oauth2/code/github`.
5. Copy Client ID + generate Client Secret.
6. Paste into `.env`:
   ```
   GITHUB_CLIENT_ID=...
   GITHUB_CLIENT_SECRET=...
   ```

### Google

1. Visit `https://console.cloud.google.com/apis/credentials`.
2. **Create Credentials → OAuth client ID → Web application**.
3. Authorized JavaScript origins: `http://localhost:5173`.
4. Authorized redirect URIs:
   `http://localhost:8080/api/v1/login/oauth2/code/google`.
5. Copy Client ID + Secret.
6. Paste into `.env`:
   ```
   GOOGLE_CLIENT_ID=...
   GOOGLE_CLIENT_SECRET=...
   ```

For production, the redirect URIs change to whatever public domain the
backend is deployed at; treat this as part of the deployment runbook
(week 3 / 4 of the PFE timeline).

## Recommended commit structure

Two commits, in this order:

1. `feat(auth): add OAuth2 social login (GitHub, Google)`
   - All backend changes including the migration, config, success
     handler, service, tests, and `.env.example` keys.
   - The frontend stays untouched.
   - Backend boots fine when client ids are blank (the OAuth providers
     simply aren't registered, no crash).

2. `feat(auth): wire GitHub / Google buttons on login and signup pages`
   - Frontend changes: callback page, route, buttons, providers fetch.

Splitting the work this way keeps the backend reviewable in isolation
and makes the PR easier to defend in the PFE oral.

## Open questions to resolve before starting

- Should we let an existing email/password user **link** their account
  to GitHub/Google after signup (e.g. from a profile settings page)?
  Out of scope for the first commit; document as a follow-up.
- For the demo, do we want to **pre-fill** a test GitHub OAuth app's
  credentials in `.env.example`? No — keep it blank, document in README.
- Avatar URL: pull `avatar_url` from GitHub / `picture` from Google,
  store in `users.avatar_url`. Already a column, just populate it.
