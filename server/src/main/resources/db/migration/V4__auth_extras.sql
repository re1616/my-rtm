-- WEB-S3: password reset, TOTP two-factor auth, Google sign-in.

-- Accounts created through Google sign-in have no password until they set one via reset.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS totp_secret  TEXT,
    ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS google_sub   TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS users_google_sub_key ON users (google_sub) WHERE google_sub IS NOT NULL;

-- Single-use reset tokens; only the SHA-256 of the token is stored.
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_password_reset_user ON password_reset_tokens (user_id);
