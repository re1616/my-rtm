-- M3a: task card data (WEB-F3) — synced notes, link attachments, subtasks.

-- Notes become a synced entity: soft delete so the deletion travels through the change log.
ALTER TABLE notes ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- Attachments: links to external files (Google Drive, Dropbox, any https URL).
-- Uploading files into our own storage needs an object store and is out of scope here.
CREATE TABLE IF NOT EXISTS attachments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id     UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    url         TEXT NOT NULL,
    version     BIGINT NOT NULL DEFAULT 1,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_attachments_task ON attachments (task_id);
CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_change_log_entity ON change_log (entity_id, seq);
