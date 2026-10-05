# Tasks — Semantic Search (pgvector) with Hybrid Retrieval and a Feature Flag

Each task is incremental, test-backed, and leaves the system green. Default mode stays `fts`
throughout, so nothing breaks until a mode is explicitly selected. Build/verify with
`./mvnw test` (unit) and `./mvnw verify` (integration/Testcontainers) per the tech-stack commands.

- [x] 1. Feature flag scaffolding (no behavior change)
  - Add `SearchProperties` `@ConfigurationProperties(prefix = "alexandria.search")` record with
    `mode` (default `fts`), `embeddingDim` (default 256), `embeddingProvider` (default `local`),
    and `HybridProps`. Validate `mode ∈ {fts,semantic,hybrid}` in the compact constructor (unknown →
    throw). Register in `SecurityConfig` `@EnableConfigurationProperties`. Add
    `${ALEXANDRIA_SEARCH_MODE:fts}` + related keys to `application.yml`. Log active mode at startup.
  - _Requirements: R1.1, R1.2, R1.3, R1.5_
  - _Tests: unit for default, each valid mode, unknown→throw._

- [x] 2. SearchStrategy abstraction over existing FTS (still FTS-only at runtime)
  - Introduce `SearchStrategy` interface returning `List<CandidateProjection>`. Implement
    `FtsStrategy` delegating to the existing `findVisibleCandidates` verbatim. Refactor
    `ResolverServiceImpl.doResolve` to call `strategy.find(...)` selected from `SearchProperties.mode`
    (only `fts` wired for now; `semantic`/`hybrid` throw "not yet enabled").
  - _Requirements: R6.1, R6.3_
  - _Tests: existing resolver tests pass unchanged; strategy selection unit test._

- [x] 3. pgvector infra: migration + docker image
  - `V9__semantic_search.sql`: `CREATE EXTENSION IF NOT EXISTS vector`, `skill_embeddings` table
    (skill_id PK, organization_id, embedding vector(256), model_id, source_hash, updated_at), HNSW
    cosine index, org index. Switch `docker-compose.yml` Postgres to `pgvector/pgvector:pg16`.
  - Startup validation: if mode is `semantic`/`hybrid`, assert extension present and
    `embeddingDim` == column dim; fail fast otherwise.
  - _Requirements: R2.1, R2.2, R2.3, R2.4, R2.5, R3.5_
  - _Tests: Testcontainers (pgvector image) migration applies cleanly and is idempotent._

- [x] 4. Embedding provider abstraction
  - `com.alexandria.embedding` package: `EmbeddingProvider` interface (`embed`, `dimension`,
    `modelId`); `LocalEmbeddingProvider` (default — decide sidecar-`potion-base-8M` vs pure-JVM per
    design open-decision #1; leaning sidecar for benchmark fidelity); `HttpEmbeddingProvider`
    (URL/model/key via env). Provider selected by `embeddingProvider` env var.
  - _Requirements: R3.1, R3.2, R8.1_
  - _Tests: LocalEmbeddingProvider determinism; dimension matches config._

- [x] 5. EmbeddingService: source assembly, hashing, upsert, backfill
  - Assemble source text from name + description + current version's
    canonicalInstructions/applicability/whenToUse/examples (mirror FTS A/B/C/D source). Compute
    `source_hash`; skip re-embed when unchanged. Upsert `skill_embeddings`. `reindex(skillId)` wired
    alongside `rebuildSearchVector` at the skill create/update boundary. `backfillAll(orgId)` bulk
    command for existing rows.
  - _Requirements: R3.3, R3.4, R8.2_
  - _Tests: source assembly matches FTS source; hash-skip logic; reindex on update._

- [x] 6. Semantic retrieval query (ACL-correct)
  - Add `findVisibleCandidatesSemantic` to `SkillResolverRepository`: identical visibility
    disjunction + lifecycle/archived predicates, `JOIN skill_embeddings`, order by `embedding <=>
    :queryVec`, `1 - distance AS score`. Serialize query embedding to a pgvector literal.
  - Wire `SemanticStrategy` (embed query → semantic query). Enable `mode=semantic`.
  - _Requirements: R4.1, R4.2, R4.3_
  - _Tests (Testcontainers): ACL-correct results; unembedded skills excluded; **ACL parity test**
    between FTS and semantic predicates (R4.4)._

- [x] 7. Hybrid fusion
  - `HybridStrategy`: over-fetch both FTS and semantic lists, fuse via RRF (default) or weighted
    normalized blend (`fusion=weighted`), trim to `limit`, deterministic tiebreak. Env-configurable
    weights/`rrfK`. Enable `mode=hybrid`.
  - Update `matchReasons` to report the real surfacing mechanism(s) per candidate instead of the
    hardcoded FTS string.
  - _Requirements: R5.1, R5.2, R5.3, R5.4_
  - _Tests: RRF/weighted fusion deterministic ordering + tiebreaks on synthetic lists; matchReasons
    reflects mechanism._

- [x] 8. Backend exposes active mode for benchmark attribution
  - Expose active `search.mode` (startup log already done; add a read path the harness can query —
    health/info endpoint field or resolve-response capability) so a run can be verified+recorded, not
    just trusted.
  - _Requirements: R7.1 (server side), R1.4_

- [x] 9. Harness: per-run mode selection + recording
  - Add `--search-mode {fts,semantic,hybrid}` to `run_experiment.py`; record into `env.json`; read
    the backend's reported active mode and assert it matches (fail the run on mismatch). No change to
    corpus, tasks, or scoring. Document the run recipe (operator sets `ALEXANDRIA_SEARCH_MODE`,
    restarts backend, runs harness with matching `--search-mode`).
  - _Requirements: R7.1, R7.2, R7.3_
  - _Tests: env.json carries mode; mismatch aborts._

- [x] 10. End-to-end verification (quota-gated)
  - Regression: full backend suite green with mode unset (R6). Then, once Claude quota resets, a
    smoke run of one small subset (e.g. `random-20`) under each mode confirming real work + mode
    recorded. (Do NOT run during quota freeze.)
  - _Requirements: R6.1, R6.2, R7_

## Suggested commit boundaries
Tasks 1–2 (flag + strategy seam, FTS-only) is a safe standalone PR. Task 3 (infra) another. Tasks
4–5 (embedding) another. Tasks 6–7 (semantic + hybrid) the core. Tasks 8–9 (benchmark wiring) last.

## Dependencies / sequencing
- 1 → 2 (flag before strategy selection).
- 3 before 6 (schema before semantic query).
- 4 → 5 → 6 (provider → service → query).
- 6 → 7 (semantic before hybrid fusion).
- 8 → 9 (server exposes mode before harness verifies it).
- 10 last, and gated on quota reset for the live portion.
