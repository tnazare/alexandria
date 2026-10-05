# Requirements — Semantic Search (pgvector) with Hybrid Retrieval and a Feature Flag

## Context

Alexandria's agent-facing Skill Resolver (`resolve_skill`) and human catalog search both run on
PostgreSQL full-text search (FTS) today: a single `skills.search_vector tsvector` column, queried
with `plainto_tsquery('english', ...)` and ranked by `ts_rank`. The benchmark (see the
`skill-retrieval-benchmark` spec and `experiments/results/`) found Alexandria's FTS resolver has poor
recall on verbose/natural-language queries — the `consulted=True` but `cand=[]` empty-result misses
seen on `sparse-semantics-20` (t008, t013, t019) and `close-semantics-20` (t018). This is the Phase 2
"Hybrid Search" work from the product roadmap, pulled forward because the benchmark needs it as a
measurable, toggleable variable.

The goal is to add semantic (vector) retrieval using pgvector **alongside** the existing FTS, combine
them into a hybrid ranking, and gate the whole thing behind a feature flag — so the benchmark can run
the identical corpus and tasks under `search mode = fts | semantic | hybrid` and measure the gain or
loss. The flag design must generalize to future toggles (model swap, embedding provider swap, other
features) so the experiment can keep attributing changes to one variable at a time.

## Non-negotiable constraints (from steering)

- **Organization scoping + ACL at the query layer AND application layer.** Any semantic query must
  reproduce the exact `organization_id` + visibility disjunction used by FTS. Private skill
  names/metadata must never leak through semantic ranking.
- **Embedding provider abstraction — never hardcode one model vendor.** The embedding source must be
  an interface with at least a local/offline implementation and a pluggable remote one, selected by
  env var.
- **Configuration through environment variables, not code changes.** The flag and all tuning
  (weights, dimensions, provider) are env-driven, following the existing `@ConfigurationProperties`
  record pattern (`ResolverProperties`).
- **Self-hosted, no new infra until justified.** Reuse PostgreSQL via pgvector; no separate vector
  DB. The only infra change is the Postgres image gaining the `vector` extension.
- **The resolve/fetch contract does not change.** Adapters (MCP `resolve_skill`, REST
  `AgentResolverResource`) stay untouched; the strategy branch lives behind `ResolverService`.

## Requirements

### R1 — Feature flag for retrieval mode
**User story:** As a deployer/experimenter, I want to select the retrieval mode by environment
variable so I can switch FTS / semantic / hybrid without code changes or redeploying new images.

Acceptance criteria:
1. WHEN the env var (e.g. `ALEXANDRIA_SEARCH_MODE`) is unset THEN the system SHALL default to `fts`
   (current behavior — zero behavior change on existing deployments).
2. WHEN the value is `fts`, `semantic`, or `hybrid` THEN the resolver SHALL use that strategy.
3. WHEN the value is unrecognized THEN the system SHALL fail fast at startup with a clear message
   (not silently fall back), so a misconfigured experiment run is never mislabeled.
4. The active mode SHALL be observable at runtime (log at startup; and surfaced to the benchmark —
   see R7) so a run can be attributed to the correct mode.
5. The flag SHALL follow the existing `@ConfigurationProperties(prefix = "alexandria.search")` record
   pattern and be registered in `SecurityConfig`'s `@EnableConfigurationProperties`.

### R2 — pgvector schema and extension
**User story:** As an operator, I want the vector storage provisioned by migration so deployment
stays "run the app, Flyway handles schema."

Acceptance criteria:
1. A new Flyway migration (`V9`) SHALL `CREATE EXTENSION IF NOT EXISTS vector`.
2. The migration SHALL add embedding storage keyed to a skill and carrying `organization_id`
   (either a column on `skills` or a `skill_embeddings` table — see design) with the vector dimension
   fixed and documented.
3. The migration SHALL create an ANN index (IVFFlat or HNSW) on the embedding, and SHALL be
   idempotent / safe to run on an existing populated DB.
4. The local `docker-compose.yml` Postgres image SHALL be switched to a pgvector-enabled image so
   `CREATE EXTENSION vector` succeeds in local dev and in the benchmark stack.
5. WHEN pgvector is unavailable at startup AND mode is `semantic`/`hybrid` THEN the system SHALL fail
   fast with a clear message rather than returning empty results.

### R3 — Embedding generation with provider abstraction
**User story:** As a platform owner, I want skills embedded from the same text FTS already indexes,
via a swappable provider, so I'm not locked to one model vendor.

Acceptance criteria:
1. There SHALL be an `EmbeddingProvider` interface producing a fixed-dimension vector for input text.
2. There SHALL be at least two implementations selectable by env var: a local/offline one (for
   self-hosted default and reproducible benchmarks) and a remote HTTP one (provider-agnostic, URL +
   model + key via env). Default is the local one.
3. Embedding source text SHALL mirror the FTS A/B/C/D weighting source: skill `name`, `description`,
   current version's `canonicalInstructions`, `applicability`, `whenToUse`, `examples` — assembled
   the same way `rebuildSearchVector` / `V7` does, so FTS and semantic see the same text.
4. Embeddings SHALL be (re)generated when a skill's indexed text changes — at the same create/update
   boundary that calls `rebuildSearchVector`, plus a bulk backfill path for existing rows.
5. The embedding dimension SHALL be a configured constant that MUST match the DB column dimension;
   a mismatch SHALL fail fast.

### R4 — Semantic retrieval query, ACL-correct
**User story:** As an agent, I want `resolve_skill` to find skills by meaning, not just keyword, while
never seeing skills I'm not allowed to.

Acceptance criteria:
1. There SHALL be a repository method that ranks candidates by vector distance (`<=>`) and carries
   the **identical** visibility disjunction + lifecycle/archived predicates as
   `findVisibleCandidates`.
2. The semantic query SHALL return the same `CandidateProjection` shape (identity, governance,
   ranking) with a score derived from vector similarity, so downstream result-building is unchanged.
3. Skills with no embedding yet SHALL be excluded from semantic ranking (not crash) and the condition
   SHALL be observable (log/metric), so partial-backfill states are detectable.
4. An integration test SHALL assert the semantic query's ACL predicate stays in parity with the FTS
   query's (same drift-guard approach already used between the two FTS queries).

### R5 — Hybrid ranking
**User story:** As an experimenter, I want a hybrid mode that combines FTS and semantic so I can
measure whether the combination beats either alone.

Acceptance criteria:
1. Hybrid mode SHALL combine the FTS candidate set and the semantic candidate set into a single
   ranked list using a documented, deterministic fusion (e.g. Reciprocal Rank Fusion or a
   configurable weighted blend of normalized scores).
2. Fusion parameters (weights / RRF k) SHALL be env-configurable with sane defaults.
3. The fused result SHALL respect `limit` and the same deterministic tiebreak discipline as FTS
   (stable ordering for reproducibility).
4. `matchReasons` SHALL reflect the actual mechanism(s) that surfaced each candidate (currently
   hardcoded `"Matched full-text search"`), so the resolve payload is honest about why a skill ranked.

### R6 — No regression to existing behavior
Acceptance criteria:
1. WITH the flag defaulted/unset, all existing resolver and catalog behavior and tests SHALL pass
   unchanged.
2. Fetch, governance, blocked/deprecated handling, and anti-enumeration 404s SHALL be unaffected by
   retrieval mode.
3. Existing FTS performance SHALL not regress when mode is `fts`.

### R7 — Benchmark integration
**User story:** As the experiment, I want each run tagged with the retrieval mode so results compare
cleanly across modes.

Acceptance criteria:
1. The retrieval mode active on the server SHALL be captured in the run's `env.json` (so a run is
   attributable to `fts` / `semantic` / `hybrid`).
2. The harness SHALL support selecting/recording the mode per run (CLI flag or config field) without
   changing the corpus, tasks, or scoring, so only one variable moves.
3. Mode SHALL be a controlled variable: both the alexandria and (unchanged) native conditions keep
   running; only the alexandria retrieval strategy changes. The benchmark compares alexandria-fts vs
   alexandria-semantic vs alexandria-hybrid (and each against native).

### R8 — Reproducibility of embeddings
Acceptance criteria:
1. The local embedding provider and its model/version SHALL be pinned and recorded, so a benchmark
   run's semantic results are reproducible (mirrors how the experiment already pins
   `minishlab/potion-base-8M` locally).
2. Re-embedding the same corpus with the same provider/version SHALL produce stable rankings.

## Out of scope (this iteration)
- Semantic search in the human catalog UI (`SkillRepository.findVisibleSkills`) — the agent resolver
  is the benchmark target; catalog is a documented follow-up seam.
- Asynchronous/queue-based embedding generation via SQS (the roadmap's background job). This iteration
  may embed synchronously at write time + a bulk backfill command; the queue path is a later task.
- Cross-encoder re-ranking, learned fusion weights, query expansion.
