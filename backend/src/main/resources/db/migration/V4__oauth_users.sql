-- =============================================================================
-- OAuth2 social login.
-- =============================================================================
-- Until V3, every account had a bcrypt password_hash. Adding "Sign in with
-- GitHub" / "Sign in with Google" means password_hash must become optional
-- (OAuth users have none) and we need to remember which external identity
-- the account is linked to so we can find them again on the next sign-in.
--
-- The (oauth_provider, oauth_subject) tuple uniquely identifies a remote
-- account. The partial unique index keeps that constraint without affecting
-- legacy email/password rows whose two columns stay NULL.
-- =============================================================================

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users ADD COLUMN oauth_provider VARCHAR(20);
ALTER TABLE users ADD COLUMN oauth_subject VARCHAR(255);

-- (oauth_provider, oauth_subject) must be unique among OAuth users. Both
-- columns are NULL for password-based accounts, and PostgreSQL / H2 both
-- treat NULL pairs as distinct under a UNIQUE constraint, so a plain
-- unique index gives us what a partial index would without needing the
-- (PG-only) WHERE clause.
CREATE UNIQUE INDEX users_oauth_idx ON users (oauth_provider, oauth_subject);
