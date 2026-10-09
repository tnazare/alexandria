# Tasks — Resident Skill Catalog (MCP resource)

Each task is incremental, test-backed, and leaves the system green. The feature is **off by default**
(`alexandria.catalog.enabled=false`) throughout, so nothing changes for existing deployments until a
mode is explicitly enabled. Build/verify with `./mvnw test` (unit) and `./mvnw verify`
(integration/Testcontainers) per the tech-stack commands.

- [x] 1. Feature flag scaffolding (no behavior change)
  - Add `CatalogProperties` `@ConfigurationProperties(prefix = "alexandria.catalog")` record:
    `enabled` (default `false`), `maxEntries` (default 500), `includeGovernance` (default `false`),
    `lifecycleScope` (default `all`; validate ∈ {`all`,`approved_only`} in the compact constructor,
    unknown → throw), `includeArchivedDefault` (default `false`). Register in `SecurityConfig`
    `@EnableConfigurationProperties`. Add `${ALEXANDRIA_CATALOG_ENABLED:false}` + related keys to
    `application.yml`. Log active catalog config at startup.
  - _Requirements: R4.1, R4.2, R4.3, R4.4, R4.5_
  - _Tests: unit for default-off, valid scope values, unknown scope → throw._

- [x] 2. ACL-scoped catalog listing query (no search term)
  - Add `findVisibleCatalogEntries(orgId, userId, isOrgAdmin, includeArchived, approvedOnly, limit)`
    to `SkillResolverRepository`: copy the `findVisibleCandidates` visibility disjunction +
    `lifecycle_status <> 'Blocked'` + archived exclusion + `current_version_id IS NOT NULL` verbatim;
    remove the `search_vector @@ plainto_tsquery` predicate and `ts_rank` score; add the optional
    `approvedOnly` lifecycle predicate; order `s.name ASC, s.id`; `LIMIT :limit`. Add a slim
    `CatalogEntryProjection` (skillId, skillVersionId, name, description, lifecycleStatus, riskLevel,
    visibility) — open-decision #2.
  - _Requirements: R1.1, R1.2, R1.3, R1.4_
  - _Tests (Testcontainers): correct sets per visibility/org-admin; excludes Blocked/Archived/
    version-less; **ACL parity** vs `findVisibleCandidates` (R1.5); deterministic ordering._

- [x] 3. CatalogService (ACL + governance + bounds + scoping)
  - Add `CatalogService.catalog(identity, includeArchived, limit)` returning
    `CatalogResult(entries, truncated, totalReturned)`. Derive `isOrgAdmin` (narrow bypass, as
    `doResolve`); clamp `limit` to `maxEntries`; query `maxEntries + 1` to detect truncation; apply
    `lifecycleScope`; expose each entry's `skillVersionId` for direct fetch. Side-effect free except
    an optional single read event (behind sub-flag — open-decision #3).
  - _Requirements: R2.1, R2.2, R2.3, R2.4, R2.5, R5.1, R5.2, R6.1, R6.2, R6.3_
  - _Tests: truncation math + limit clamp with stubbed repo; org-admin bypass; scope application._

- [x] 4. CatalogRenderer (resident catalog text)
  - Pure `CatalogResult → String`: instruction header; one `"{skillVersionId}: {name} — {description}"`
    line per entry (open-decision #1); optional ` [lifecycle/risk]` when `includeGovernance`;
    truncation note when truncated. `text/markdown`. Deterministic. Match
    `experiments/harness/measure_tokens.catalog_line` format so product == benchmark lines.
  - _Requirements: R3.3, R3.5, R5.2_
  - _Tests: line/header/truncation formatting; determinism; governance annotation on/off._

- [ ] 5. MCP resources capability + resources/list + resources/read (flag-gated)
  - In `McpToolService`: when `catalog.enabled`, add a `resources` capability in `initializeResult()`
    and handle `resources/list` (one `alexandria://catalog` resource) and `resources/read` (validate
    uri; call `CatalogService` with the `SecurityContextHolder` identity; return
    `{contents:[{uri,mimeType,text}]}`). When disabled, no `resources` capability and `resources/*`
    → `-32601` (unchanged behavior). Keep `resolve_skill`/`fetch_skill` untouched.
  - _Requirements: R3.1, R3.2, R3.3, R3.4, R3.5, R3.6, R4.2, R4.3, R8.1, R8.2_
  - _Tests: flag off → tools-only + resources/* → -32601; flag on → list/read return catalog for the
    authenticated identity, nothing for unauthenticated; resolve/fetch byte-unchanged._

- [ ] 6. Info contributor + observability
  - Add `CatalogInfoContributor` publishing `catalog.enabled` (+ `maxEntries`, `lifecycleScope`) on
    `/actuator/info` (mirror `SearchModeInfoContributor`). Add a metric/log on `resources/read`
    (entry count, truncated, caller org — counts/ids only, never content).
  - _Requirements: R4.5, R7.1, R7.3_
  - _Tests: info field present reflects config; read logs counts not content._

- [ ] 7. Harness: source the catalog from the backend resource
  - Add a `--catalog-source {local,backend}` switch to the harness: `local` keeps
    `catalog_server.py`; `backend` reads `alexandria://catalog` via the capture proxy against the
    running stack and injects it as the resident catalog. Record `catalog_source` in `env.json`.
    No change to corpus/tasks/scoring.
  - _Requirements: R9.1, R9.2, R9.3_
  - _Tests: env.json carries catalog_source; backend path parses the resource into catalog lines._

- [ ] 8. End-to-end verification (quota-gated)
  - Regression: full backend suite green with the flag unset (R8). Then, once Claude quota resets, a
    smoke run of `random-20` with `--catalog-source backend` confirming the real resource reproduces
    the resident-catalog compliance behavior and the run is attributed. (Do NOT run during quota
    freeze.)
  - _Requirements: R8.1, R8.2, R8.3, R9_

## Suggested commit boundaries
Task 1 (flag) is a safe standalone PR. Tasks 2–4 (query + service + renderer, all server-side, still
no MCP surface) another. Task 5 (MCP resources) the core surface change. Tasks 6–7 (observability +
harness) another. Task 8 last, gated on quota for the live portion.

## Dependencies / sequencing
- 1 → 5 (flag before the resource surface it gates).
- 2 → 3 → 4 (query → service → renderer).
- 3,4 → 5 (service + renderer before the MCP handlers that call them).
- 5 → 6 (surface before its info/observability).
- 5,6 → 7 (backend resource exists before the harness reads it).
- 8 last, gated on quota reset for the live portion.

## Notes carried from the benchmark (why this shape)
- The win is **single-step resident selection**; do not add a category/hierarchy hop (benchmarked,
  collapsed compliance to 0.38 — `experiments/results/run-20261002-130648.md`).
- The catalog's token cost is ~linear in N; `maxEntries` + optional `lifecycleScope` are the
  context-budget levers (R5, R6), not sub-linear disclosure.
- The resident-ness is partly the MCP client's behavior (it must load the resource into context);
  task 8 measures the real resource end-to-end to confirm the product path matches the simulation.
