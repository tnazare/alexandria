# Design — Semantic Search (pgvector) with Hybrid Retrieval and a Feature Flag

## Overview

Add vector retrieval to Alexandria's Skill Resolver alongside the existing Postgres FTS, fuse them
into a hybrid ranking, and gate the whole thing behind an env-driven feature flag. The resolve/fetch
contract and all adapters (MCP, REST) are untouched; the change is confined to a strategy branch
inside `ResolverService`, a new repository query, an embedding subsystem, one Flyway migration, and
the Postgres image. Default mode is `fts`, so existing deployments see zero behavior change.

This directly serves the benchmark: with the flag, the same frozen corpus and tasks run under
`fts | semantic | hybrid`, isolating the retrieval mechanism as a single controlled variable.

## Current state (verified in code)

- `ResolverServiceImpl.doResolve` calls `resolverRepository.findVisibleCandidates(orgId, userId,
  isOrgAdmin, includeArchived, query, limit)` — the single query seam. Every adapter is a thin shell
  above `ResolverService`.
- FTS lives in `SkillResolverRepository.findVisibleCandidates` (native SQL): visibility disjunction +
  `search_vector @@ plainto_tsquery('english', :query)`, ranked `ts_rank(...) AS score`, ordered
  `score DESC, updated_at DESC, id LIMIT :limit`. Returns `CandidateProjection`.
- `skills.search_vector` (tsvector) is maintained by trigger (A=name, B=description) in
  `V4__skills_schema.sql` and by app-side `rebuildSearchVector(skillId, tagValues, instructions)`
  (adds C=tags, D=instructions). Bulk backfill in `V7__rebuild_search_vectors.sql`.
- Config pattern: `@ConfigurationProperties` records under `alexandria.*` (e.g. `ResolverProperties`
  with a boolean `hookInjectOnPrompt`), registered in `SecurityConfig`'s
  `@EnableConfigurationProperties`. No `@ConditionalOnProperty` beans exist yet.
- `docker-compose.yml` Postgres is `postgres:16-alpine` (no pgvector). pgvector absent everywhere.
- Migrations run to `V8`; next is `V9`.
- Harness: `run_experiment.py` writes `results/raw/<run-id>/env.json` from `experiment.yaml`; the
  alexandria condition drives Claude through `mcp_proxy` and records `resolve_candidates`,
  `consulted`, `retrieval_latency_ms`, `context_token_cost`.

## Architecture

```
                 resolve_skill (MCP)  /  REST AgentResolverResource
                                   │
                                   ▼
                        ResolverService.resolve()
                                   │
                   ┌───────────────┴───────────────┐
                   │   SearchStrategy (by flag)      │   alexandria.search.mode
                   │   fts | semantic | hybrid       │   = fts (default) | semantic | hybrid
                   └───────────────┬───────────────┘
          ┌────────────────────────┼─────────────────────────┐
          ▼                        ▼                          ▼
  FtsCandidateQuery        SemanticCandidateQuery        HybridFusion
  (existing SQL)           (pgvector <=> SQL)            (RRF / weighted blend of the two)
          │                        │                          │
          └── CandidateProjection (identity + governance + score) ──┘
                                   │
                        resolveVersion + toResult (unchanged)

Write path:  Skill create/update ── rebuildSearchVector (FTS, existing)
                                 └─ EmbeddingService.reindex(skillId)
                                       └─ EmbeddingProvider.embed(assembledText) ─▶ skill_embeddings
```

### Why a strategy branch, not conditional beans
The existing codebase has no `@ConditionalOnProperty` beans; it branches on config values in service
code (`hookInjectOnPrompt`). We follow that, but encapsulate each mode as a small `SearchStrategy`
component so the branch is one lookup, strategies are independently testable, and adding a future mode
is additive. All three strategies return `List<CandidateProjection>` so `doResolve` downstream is
unchanged.

## Components

### 1. Feature flag — `SearchProperties`
New `@ConfigurationProperties(prefix = "alexandria.search")` record in `com.alexandria.config`:

```java
@ConfigurationProperties(prefix = "alexandria.search")
public record SearchProperties(
        String mode,              // ALEXANDRIA_SEARCH_MODE: fts | semantic | hybrid; default fts
        Integer embeddingDim,     // must equal DB column dim; default 256 (matches local provider)
        String embeddingProvider, // local | http; default local
        HybridProps hybrid
) {
    public record HybridProps(String fusion, Double ftsWeight, Double semanticWeight, Integer rrfK) {}
    // compact constructor: default mode=fts; validate mode ∈ {fts,semantic,hybrid} else throw
    // (fail-fast, R1.3); default dim=256, provider=local, fusion=rrf, rrfK=60, weights=0.5/0.5
}
```
Registered in `SecurityConfig` `@EnableConfigurationProperties({..., SearchProperties.class})`.
Surfaced in `application.yml` as `${ALEXANDRIA_SEARCH_MODE:fts}` etc. Logged at startup. An
unrecognized mode throws in the compact constructor → Spring context fails to start (R1.3).

### 2. Schema — `V9__semantic_search.sql`
Decision: **separate `skill_embeddings` table**, not a column on `skills`. Reasons: embeddings are
large, nullable during backfill, regenerated independently, and may later be multi-vector per skill
(per section). A side table keeps `skills` lean and the ANN index isolated. It carries
`organization_id` per the steering convention (even though it's derivable via `skill_id`), so the ACL
predicate can filter directly and consistently.

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE skill_embeddings (
    skill_id        UUID PRIMARY KEY REFERENCES skills(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL,
    embedding       vector(256) NOT NULL,     -- dim MUST match SearchProperties.embeddingDim
    model_id        TEXT NOT NULL,            -- provenance for reproducibility (R8)
    source_hash     TEXT NOT NULL,            -- hash of assembled source text; skip re-embed if unchanged
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ANN index. IVFFlat needs ANALYZE + lists tuning; HNSW is better recall out-of-box for small/medium
-- corpora (benchmark is 20–500). Use HNSW with cosine ops.
CREATE INDEX idx_skill_embeddings_hnsw
    ON skill_embeddings USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_skill_embeddings_org ON skill_embeddings(organization_id);
```
Idempotent (`IF NOT EXISTS` on extension; new table). Dimension is pinned to 256 to match the local
provider (`minishlab/potion-base-8M`, dim 256) already used by the experiment — so backend and
benchmark speak the same vector space. If a deployer swaps providers to a different dim, they change
both the env var and a migration; the dim-mismatch check (R3.5) catches drift at startup.

Docker: switch `docker-compose.yml` Postgres from `postgres:16-alpine` to `pgvector/pgvector:pg16`.

### 3. Embedding subsystem — new `com.alexandria.embedding` package
- `EmbeddingProvider` interface: `float[] embed(String text)` + `int dimension()` + `String modelId()`.
- `LocalEmbeddingProvider` (default): deterministic, offline, no vendor lock-in. To avoid a heavy
  Java ML dependency, the pragmatic implementation calls a small sidecar/embedded model. Two options
  to decide in tasks:
  - (a) a tiny local HTTP embedding sidecar in the compose stack (reuses the same model2vec /
    `potion-base-8M` the experiment already pins — maximal benchmark fidelity), or
  - (b) a pure-JVM hashing/bag-of-ngrams embedding (fully in-process, lower fidelity).
  Recommendation: (a) for benchmark fidelity, since the experiment's semantic subsets were built with
  `potion-base-8M`; a mismatched backend embedder would muddy the comparison.
- `HttpEmbeddingProvider`: provider-agnostic REST client; URL/model/key via env (satisfies "never
  hardcode one vendor").
- `EmbeddingService`: assembles source text (name + description + current version's
  canonicalInstructions/applicability/whenToUse/examples — same fields as FTS A/B/C/D), computes
  `source_hash`, skips if unchanged, calls the provider, upserts `skill_embeddings`. Exposes
  `reindex(skillId)` (called alongside `rebuildSearchVector` at the write boundary) and
  `backfillAll(orgId)` (bulk path for existing rows; invoked by a one-off admin command / startup
  runner guarded by mode).

### 4. Retrieval queries — `SkillResolverRepository`
Add a sibling to `findVisibleCandidates`:

```java
@Query(value = """
    SELECT s.id AS skillId, s.current_version_id AS skillVersionId, s.name AS name,
           s.description AS description, s.slug AS slug, s.lifecycle_status AS lifecycleStatus,
           s.risk_level AS riskLevel, s.visibility AS visibility, s.owner_team_id AS ownerTeamId,
           s.deprecation_reason AS deprecationReason,
           1 - (e.embedding <=> CAST(:queryVec AS vector)) AS score
    FROM skills s
    JOIN skill_embeddings e ON e.skill_id = s.id
    WHERE s.organization_id = :orgId
      AND ( <IDENTICAL visibility disjunction as findVisibleCandidates> )
      AND s.lifecycle_status <> 'Blocked'
      AND (:includeArchived = true OR s.lifecycle_status <> 'Archived')
      AND s.current_version_id IS NOT NULL
    ORDER BY e.embedding <=> CAST(:queryVec AS vector), s.updated_at DESC, s.id
    LIMIT :limit
    """, nativeQuery = true)
List<CandidateProjection> findVisibleCandidatesSemantic(..., String queryVec, int limit);
```
`:queryVec` is the query embedding serialized as a pgvector literal (`[0.1,0.2,...]`). The ACL
disjunction is copied verbatim; an integration test (R4.4) asserts parity with the FTS query's
predicate, extending the existing drift-guard. Skills without an embedding are naturally excluded by
the `JOIN` (R4.3), and `EmbeddingService` exposes a count of unembedded visible skills for
observability.

### 5. Search strategies + fusion
`SearchStrategy` interface: `List<CandidateProjection> find(orgId, userId, isOrgAdmin,
includeArchived, query, limit)`.
- `FtsStrategy` → `findVisibleCandidates` (today's behavior, verbatim).
- `SemanticStrategy` → `embeddingService.embed(query)` then `findVisibleCandidatesSemantic`.
- `HybridStrategy` → run both (each over-fetched to e.g. `limit * k`), fuse, trim to `limit`.

Fusion default = **Reciprocal Rank Fusion** (RRF): `score(d) = Σ 1/(rrfK + rank_i(d))` over the two
lists. RRF is rank-based so it needs no score normalization between `ts_rank` and cosine (which live
on different scales) — the right default for a first honest hybrid. A weighted normalized-score blend
is offered as an alternative via `fusion=weighted` (`ftsWeight`/`semanticWeight`), for experiments.
Deterministic tiebreak preserved (updated_at desc, id).

`doResolve` changes from a direct repo call to `strategy.find(...)`, where `strategy` is selected once
from `SearchProperties.mode`. `matchReasons` is updated to report the real mechanism(s) per candidate
(R5.4): FTS-only, semantic-only, or both — tracked through fusion.

### 6. `matchReasons` honesty
Currently hardcodes `"Matched full-text search"`. Replace with the mechanism(s) that surfaced the
candidate so the resolve payload (and the benchmark's captured reasons) are accurate per mode.

## Benchmark integration (R7)
- Add `--search-mode {fts,semantic,hybrid}` to `run_experiment.py` (or a config field). It does NOT
  change corpus/tasks/scoring; it (a) is recorded into `env.json`, and (b) is how the operator sets
  `ALEXANDRIA_SEARCH_MODE` on the backend for that run (documented in the run recipe — the server must
  be started/pointed at the chosen mode; the harness records what it was told).
- The server exposes the active mode (startup log + optionally a small `/actuator`-style field or the
  resolve response capabilities) so the harness can *verify* and record the mode rather than trust the
  operator — closing the "mislabeled run" risk. Minimal version: harness reads mode from a backend
  health/info endpoint and writes it to `env.json`.
- `native` condition is unchanged; the comparison becomes
  `alexandria-{fts,semantic,hybrid}` × `native`, one mode per run.

## Data model summary
New: `skill_embeddings(skill_id PK, organization_id, embedding vector(256), model_id, source_hash,
updated_at)` + HNSW cosine index + org index. No change to `skills`, `skill_versions`,
`skill_variants`.

## Error handling / fail-fast
- Unknown `mode` → context fails at startup (R1.3).
- `embeddingDim` ≠ DB column dim → startup check throws (R3.5).
- `semantic`/`hybrid` mode but extension missing / table empty → startup validation logs loudly;
  empty semantic results are reported as a measurable condition, never a silent crash (R2.5, R4.3).

## Testing strategy
- Unit: `SearchProperties` validation (default, each valid mode, unknown→throw, dim mismatch);
  RRF/weighted fusion given synthetic ranked lists (deterministic ordering, tiebreaks); `EmbeddingService`
  source-text assembly + source_hash skip logic; `LocalEmbeddingProvider` determinism.
- Integration (Testcontainers pgvector): `findVisibleCandidatesSemantic` returns ACL-correct results;
  **ACL parity test** between FTS and semantic predicates (R4.4); unembedded skills excluded; hybrid
  fuses both sources; existing FTS tests unchanged under default mode (R6).
- Regression: full existing resolver + catalog suite passes with mode unset.
- Benchmark smoke: one small subset (e.g. `random-20`) under each mode end-to-end once quota allows,
  confirming mode is recorded in `env.json` and real work happens.

## Rollout / reversibility
- Default `fts` → shipping this is a no-op for existing deployments; the flag opts in.
- Migration `V9` is additive (new table/extension) — safe; a down path would drop the table/extension.
- Docker image bump to `pgvector/pgvector:pg16` is required for anyone running `semantic`/`hybrid`
  locally; plain `postgres:16` deployments must install the extension (documented).

## Open decisions for task breakdown
1. Local embedder: compose sidecar reusing `potion-base-8M` (benchmark-faithful) vs pure-JVM
   (simpler, lower fidelity). Leaning sidecar for fidelity.
2. HNSW vs IVFFlat index (leaning HNSW for the 20–500 range).
3. Synchronous reindex at write time vs deferred backfill-only for this iteration (queue path is out
   of scope).
4. How the harness verifies the server's active mode (health/info endpoint vs resolve capabilities
   field).
