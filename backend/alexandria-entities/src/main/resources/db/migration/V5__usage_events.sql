-- V5__usage_events.sql
-- Introduces the append-only usage_events store (Skill Export milestone, parent Req 17).
-- This milestone writes only 'copy' and 'export' events; 'view' is reserved in the CHECK
-- constraint for the later Usage Event Tracking milestone. Records are never updated after
-- insert (no updated_at column).

CREATE TABLE usage_events (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    skill_id          UUID NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    skill_version_id  UUID REFERENCES skill_versions(id) ON DELETE SET NULL,
    user_id           UUID REFERENCES users(id) ON DELETE SET NULL,
    event_type        TEXT NOT NULL CHECK (event_type IN ('view', 'copy', 'export')),
    source            TEXT,
    export_format     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_usage_events_org ON usage_events (organization_id);
CREATE INDEX idx_usage_events_skill ON usage_events (skill_id);
CREATE INDEX idx_usage_events_type_time ON usage_events (organization_id, event_type, created_at);
