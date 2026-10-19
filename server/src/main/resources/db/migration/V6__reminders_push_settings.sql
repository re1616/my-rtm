-- M3b: reminders (WEB-F8) — Web Push subscriptions, sent-reminder bookkeeping, daily digest.

CREATE TABLE IF NOT EXISTS push_subscriptions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint    TEXT NOT NULL UNIQUE,
    p256dh      TEXT NOT NULL,
    auth        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_push_subscriptions_user ON push_subscriptions (user_id);

-- One row per reminder actually sent: the unique key makes the scheduler idempotent,
-- also with several server instances.
CREATE UNIQUE INDEX IF NOT EXISTS idx_reminders_once ON reminders (task_id, fire_at, channel);

CREATE TABLE IF NOT EXISTS digest_log (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day         DATE NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, day)
);

-- Deleting an account removes its change log too (actor_id had no ON DELETE action)
ALTER TABLE change_log DROP CONSTRAINT IF EXISTS change_log_actor_id_fkey;
ALTER TABLE change_log ADD CONSTRAINT change_log_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE CASCADE;
