-- Idempotency must be per user: a global op_id key let one user's op id collide with
-- another user's (the second op failed and poisoned the batch).
ALTER TABLE processed_ops DROP CONSTRAINT processed_ops_pkey;
ALTER TABLE processed_ops ADD PRIMARY KEY (user_id, op_id);

-- Tables referenced by the shared query language (location:, isShared:). Without them the
-- SQL rendered by SqlRenderer failed at runtime. Features are not implemented yet, the
-- tables just make those predicates valid (and empty).
CREATE TABLE IF NOT EXISTS locations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    lat         DOUBLE PRECISION,
    lon         DOUBLE PRECISION,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_locations_owner ON locations (owner_id);

CREATE TABLE IF NOT EXISTS list_members (
    list_id     UUID NOT NULL REFERENCES lists(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission  TEXT NOT NULL DEFAULT 'view',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (list_id, user_id)
);
