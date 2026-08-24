-- Seed the default organization for local development.
-- In production, organizations are created via the admin API or provisioning scripts.

INSERT INTO organizations (id, name, slug, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default Organization', 'default', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
