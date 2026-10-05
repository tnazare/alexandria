# Requirements — Resident Skill Catalog (MCP resource) for high-compliance skill selection

## Context

Alexandria's agent-facing Skill Resolver exposes two MCP tools today: `resolve_skill` (ranked search)
and `fetch_skill` (pull one skill's body). The `skill-retrieval-benchmark` experiment established that
this design loses on the metric that matters — **the agent frequently never calls `resolve_skill`**.
Across runs, consult compliance sat at ~0.48–0.65, and because the server-side ranking was already
near-perfect when invoked (hybrid conditional recall@5 ≈ 0.9–1.0), the whole accuracy deficit was the
missing tool call, not bad ranking. Native `.claude/skills` beat Alexandria on model-choice purely
because its catalog of skill name+description is **resident** in the model's context — selection
requires no tool call, so there is nothing to skip.

The benchmark then validated a fix: a **resident catalog** condition that injects a compact
`id: name — description` line per skill into the agent's context, keeping an on-demand `fetch_skill`
for the body. Results (`experiments/results/run-20261002-095819.md`, `run-20261002-103254.md`):

| Metric | catalog | native | Δ |
|--------|---------|--------|---|
| Model-choice (random-20) | 0.867 | 0.683 | +0.18 |
| Model-choice (sparse-20) | 0.950 | 0.833 | +0.12 |

The win is compliance-driven (model-choice equalled consult compliance for both conditions) and robust
across subsets. The cost is a token premium (catalog ~14–31% more than native, linear in N). A
hierarchical-disclosure variant that tried to make the catalog sub-linear in N **failed** — the extra
"open a category" hop collapsed compliance to 0.38 (`run-20261002-130648.md`), confirming the lever is
specifically *single-step resident selection*.

This spec brings that validated mechanism into the Alexandria product: expose the governed, ACL-scoped
skill catalog as an **MCP resource** the client loads into the agent's context, so selection is
resident, while reusing the existing `fetch_skill` for the governed body. The principle: **make using
a skill the path of least resistance — the choice is already in context; the only action is to fetch
what was chosen.**

## Non-negotiable constraints (from steering)

- **Organization scoping + ACL at the query layer AND application layer.** The catalog listing MUST
  reproduce the exact `organization_id` + visibility disjunction used by `findVisibleCandidates`.
  Private/team/restricted skill names and descriptions MUST never leak to a caller without access.
  A catalog is a *broader* disclosure than a ranked search (it lists everything visible at once), so
  ACL correctness is more safety-critical here, not less.
- **The resolve/fetch contract does not change.** `resolve_skill` and `fetch_skill` keep working
  unchanged; the catalog is additive. `fetch_skill` is the catalog's body-retrieval step as-is.
- **Configuration through environment variables, not code changes.** Enabling the catalog, its size
  cap, and scoping behavior are env-driven via the existing `@ConfigurationProperties` record pattern.
- **Self-hosted, no new infra.** Reuse PostgreSQL and the existing hand-rolled MCP endpoint
  (`McpResource` / `McpToolService`). No new services, no Spring AI dependency.
- **Governed content only.** The catalog MUST surface the same lifecycle/visibility rules the resolver
  enforces (exclude Blocked; exclude Archived unless requested; only skills with a current version).

## Requirements

### R1 — Catalog listing query (ACL-correct, no search term)
**User story:** As an agent client, I want the full set of skills I'm allowed to see as compact
lines, so I can place them resident in the model's context without issuing a search.

Acceptance criteria:
1. There SHALL be a repository method that returns every ACL-visible, non-Blocked skill for the
   caller's organization **without** requiring a full-text/semantic query term (unlike
   `findVisibleCandidates`, which requires an FTS match).
2. The method SHALL carry the **identical** visibility disjunction (`organization` / `team` /
   `private` / `restricted` / `isOrgAdmin` bypass) and the `lifecycle_status <> 'Blocked'`,
   archived-exclusion, and `current_version_id IS NOT NULL` predicates as `findVisibleCandidates`.
3. The projection SHALL be a slim catalog line: `skillId`, `skillVersionId` (= `current_version_id`),
   `name`, `description`, `lifecycleStatus`, `riskLevel`, `visibility` — no instruction/example/body
   fields.
4. Ordering SHALL be deterministic (e.g. `name ASC, id`) so the resident catalog is byte-stable for a
   given org/caller/skill-set, which matters for client-side caching and reproducibility.
5. An integration test SHALL assert this method's ACL predicate stays in parity with
   `findVisibleCandidates` (the same drift-guard approach used between the FTS and semantic queries),
   so a catalog can never surface a skill the resolver would hide.

### R2 — Catalog service method
**User story:** As the MCP layer, I want a service method that returns the governed catalog for an
authenticated identity, so ACL and governance are enforced in one place.

Acceptance criteria:
1. `ResolverService` (or a dedicated `CatalogService`) SHALL expose a method, e.g.
   `catalog(identity, includeArchived, limit)`, returning the catalog lines for that identity.
2. The method SHALL enforce the same org-admin narrow-bypass rule as `doResolve`
   (`identity.role() == Role.Org_Admin`).
3. The method SHALL apply a configurable maximum entry count (R5) and signal truncation when the
   visible set exceeds it, so a huge org cannot produce an unbounded payload.
4. Each catalog line SHALL expose the `skillVersionId` the client passes straight to `fetch_skill`,
   so the catalog → fetch handoff needs no extra lookup.
5. The method SHALL be side-effect free except for an optional audit/usage event recording that a
   catalog was served (see R7).

### R3 — Expose the catalog as an MCP resource
**User story:** As an MCP client, I want to read the catalog as a resource and load it into the
agent's context, so skill selection is resident and requires no tool call.

Acceptance criteria:
1. The MCP `initialize` result SHALL advertise a `resources` capability (today it advertises only
   `tools`).
2. `McpToolService.handle` SHALL support `resources/list` (advertising a single catalog resource,
   e.g. URI `alexandria://catalog`, with name, description, and `text/plain` or `text/markdown`
   mimeType) and `resources/read` (returning the rendered catalog text for the authenticated
   identity).
3. The rendered catalog SHALL be one `id: name — description` line per skill (the format validated by
   the benchmark), optionally annotated with governance (lifecycle/risk) when configured, and SHALL
   carry a short header instructing the agent to select an id and call `fetch_skill`.
4. `resources/read` SHALL enforce the caller's ACL via the same authenticated identity path used by
   `tools/call` (identity from `SecurityContextHolder`; no catalog content for an unauthenticated
   caller).
5. The resource content SHALL use the `skillVersionId` as (or alongside) the catalog id so the agent
   can call `fetch_skill(skillVersionId)` directly from a catalog line.
6. The two existing tools (`resolve_skill`, `fetch_skill`) SHALL remain advertised and unchanged;
   adding resources SHALL NOT alter tool behavior.

### R4 — Feature flag (opt-in, zero default-behavior change)
**User story:** As a deployer, I want the catalog off by default and enabled by env var, so existing
deployments and the "don't preload catalog" stance are unchanged until opted in.

Acceptance criteria:
1. A `@ConfigurationProperties(prefix = "alexandria.catalog")` record SHALL gate the feature with an
   `enabled` flag defaulting to `false`.
2. WHEN `enabled=false` THEN `initialize` SHALL NOT advertise `resources`, and `resources/*` methods
   SHALL return the JSON-RPC method-not-found error — i.e. the server behaves exactly as today.
3. WHEN `enabled=true` THEN the resource capability and `resources/*` handlers SHALL be active.
4. The flag and its tuning (max entries, include-governance annotations, scoping — R5/R6) SHALL be
   env-driven, following the existing record pattern and registered in `SecurityConfig`'s
   `@EnableConfigurationProperties`.
5. The active catalog config SHALL be observable at startup (log) and via the info endpoint (mirrors
   how `search.mode` is surfaced), so a benchmark/run can attribute behavior to catalog on/off.

### R5 — Bounded payload (token-cost guard)
**User story:** As an operator of a large registry, I want the catalog bounded so it cannot blow the
agent's context budget.

Acceptance criteria:
1. A configurable `maxEntries` (default documented, e.g. 500) SHALL cap the number of catalog lines.
2. WHEN the visible set exceeds `maxEntries` THEN the response SHALL be truncated deterministically
   (by the R1.4 ordering) AND SHALL include an explicit note that it was truncated, so the agent and
   operator know the catalog is partial.
3. The resident catalog's token cost grows ~linearly in N (established by the benchmark token model);
   this requirement documents that `maxEntries` is the operator's lever for the context-budget
   tradeoff, and that no sub-linear hierarchical scheme is included (the benchmark showed it harms
   compliance — see `experiments/results/run-20261002-130648.md`).

### R6 — Catalog scoping (optional narrowing)
**User story:** As a deployer with a large registry, I want to optionally narrow the catalog (e.g. to
a team or a set of lifecycle states) so a focused catalog stays within budget and relevance.

Acceptance criteria:
1. The catalog service SHALL accept optional, env-defaulted scoping that narrows the listing within
   the caller's ACL — at minimum a lifecycle-state filter (e.g. only `Company_Approved` /
   `Team_Approved`) — WITHOUT ever widening beyond what the ACL permits.
2. Any scoping SHALL be applied in the query (not post-filtered in Java) so bounds and ordering stay
   correct and the ACL remains query-enforced.
3. Scoping SHALL default to "all ACL-visible, non-Blocked, non-Archived" so the default matches the
   benchmark's resident-catalog condition.

### R7 — Observability and audit
Acceptance criteria:
1. Serving a catalog `resources/read` SHALL be observable (metric/log: entry count, truncated flag,
   caller org) so operators can see catalog size and usage.
2. A catalog read MAY record a lightweight usage/audit event (consistent with how `fetch` records a
   `view`), scoped to the org, with no per-skill `view` spam (one event per catalog read, not per
   line).
3. No private skill name/description SHALL ever appear in logs or audit payloads (log counts/ids, not
   content), consistent with the anti-enumeration posture.

### R8 — No regression
Acceptance criteria:
1. WITH `alexandria.catalog.enabled` unset/false, all existing MCP, resolver, and catalog behavior
   and tests SHALL pass unchanged, and `initialize` SHALL advertise tools-only exactly as today.
2. `resolve_skill` and `fetch_skill` request/response shapes SHALL be byte-unchanged.
3. Fetch, governance, blocked/deprecated handling, and anti-enumeration 404s SHALL be unaffected.

### R9 — Benchmark integration (close the loop)
**User story:** As the experiment, I want to run the catalog condition against the *real* backend (not
just the local simulated catalog server), so the product implementation is validated against the same
tasks.

Acceptance criteria:
1. The harness's existing `catalog` condition SHALL be able to source its resident catalog from the
   backend's `alexandria://catalog` MCP resource (in addition to the current local-server simulation),
   gated by a flag, so the product path is measured end-to-end.
2. The run SHALL record whether the catalog came from the backend resource vs. the local simulation in
   `env.json`, so results are attributable.
3. This integration SHALL NOT change the corpus, tasks, or scoring — only the catalog source — so the
   comparison to native stays controlled.

## Out of scope (this iteration)
- Human catalog UI changes (the resident catalog is an agent-facing MCP resource; the web catalog
  already lists skills via `SkillRepository.findVisibleSkills`).
- Hierarchical / category-based disclosure of the catalog — benchmarked and rejected
  (`run-20261002-130648.md`); not revisited unless a stronger model / coherent-subset retest justifies.
- Per-tool catalog variants (compiling tool-specific lines) — the catalog lists identity+description;
  tool-specific bodies remain a `fetch_skill(tool=...)` concern.
- Push/subscription updates to the catalog resource (MCP `resources/subscribe`); this iteration is
  read-on-demand. Clients re-read to refresh.
- Semantic ordering of catalog lines; R1.4 ordering is lexical/deterministic for stability.
