-- Taskly initial schema
-- Based on ARCH.md tables section

-- ============================================================
-- Users
-- Extensions (pgcrypto, pg_trgm) are installed by superuser externally.
-- ============================================================
-- ============================================================
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           TEXT NOT NULL,
    password_hash   TEXT NOT NULL,
    tz              TEXT NOT NULL DEFAULT 'Europe/Moscow',
    locale          TEXT NOT NULL DEFAULT 'ru',
    plan            TEXT NOT NULL DEFAULT 'free',
    settings        JSONB NOT NULL DEFAULT '{}',
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_users_email ON users (lower(email));

-- ============================================================
-- Lists
-- ============================================================
CREATE TABLE lists (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    is_smart        BOOLEAN NOT NULL DEFAULT false,
    query           TEXT,
    color           TEXT,
    archived        BOOLEAN NOT NULL DEFAULT false,
    sort_order      INT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 1,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lists_owner ON lists (owner_id) WHERE deleted_at IS NULL;

-- ============================================================
-- Tasks
-- ============================================================
CREATE TABLE tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    list_id         UUID NOT NULL REFERENCES lists(id) ON DELETE CASCADE,
    parent_id       UUID REFERENCES tasks(id),
    name            TEXT NOT NULL,
    priority        INT CHECK (priority IN (1, 2, 3) OR priority IS NULL),
    due_at          TIMESTAMPTZ,
    due_has_time    BOOLEAN NOT NULL DEFAULT false,
    start_at        TIMESTAMPTZ,
    estimate_min    INT,
    rrule           TEXT,
    repeat_mode     TEXT CHECK (repeat_mode IN ('every', 'after') OR repeat_mode IS NULL),
    completed_at    TIMESTAMPTZ,
    postponed_count INT NOT NULL DEFAULT 0,
    url             TEXT,
    location_id     UUID,
    assignee_id     UUID,
    version         BIGINT NOT NULL DEFAULT 1,
    field_versions  JSONB NOT NULL DEFAULT '{}',
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_list ON tasks (list_id, completed_at, due_at);
CREATE INDEX idx_tasks_owner ON tasks (owner_id, updated_at);
CREATE INDEX idx_tasks_active ON tasks (owner_id, list_id) WHERE completed_at IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_tasks_due ON tasks (owner_id, due_at) WHERE completed_at IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_tasks_name_trgm ON tasks USING gin (name gin_trgm_ops);

-- ============================================================
-- Tags
-- ============================================================
CREATE TABLE tags (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    color           TEXT,
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_tags_owner_name ON tags (owner_id, lower(name));

CREATE TABLE task_tags (
    task_id         UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    tag_id          UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, tag_id)
);

-- ============================================================
-- Notes
-- ============================================================
CREATE TABLE notes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    content         TEXT NOT NULL,
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notes_task ON notes (task_id);

-- ============================================================
-- Reminders
-- ============================================================
CREATE TABLE reminders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    fire_at         TIMESTAMPTZ NOT NULL,
    channel         TEXT NOT NULL DEFAULT 'push',
    sent            BOOLEAN NOT NULL DEFAULT false,
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reminders_fire ON reminders (fire_at) WHERE NOT sent;

-- ============================================================
-- Change log (sync)
-- ============================================================
CREATE TABLE change_log (
    seq             BIGSERIAL,
    scope_id        TEXT NOT NULL,
    entity          TEXT NOT NULL,
    entity_id       UUID NOT NULL,
    op              TEXT NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}',
    actor_id        UUID NOT NULL REFERENCES users(id),
    ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (scope_id, seq)
);

CREATE INDEX idx_change_log_ts ON change_log (ts);

-- ============================================================
-- Processed operations (idempotency)
-- ============================================================
CREATE TABLE processed_ops (
    op_id           TEXT PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    result          JSONB NOT NULL DEFAULT '{}',
    ts              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_ops_user ON processed_ops (user_id);
CREATE INDEX idx_processed_ops_ts ON processed_ops (ts);

-- ============================================================
-- Outbox (async events)
-- ============================================================
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    type            TEXT NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unsent ON outbox (created_at) WHERE sent_at IS NULL;

-- ============================================================
-- Refresh tokens
-- ============================================================
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);