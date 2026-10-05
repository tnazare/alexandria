-- V9__semantic_search.sql
-- Phase 2 semantic search: pgvector storage for skill embeddings (Requirement R2).
-- Additive and idempotent — creates the vector extension and a new side table only; no changes to
-- existing tables and no data backfill, so it is safe on an empty or already-populated database.
--
-- Decision (see design "Schema"): embeddings live in a separate `skill_embeddings` table rather than
-- a column on `skills`. They are large, nullable during backfill, regenerated independently, and may
-- later become multi-vector per skill. The side table keeps `skills` lean and the ANN index isolated.
-- It carries `organization_id` (per the org-scoping convention) so the ACL predicate can filter on it
-- directly, consistently with the FTS query.

-- pgvector extension. IF NOT EXISTS makes re-runs and pre-provisioned databases safe (R2.1).
CREATE EXTENSION IF NOT EXISTS vector;

-- Per-skill embedding storage.
--   embedding   dim MUST equal SearchProperties.embeddingDim (default 256, matching the local
--               provider). A mismatch is caught at startup by the fail-fast check (R3.5) rather than
--               producing wrong distances silently.
--   model_id    provenance of the vector for reproducibility (R8).
--   source_hash hash of the assembled source text; lets the embedding service skip re-embedding
--               unchanged skills.
CREATE TABLE skill_embeddings (
    skill_id        UUID PRIMARY KEY REFERENCES skills(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL,
    embedding       vector(256) NOT NULL,
    model_id        TEXT NOT NULL,
    source_hash     TEXT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ANN index for approximate nearest-neighbour search by cosine distance. HNSW is chosen over IVFFlat
-- because it gives good recall out of the box on the small/medium corpora this targets (benchmark is
-- 20–500 skills) without an ANALYZE/lists tuning step. Cosine ops match the `<=>` operator used by
-- the semantic query.
CREATE INDEX idx_skill_embeddings_hnsw
    ON skill_embeddings USING hnsw (embedding vector_cosine_ops);

-- Supports org-scoped filtering/backfill counts.
CREATE INDEX idx_skill_embeddings_org ON skill_embeddings(organization_id);
