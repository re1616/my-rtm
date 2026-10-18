-- Taskly feature flags
-- SEC-7: /v1/features returns active flags for user+plan+version
-- ============================================================
CREATE TABLE feature_flags (
    key             TEXT PRIMARY KEY,
    description     TEXT,
    rollout_pct     INTEGER NOT NULL DEFAULT 100 CHECK (rollout_pct >= 0 AND rollout_pct <= 100),
    plan_filter     TEXT,        -- comma-separated plan names, NULL = all plans
    version_min     TEXT,        -- minimum semver (inclusive), NULL = any
    version_max     TEXT,        -- maximum semver (inclusive), NULL = any
    enabled         BOOLEAN NOT NULL DEFAULT true,
    kill_switch     BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed flags for development
INSERT INTO feature_flags (key, description, rollout_pct, plan_filter, enabled) VALUES
    ('smart_add', 'Smart Add parsing in task creation', 100, 'free,pro,business', true),
    ('recurring_tasks', 'Repeating tasks with RRULE support', 100, 'free,pro,business', true),
    ('collaboration', 'Shared lists and collaboration', 0, 'pro,business', true),
    ('ai_suggestions', 'AI-powered task suggestions', 50, 'pro,business', true),
    ('analytics', 'Task completion analytics dashboard', 100, 'business', true)
ON CONFLICT (key) DO NOTHING;