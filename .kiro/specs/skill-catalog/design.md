# Design — Resident Skill Catalog (MCP resource)

## Overview

Expose Alexandria's governed, ACL-scoped skill catalog as an **MCP resource** that an agent client
loads into the model's context, so skill *selection* is resident (no tool call to skip) while skill
*bodies* are still pulled on demand via the existing `fetch_skill`. The change is additive and
confined to: one new ACL-scoped repository query (list without a search term), a thin catalog service
method, a feature-flag config record, and `resources/*` support in the hand-rolled MCP dispatcher. The
`resolve_skill`/`fetch_skill` tools and all other adapters are untouched. The feature is **off by
default**, so existing deployments and the current "tools-only, don't preload catalog" stance are
unchanged until a deployer opts in.

This productizes the benchmark-validated `catalog` mechanism
(`experiments/results/run-20261002-095819.md`, `run-20261002-103254.md`): resident
`id: name — description` lines beat native on model-choice (+0.12 to +0.18) because selection becomes
the path of least resistance. The rejected hierarchical variant (`run-20261002-130648.md`) is
deliberately not built — single-step resident selection is the whole point.

## Current state (verified in code)

- MCP endpoint: `McpResource` (`backend/alexandria-service/.../agent/mcp/McpResource.java`),
  `@RequestMapping("/api/agent/v1/mcp")`, hand-rolled JSON-RPC 2.0 over Streamable-HTTP (POST only;
  id-less messages → 202). Identity comes from `SecurityContextHolder` (`AlexandriaAuthentication` →
  `AlexandriaUserDetails`); both OIDC user tokens and `alx_` service tokens authenticate and ACL
  flows through.
- Dispatcher: `McpToolService.handle` switches on `method`: `initialize`, `tools/list`,
  `tools/call`, `notifications/initialized`/`ping`, default → `-32601`. `initializeResult()`
  advertises **only** a `tools` capability (`caps.putObject("tools")`). Two tools are hand-defined:
  `resolve_skill` and `fetch_skill`.
- Resolver core: `ResolverServiceImpl.doResolve` → `SearchStrategy` →
  `SkillResolverRepository.findVisibleCandidates` (and `...Semantic`). `fetch` →
  `doFetch(skillVersionId, tool, format, source, identity)` loads version org-scoped, re-checks
  per-user visibility, rejects Blocked (403), renders `agent_skill`/`alexandria_native`, records a
  `view` usage event.
- ACL/visibility SQL lives in `SkillResolverRepository.findVisibleCandidates` (native): the
  `organization`/`team`/`private`/`restricted` disjunction + `isOrgAdmin` bypass +
  `lifecycle_status <> 'Blocked'` + archived exclusion + `current_version_id IS NOT NULL`. The
  semantic sibling copies this disjunction verbatim; an integration test guards parity. **There is no
  query that lists all visible skills without an FTS match** — `findVisibleCandidates` requires
  `search_vector @@ plainto_tsquery(:query)`.
- Data model: `SkillEntity` carries `name`, `slug`, `description`, `lifecycleStatus`, `visibility`,
  `riskLevel`, `currentVersionId`, `ownerTeamId`, `createdBy` on the `skills` table directly, plus
  `organizationId` from `BaseEntity`. Every catalog-line field exists on `skills`; no join to
  `skill_versions` is needed, and the fetch id = `current_version_id`.
- Projection shape: `CandidateProjection` already exposes exactly `skillId`, `skillVersionId`,
  `name`, `description`, `slug`, `lifecycleStatus`, `riskLevel`, `visibility`, `ownerTeamId`,
  `deprecationReason`, `score`.
- Config pattern: `@ConfigurationProperties` records under `alexandria.*` (e.g. `SearchProperties`
  with a validated `mode`), registered in `SecurityConfig`'s `@EnableConfigurationProperties`.
- Info surface: `SearchModeInfoContributor` publishes `search.mode` on `/actuator/info`; the harness
  reads it to verify a run. The catalog mirrors this for its on/off state.
- Benchmark: `experiments/harness/catalog_server.py` simulates the catalog with a local MCP server;
  `run_catalog.py` injects the catalog into the system prompt. R9 points this at the real backend
  resource.

## Architecture

```
            MCP client (Claude Code / Cursor / Kiro / Copilot)
                 │                                   │
   resources/read alexandria://catalog        tools/call fetch_skill(skillVersionId)
                 │                                   │
                 ▼                                   ▼
        McpToolService.handle  ───────────────────────────────────► ResolverService.fetch
         (resources/list, resources/read)                           (UNCHANGED body pull)
                 │
                 ▼
        CatalogService.catalog(identity, includeArchived, limit)
                 │   (ACL + governance + maxEntries + scoping)
                 ▼
   SkillResolverRepository.findVisibleCatalogEntries(orgId, userId, isOrgAdmin,
                                                     includeArchived, lifecycleScope, limit)
                 │   (SAME visibility disjunction as findVisibleCandidates, NO FTS predicate)
                 ▼
        CatalogEntry projection (skillId, skillVersionId, name, description,
                                 lifecycleStatus, riskLevel, visibility)
                 │
                 ▼
        CatalogRenderer → "id: name — description" lines + header  (text/markdown)

Flag:  alexandria.catalog.enabled=false (default) → initialize advertises tools only;
       resources/* → -32601 (exactly today's behavior).
       enabled=true → resources capability + resources/list + resources/read active.
```

### Why an MCP resource, not a third tool

Three options were weighed:

1. **MCP resource (chosen).** A resident catalog is semantically a *readable document the client
   loads into context*, which is exactly what MCP "resources" are for. It keeps selection resident
   (the client inlines the resource) with no per-task tool call — matching the benchmark's mechanism.
   It is opt-in per client (clients choose which resources to load) and per deployer (the flag).
2. **A third `list_skills` tool.** Least code, but it is a *tool call*, reintroducing the
   skip-the-call failure mode the catalog exists to remove, and it contradicts the explicit
   "advertise only two tools so clients never preload catalog content" design note in `McpResource`.
   A client could call it once at startup, but that is a client convention we cannot rely on.
3. **Server-pushed system-prompt injection.** Not possible over MCP — the server cannot write the
   client's system prompt. The benchmark simulated this; in the product the client must do the
   loading, which is precisely what resources enable.

The resource choice has one honest dependency (see Risks): the *resident-ness* is ultimately the
client's behavior. The server offers the catalog; the client must load it into context. Different MCP
clients handle resources differently, so the compliance win depends partly on client behavior — which
is why R9 closes the loop by measuring the real resource end-to-end.

### Why no hierarchical disclosure

The benchmark tested a category-navigation variant to make the catalog sub-linear in N and it
collapsed compliance to 0.38 — the extra "open a category" hop gave the model a place to disengage.
The design therefore keeps a flat, single-step catalog and uses `maxEntries` + optional scoping (R5,
R6) as the context-budget lever instead of hierarchy.

## Components

### 1. `CatalogProperties` (config, alexandria-service)
`@ConfigurationProperties(prefix = "alexandria.catalog")` record:
- `enabled` (boolean, default `false`) — master gate.
- `maxEntries` (int, default 500) — payload cap (R5).
- `includeGovernance` (boolean, default `false`) — annotate lines with lifecycle/risk.
- `lifecycleScope` (enum/string, default `all`) — e.g. `all` | `approved_only` (R6).
- `includeArchivedDefault` (boolean, default `false`).
Validated in the compact constructor (unknown `lifecycleScope` → throw, fail-fast). Registered in
`SecurityConfig`'s `@EnableConfigurationProperties`. Env keys like `${ALEXANDRIA_CATALOG_ENABLED:false}`
in `application.yml`.

### 2. Repository: `findVisibleCatalogEntries` (alexandria-entities)
New native query on `SkillResolverRepository`, derived from `findVisibleCandidates` by **deleting the
FTS predicate and score/limit-by-rank** and selecting a slim projection:
- Keep verbatim: the `organization_id = :orgId` filter, the full visibility disjunction (`organization`
  / `team` subquery on `team_members` / `private` / `restricted` with `skill_access_grants` / plus
  `:isOrgAdmin` bypass), `lifecycle_status <> 'Blocked'`, archived exclusion, `current_version_id IS
  NOT NULL`.
- Remove: `search_vector @@ plainto_tsquery(:query)` and `ts_rank(...) AS score`.
- Add: optional `lifecycleScope` predicate (e.g. `(:approvedOnly = false OR s.lifecycle_status IN
  ('Company_Approved','Team_Approved'))`).
- Order: `s.name ASC, s.id` (deterministic, R1.4). `LIMIT :limit` where `limit = maxEntries + 1` so
  the service can detect truncation.
- Projection: a new `CatalogEntryProjection` (or reuse `CandidateProjection` with `score` null) with
  `skillId`, `skillVersionId`, `name`, `description`, `lifecycleStatus`, `riskLevel`, `visibility`.

ACL parity is enforced by an integration test that asserts the catalog query and
`findVisibleCandidates` return the same visible skill set for a battery of identities/visibility
combinations (R1.5), mirroring the existing FTS↔semantic parity guard.

### 3. `CatalogService` (alexandria-service)
A small service (or methods on `ResolverService`) exposing:
```
CatalogResult catalog(AlexandriaUserDetails identity, boolean includeArchived, Integer limit)
```
- Derives `isOrgAdmin = identity.role() == Role.Org_Admin` (same narrow bypass as `doResolve`).
- Resolves effective `limit = min(requested or maxEntries, maxEntries)`, queries `maxEntries + 1`,
  sets `truncated = (rows > maxEntries)`, trims to `maxEntries`.
- Applies `lifecycleScope` from config.
- Returns `CatalogResult(entries, truncated, totalReturned)` where each entry carries the
  `skillVersionId` for direct `fetch_skill` (R2.4).
- Records one usage/audit event per read (R7.2), org-scoped, counts only.

### 4. `CatalogRenderer` (alexandria-service)
Pure function `CatalogResult → String`:
- Header: a short instruction block ("These are reusable governed skills as `id: name — description`.
  Pick the id that fits the task and call fetch_skill with it.").
- One line per entry: `"{skillVersionId}: {name} — {description}"` (id is the version id so the agent
  can fetch directly — R3.5). When `includeGovernance`, append ` [lifecycle/risk]`.
- Truncation note when `truncated` (R5.2).
Rendered as `text/markdown`. Deterministic given the entries (R1.4). The format mirrors
`experiments/harness/measure_tokens.catalog_line` so product and benchmark lines match.

### 5. MCP `resources/*` in `McpToolService`
- `initializeResult()`: when `catalog.enabled`, add `caps.putObject("resources")` alongside `tools`.
- `handle()`: add cases `resources/list` and `resources/read` **guarded by `catalog.enabled`** (when
  disabled, fall through to the default `-32601`, so behavior is identical to today — R4.2, R8.1).
- `resources/list`: return one resource `{ uri: "alexandria://catalog", name: "Skill Catalog",
  description: ..., mimeType: "text/markdown" }`.
- `resources/read`: validate the requested `uri` is `alexandria://catalog`; call
  `CatalogService.catalog(identity, ...)`; return `{ contents: [{ uri, mimeType, text }] }` with the
  rendered catalog. Identity is the same `SecurityContextHolder` principal `tools/call` uses (R3.4);
  an unauthenticated caller yields no content.

### 6. Info contributor
A `CatalogInfoContributor` publishing `catalog.enabled` (+ `maxEntries`, `lifecycleScope`) on
`/actuator/info`, mirroring `SearchModeInfoContributor`, so a run can attribute behavior and the
harness can verify (R4.5, R9.2).

### 7. Harness integration (experiments)
`run_catalog.py` gains a catalog-source switch: `local` (today's `catalog_server.py` simulation) or
`backend` (read `alexandria://catalog` via the capture proxy against the running stack). Recorded in
`env.json` as `catalog_source` (R9.2). Corpus/tasks/scoring unchanged (R9.3).

## Data flow (resident selection, then fetch)

1. Client connects, `initialize` → sees `resources` capability (flag on).
2. Client `resources/list` → one `alexandria://catalog` resource.
3. Client `resources/read` → rendered `id: name — description` lines (ACL-scoped), loads them into the
   agent's context (resident).
4. Agent reads the task, picks a line's id (= `skillVersionId`) from resident context — no tool call
   to decide.
5. Agent `tools/call fetch_skill(skillVersionId)` → existing `ResolverService.fetch` returns the
   governed body. Done.

The only tool call is the fetch of the already-chosen skill — the compliance-friendly step.

## Security & ACL

- The catalog is a **broad** disclosure (all visible skills at once), so query-layer ACL is the
  primary control. The new query copies the proven disjunction verbatim; the R1.5 parity test is the
  guard against drift. No post-filtering in Java widens the set.
- `resources/read` runs under the authenticated identity exactly like `tools/call`; service tokens
  and user tokens both resolve to an `AlexandriaUserDetails` with an org and role.
- Anti-enumeration: Blocked and non-visible skills are absent from the query, so their
  names/descriptions never render. Logs/audit record counts and ids, never content (R7.3).
- Org-admin bypass is identical to the resolver's narrow bypass; no new privilege path.

## Testing strategy

- **Unit:** `CatalogProperties` validation (default off; invalid `lifecycleScope` throws);
  `CatalogRenderer` line/header/truncation formatting + determinism; `CatalogService` truncation
  math and limit clamping (with a stubbed repository).
- **Integration (Testcontainers):** `findVisibleCatalogEntries` returns correct sets for
  organization/team/private/restricted/org-admin identities; excludes Blocked, Archived (unless
  asked), and version-less skills; **ACL parity** vs `findVisibleCandidates` (R1.5); deterministic
  ordering.
- **MCP contract:** with flag off, `initialize` advertises tools-only and `resources/*` → `-32601`
  (R8.1); with flag on, `resources/list`/`resources/read` return the catalog for the authenticated
  identity and nothing for an unauthenticated one; `resolve_skill`/`fetch_skill` byte-unchanged (R8.2).
- **Benchmark (quota-gated):** harness `catalog_source=backend` run on `random-20` reproduces the
  resident-catalog compliance behavior against the real resource.

## Open decisions
1. **Catalog id = `skillVersionId` vs `slug`.** Using `skillVersionId` lets the agent call
   `fetch_skill` with no lookup (R3.5) and pins the exact version; a slug is more human-readable but
   needs a resolve step. Leaning `skillVersionId` for the direct handoff; could render both
   (`slug` as display, versionId as the fetch id).
2. **`CatalogEntryProjection` vs reusing `CandidateProjection`.** A dedicated projection is cleaner
   (no null `score`), but reusing `CandidateProjection` means zero new DTO and the renderer already
   knows the shape. Leaning dedicated projection for clarity.
3. **One usage event per read vs none.** R7.2 allows a single catalog-read event; decide whether it
   adds signal worth the write, or whether a metric/log suffices (leaning metric/log only to avoid
   audit noise, with the event behind a sub-flag).
4. **Resource `uri` scheme.** `alexandria://catalog` is proposed; confirm it doesn't collide with any
   client expectations and whether to parameterize (e.g. `alexandria://catalog?scope=approved`).
