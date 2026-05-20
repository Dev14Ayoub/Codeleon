-- Linked OAuth identities and provider tokens.
--
-- The original users.oauth_provider/oauth_subject pair can only represent
-- the provider that created the account. A user who signed up with Google
-- still needs to connect GitHub so Codeleon can import private/restricted
-- repositories. This table stores each linked provider separately.

CREATE TABLE user_oauth_accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    email VARCHAR(180),
    access_token VARCHAR(4000),
    token_type VARCHAR(50),
    scopes VARCHAR(1000),
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_oauth_provider UNIQUE (user_id, provider),
    CONSTRAINT uq_oauth_provider_subject UNIQUE (provider, subject)
);

CREATE INDEX idx_user_oauth_accounts_user_id ON user_oauth_accounts(user_id);
CREATE INDEX idx_user_oauth_accounts_provider ON user_oauth_accounts(provider);
