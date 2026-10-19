-- M4: Android devices registered for FCM wake-ups (AND-S4).
CREATE TABLE IF NOT EXISTS push_devices (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL UNIQUE,
    platform    TEXT NOT NULL DEFAULT 'android',
    -- false: the device cannot use exact alarms, the server sends reminders by FCM (AND-N2)
    exact_alarms BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_push_devices_user ON push_devices (user_id);
