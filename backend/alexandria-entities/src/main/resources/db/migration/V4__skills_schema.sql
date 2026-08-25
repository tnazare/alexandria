-- V4__skills_schema.sql

-- Taxonomy entries (controlled vocabulary)
CREATE TABLE taxonomy_entries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    tag_type          TEXT NOT NULL CHECK (tag_type IN ('task_type', 'tool', 'language', 'framework')),
    value             TEXT NOT NULL,
    description       TEXT,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_taxonomy_org_type_value ON taxonomy_entries(organization_id, tag_type, LOWER(value));
CREATE INDEX idx_taxonomy_org_type ON taxonomy_entries(organization_id, tag_type);

-- Skills (core entity)
CREATE TABLE skills (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    owner_team_id       UUID NOT NULL REFERENCES teams(id) ON DELETE RESTRICT,
    name                TEXT NOT NULL,
    slug                TEXT NOT NULL,
    description         TEXT NOT NULL,
    lifecycle_status    TEXT NOT NULL DEFAULT 'Draft'
                        CHECK (lifecycle_status IN ('Draft', 'Published', 'Team_Approved',
                               'Company_Approved', 'Deprecated', 'Archived', 'Blocked')),
    visibility          TEXT NOT NULL DEFAULT 'private'
                        CHECK (visibility IN ('private', 'team', 'organization', 'restricted')),
    risk_level          TEXT CHECK (risk_level IN ('low', 'medium', 'high', 'restricted')),
    current_version_id  UUID,  -- FK added after skill_versions table
    created_by          UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_skills_org_slug ON skills(organization_id, slug);
CREATE INDEX idx_skills_org_id ON skills(organization_id);
CREATE INDEX idx_skills_owner_team ON skills(owner_team_id);
CREATE INDEX idx_skills_lifecycle ON skills(organization_id, lifecycle_status);
CREATE INDEX idx_skills_visibility ON skills(organization_id, visibility);
CREATE INDEX idx_skills_created_by ON skills(created_by);

-- Skill versions (immutable content snapshots)
CREATE TABLE skill_versions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id              UUID NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version_number        INTEGER NOT NULL,
    canonical_instructions TEXT NOT NULL,
    applicability         TEXT,
    limitations           TEXT,
    when_to_use           TEXT,
    examples              TEXT,
    changelog             TEXT,
    supported_tools       TEXT[] NOT NULL DEFAULT '{}',
    published_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_skill_versions_skill_number ON skill_versions(skill_id, version_number);
CREATE INDEX idx_skill_versions_skill ON skill_versions(skill_id);

-- Add FK from skills.current_version_id to skill_versions
ALTER TABLE skills
    ADD CONSTRAINT fk_skills_current_version FOREIGN KEY (current_version_id)
    REFERENCES skill_versions(id) ON DELETE SET NULL;

-- Skill variants (tool-specific instructions per version)
CREATE TABLE skill_variants (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_version_id  UUID NOT NULL REFERENCES skill_versions(id) ON DELETE CASCADE,
    tool              TEXT NOT NULL CHECK (tool IN ('generic', 'github_copilot', 'codex', 'cursor',
                      'aws_kiro', 'sourcegraph_cody', 'claude_code', 'jetbrains_ai', 'internal_agent')),
    instructions      TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_skill_variants_version_tool ON skill_variants(skill_version_id, tool);
CREATE INDEX idx_skill_variants_version ON skill_variants(skill_version_id);

-- Skill tags (join table between skills and taxonomy_entries)
CREATE TABLE skill_tags (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id          UUID NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    taxonomy_entry_id UUID NOT NULL REFERENCES taxonomy_entries(id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_skill_tags_skill_entry ON skill_tags(skill_id, taxonomy_entry_id);
CREATE INDEX idx_skill_tags_skill ON skill_tags(skill_id);
CREATE INDEX idx_skill_tags_entry ON skill_tags(taxonomy_entry_id);

-- Skill access grants (for "restricted" visibility)
CREATE TABLE skill_access_grants (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id          UUID NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    granted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_skill_access_grants_skill_user ON skill_access_grants(skill_id, user_id);
CREATE INDEX idx_skill_access_grants_skill ON skill_access_grants(skill_id);
CREATE INDEX idx_skill_access_grants_user ON skill_access_grants(user_id);

-- Full-text search index (tsvector column on skills)
ALTER TABLE skills ADD COLUMN search_vector tsvector;

-- Trigger function to update search_vector
CREATE OR REPLACE FUNCTION skills_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'B');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_skills_search_vector
    BEFORE INSERT OR UPDATE OF name, description ON skills
    FOR EACH ROW EXECUTE FUNCTION skills_search_vector_update();

-- GIN index for full-text search
CREATE INDEX idx_skills_search_vector ON skills USING GIN(search_vector);
