-- V8__skill_resolver.sql
-- Agent-facing Skill Resolver: service tokens for non-interactive agent auth (Requirement 9)
-- and organization-level resolver policy for reference-mode / commit handling (Requirement 7).
-- Creates new tables only; no data backfill, safe on empty and populated databases. Runs after V7.

-- Service tokens for non-interactive agent authentication.
-- The presented secret is never stored; only its SHA-256 hash and a short display prefix.
CREATE TABLE service_tokens (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    name             TEXT NOT NULL,
    token_hash       TEXT NOT NULL,
    token_prefix     TEXT NOT NULL,
    scope            TEXT NOT NULL DEFAULT 'agent_read'
                     CHECK (scope IN ('agent_read')),
    created_by       UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at       TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    last_used_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_service_tokens_hash ON service_tokens(token_hash);
CREATE INDEX idx_service_tokens_org ON service_tokens(organization_id);
CREATE INDEX idx_service_tokens_prefix ON service_tokens(token_prefix);

-- Organization-level resolver policy governing agent commit / reference-mode handling.
CREATE TABLE resolver_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    high_risk_policy        TEXT NOT NULL DEFAULT 'pin_and_revalidate'
                            CHECK (high_risk_policy IN ('permissive', 'pin_and_revalidate', 'block_commit')),
    restricted_risk_policy  TEXT NOT NULL DEFAULT 'pin_and_revalidate'
                            CHECK (restricted_risk_policy IN ('permissive', 'pin_and_revalidate', 'block_commit')),
    default_reference_mode  TEXT NOT NULL DEFAULT 'floating_latest_approved'
                            CHECK (default_reference_mode IN ('pinned', 'floating_latest_approved', 'floating_latest_published')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_resolver_policies_org ON resolver_policies(organization_id);
