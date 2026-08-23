-- Users, Teams, and Team Members schema

-- Users
CREATE TABLE users (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    external_subject  TEXT NOT NULL,
    email             TEXT NOT NULL,
    display_name      TEXT,
    role              TEXT NOT NULL DEFAULT 'Skill_Consumer',
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_users_org_subject ON users(organization_id, external_subject);
CREATE INDEX idx_users_org_id ON users(organization_id);

-- Teams
CREATE TABLE teams (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    name              TEXT NOT NULL,
    slug              TEXT NOT NULL,
    description       TEXT,
    created_by        UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_teams_org_slug ON teams(organization_id, slug);
CREATE INDEX idx_teams_org_id ON teams(organization_id);
CREATE INDEX idx_teams_created_by ON teams(created_by);

-- Team Members
CREATE TABLE team_members (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id           UUID NOT NULL REFERENCES teams(id) ON DELETE RESTRICT,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    team_role         TEXT NOT NULL,
    joined_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_team_members_team_user ON team_members(team_id, user_id);
CREATE INDEX idx_team_members_team_id ON team_members(team_id);
CREATE INDEX idx_team_members_user_id ON team_members(user_id);

-- Add FK constraint on audit_events.actor_user_id referencing users
ALTER TABLE audit_events
    ADD CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT;
