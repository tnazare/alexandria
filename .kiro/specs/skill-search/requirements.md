# Requirements Document

## Introduction

This specification covers **Milestone 2c — Skill Search**. It completes the Phase 1 Governed
Skill Registry by making skills meaningfully discoverable: extending the existing
PostgreSQL full-text search (FTS) to cover tags and canonical instructions, adding
relevance-ranked ordering, wiring all filter dimensions end-to-end through the backend, and
unifying the two divergent frontend search UIs into a single, coherent experience.

Phase 1 (Milestone 2a/2b) left the following search gaps:

- The `search_vector` trigger only indexes skill `name` (weight A) and `description` (weight B);
  tag values and canonical instructions are not indexed.
- Results are always ordered by `updated_at DESC` regardless of search relevance.
- The backend `GET /api/skills` endpoint accepts `visibility` in the API signature but silently
  ignores it; `riskLevel` has no parameter at all; taxonomy-based filters (`taskType`, `language`,
  `framework`, `tool`) are not wired to any backend predicate.
- Two separate frontend search layers exist (`src/browser/` catalog and `src/pages/skills/`
  authoring list) with divergent filter models and different API modules; the catalog sends eight
  filter params that the backend ignores entirely.
- The native `findVisibleSkills` query has no explicit `countQuery` annotation, forcing Spring
  Data to run a COUNT query derived from the full pagination SQL rather than a lean count.

This milestone fixes all of these gaps within Phase 1's constraint: **PostgreSQL full-text
search only** (no Elasticsearch, no pgvector). Phase 2 will add semantic (embedding-based)
search; this milestone lays the foundation cleanly so Phase 2 can extend it without
rewriting it.

## Glossary

- **Search_Vector**: The `tsvector` column on the `skills` table, maintained by a DB trigger.
  After this milestone: name (A), description (B), tags (C), canonical instructions (D).
- **Taxonomy_Filter**: A filter that narrows results by a specific taxonomy tag type and value
  (e.g. `taskType=code-review`, `language=python`). Resolved to a tag ID join at query time.
- **Relevance_Score**: A `ts_rank` score computed over the `search_vector` when a `query`
  parameter is present, used to order results by match quality instead of recency.
- **Dual_Sort**: The ordering strategy: when a free-text query is present, results are ordered
  by `Relevance_Score DESC, updated_at DESC`; when no query is present (browse/filter mode),
  results are ordered by `updated_at DESC`.
- **Filter_Dimension**: One of the filterable axes: status, visibility, riskLevel, teamId,
  taskType, language, framework, tool (taxonomy dimensions), plus the free-text query.
- **Unified_Search_API**: The single `GET /api/skills` endpoint that handles both free-text
  search and faceted filtering through a shared parameter set.
- **Browse_Layer**: The read-only skill catalog in `src/browser/` used by Skill_Consumers.
- **Authoring_Layer**: The skill management UI in `src/pages/skills/` used by Skill_Authors
  and admins.

## Requirements

### Requirement 1: Extended Full-Text Index

**User Story:** As a Skill_Consumer, I want to search by keywords that appear in tag values and
skill instructions, not just the name and description, so that I can find skills by the concepts
they cover even if those words don't appear in the title.

#### Acceptance Criteria

1. WHEN a skill's tags are added, updated, or removed, THE Registry SHALL update the skill's
   `search_vector` to include all active tag values for that skill at weight 'C' and SHALL NOT
   require a manual reindex — the rebuild SHALL occur automatically in the same database
   transaction as the tag change.
2. WHEN a skill's canonical instructions change (on in-place draft edit or new-version creation),
   THE Registry SHALL update the skill's `search_vector` to include the canonical instructions of
   the **current version** at weight 'D' in the same database transaction as the content update.
3. WHEN a skill's current-version pointer changes (i.e. after a versioned edit creates a new
   version and resets `current_version_id`), THE Registry SHALL re-index the **new** version's
   canonical instructions at weight 'D' and SHALL NOT retain the previous version's instruction
   content in the vector.
4. WHEN a `search` query matches a lexeme that appears in both the name and the instructions of a
   skill, THE Registry SHALL rank a skill where the lexeme appears in the name ('A') higher than a
   skill where it appears only in the instructions ('D'), using the four-weight order:
   name ('A') > description ('B') > tags ('C') > canonical instructions ('D').
5. THE Registry SHALL ensure that the existing DB trigger continues to maintain name (weight 'A')
   and description (weight 'B') in the `search_vector`; tag values (weight 'C') and canonical
   instructions (weight 'D') SHALL be applied by the application layer in the same transaction so
   that the complete four-weight vector is always consistent after any write.
6. IF the canonical instructions of the current version are null or blank, THE Registry SHALL omit
   weight 'D' from the search vector without error, so that an empty-instructions skill is still
   searchable by name, description, and tags.
7. IF a skill has no current version (i.e. `current_version_id` is null), THE Registry SHALL
   rebuild the search vector with weights 'A', 'B', and 'C' only and SHALL NOT raise an error.

### Requirement 2: Relevance-Ranked Ordering

**User Story:** As a Skill_Consumer, I want search results ordered by how well they match my
query, not just by when they were last updated, so that the most relevant skills appear at the
top.

#### Acceptance Criteria

1. WHEN a `search` parameter is present and non-blank in a list request, THE Registry SHALL order
   results by `ts_rank(search_vector, plainto_tsquery('english', search), 1) DESC` as the primary
   sort, with `updated_at DESC` as the tiebreaker.
2. WHEN no `search` parameter is present or the `search` parameter is blank, THE Registry SHALL
   order results by `updated_at DESC`, preserving current browse behavior with no change.
3. THE Relevance_Score SHALL use normalization divisor `1` (divide rank by document length) so
   that skills with longer canonical instructions do not outrank shorter, equally relevant skills
   purely due to document size.
4. THE relevance ordering SHALL be applied inside the SQL query rather than in application code
   so that page 2 of a ranked query is consistent with page 1 — i.e. a skill cannot appear on
   both pages or be skipped between them due to application-layer reordering.
5. IF two or more skills have the same `ts_rank` score for a given query, THE Registry SHALL
   break ties by `updated_at DESC` so that results are deterministic across identical requests.

### Requirement 3: Unified Filter Dimensions

**User Story:** As a Skill_Consumer and Skill_Author, I want to filter skills by status,
visibility, risk level, owning team, and taxonomy dimensions (task type, language, framework,
tool) — and have those filters actually take effect — so that I can narrow results to exactly the
skills I need.

#### Acceptance Criteria

1. THE `GET /api/skills` endpoint SHALL accept and apply all of the following optional filter
   parameters, combining them with AND semantics:
   - `search` (string): full-text query passed to `plainto_tsquery`
   - `status` (string): lifecycle status exact-match filter (Draft, Published, Team_Approved, etc.)
   - `visibility` (string): visibility exact-match filter (private, team, organization, restricted)
   - `riskLevel` (string): risk level exact-match filter (low, medium, high, restricted)
   - `teamId` (UUID): owner team exact-match filter
   - `taskType` (string): tag value filter for taxonomy entries of tag_type = 'task_type'
   - `language` (string): tag value filter for taxonomy entries of tag_type = 'language'
   - `framework` (string): tag value filter for taxonomy entries of tag_type = 'framework'
   - `tool` (string): tag value filter for taxonomy entries of tag_type = 'tool'
2. WHEN `visibility` is supplied as a filter, THE Registry SHALL apply it as an additional
   predicate on top of the existing per-user visibility enforcement, so that a user with full org
   access who requests `visibility=team` sees only team-scoped skills (intersected with their
   normal access).
3. WHEN `riskLevel` is supplied as a filter, THE Registry SHALL return only skills whose
   `risk_level` column exactly matches the requested value. Skills with a null `risk_level` SHALL
   be excluded from the result when any `riskLevel` filter is active.
4. WHEN a taxonomy dimension filter (`taskType`, `language`, `framework`, or `tool`) is supplied,
   THE Registry SHALL return only skills that have at least one active taxonomy tag of the
   matching `tag_type` whose value matches the supplied string case-insensitively. IF the
   supplied value does not match any active taxonomy entry in the organization, the result set
   SHALL be empty rather than an error.
5. ALL filter parameters accepted by `SkillApi.listSkills` SHALL be passed without modification
   through `SkillResource` and `SkillServiceImpl` to the `findVisibleSkills` repository query.
   No filter parameter SHALL be silently dropped or ignored at any layer.
6. IF an unknown or invalid `status`, `visibility`, or `riskLevel` value is supplied, THE
   Registry SHALL return an empty result set (HTTP 200, `totalElements: 0`) rather than HTTP 400,
   because these are permissive faceted filters, not strict API inputs.
7. THE `includeArchived` parameter SHALL continue to function as before: when absent or `false`,
   Archived skills are excluded from results unless `status=Archived` is explicitly requested.
8. WHEN a blank string is supplied for any filter parameter, THE Registry SHALL treat it as if
   the parameter were absent (i.e. no filtering on that dimension).

### Requirement 4: Count Query Optimization

**User Story:** As an operator, I want pagination to remain fast even with complex filter
combinations, so that search pages load within the target latency.

#### Acceptance Criteria

1. THE `findVisibleSkills` repository method SHALL have an explicit `countQuery` annotation
   containing a `SELECT COUNT(*)` statement so that Spring Data uses the lean count query for
   pagination totals rather than deriving a count from the full projection query.
2. THE explicit count query SHALL apply exactly the same WHERE predicates as the data query
   (same visibility rules, same filter dimensions, same FTS predicate) so that the reported
   `totalElements` is always accurate. The count query SHALL omit the ORDER BY and column
   projection from the data query.

### Requirement 5: Unified Search API Client (Frontend)

**User Story:** As a frontend developer, I want a single, well-typed API module for skill search
that handles both the browse catalog and the authoring list, so that search behavior is
consistent and not duplicated across two incompatible modules.

#### Acceptance Criteria

1. WHEN either the browse catalog or the authoring list performs a skill search, BOTH SHALL
   invoke the same underlying API function. The HTTP call to `GET /api/skills` SHALL exist in
   exactly one place in the frontend codebase; duplicating it in separate modules SHALL NOT be
   permitted after this milestone.
2. THE shared search function/hook SHALL accept all filter dimensions defined in Requirement 3.1
   as individually typed, optional parameters. An unset parameter SHALL be omitted from the
   request rather than sent as an empty string or null.
3. THE TanStack Query key for a skill list request SHALL incorporate all active filter parameters
   so that two requests differing only in filter values produce distinct cache entries and do not
   serve stale results from a different filter set.
4. THE existing `searchSkills` function in `src/api/skills.ts` (browse layer) SHALL be removed
   or converted to a thin re-export of the canonical `listSkills` from `src/api/skillsCatalog.ts`
   after migration. Having two independent implementations that each call `GET /api/skills` SHALL
   constitute a defect.

### Requirement 6: Filter UI Wired to Real Taxonomy

**User Story:** As a Skill_Consumer, I want the filter dropdowns to show the actual taxonomy
values configured for my organization rather than hardcoded options, so that I'm filtering by
values that really exist.

#### Acceptance Criteria

1. WHEN the FilterPanel component mounts, IT SHALL fetch taxonomy entries from `GET /api/taxonomy`
   and populate the Task Type, Language, Framework, and Tool filter dropdowns from the live
   taxonomy data, replacing the current hardcoded option arrays entirely.
2. WHILE the `GET /api/taxonomy` request is in-flight, THE FilterPanel SHALL render the four
   taxonomy dropdowns in a disabled state with a placeholder option (e.g. "Loading…") and SHALL
   NOT show any hardcoded options during the loading period.
3. THE taxonomy dropdowns SHALL only present entries where `active = true`; deactivated taxonomy
   entries SHALL NOT appear as selectable filter options.
4. WHEN the taxonomy response is received, THE filter options for each dropdown SHALL be ordered
   alphabetically by value within each tag_type group.
5. IF the `GET /api/taxonomy` request fails (any non-2xx response or network error), THE
   FilterPanel SHALL render the four taxonomy dropdowns as empty (no selectable options) and
   SHALL NOT throw an uncaught error or crash the page.

### Requirement 7: Unified Search Experience

**User Story:** As both a Skill_Consumer (browsing) and a Skill_Author (managing), I want the
search and filter experience to feel consistent between the catalog and the authoring list, so
that I can apply the same mental model regardless of which view I'm in.

#### Acceptance Criteria

1. BOTH the browse catalog (`src/browser/CatalogPage`) and the authoring list
   (`src/pages/skills/SkillListPage`) SHALL support the same set of filter dimensions (search,
   status, visibility, riskLevel, teamId, taskType, language, framework, tool), sourcing taxonomy
   options from the live taxonomy API for the four taxonomy-typed dimensions.
2. BOTH views SHALL debounce free-text search input with a 300ms delay before issuing an API
   request, and SHALL persist all active filters and the current page number as URL query
   parameters so that reloading the page or sharing the URL restores the exact same state.
3. WHEN a search query is active and the response is received, BOTH views SHALL display the
   `totalElements` count returned by the backend so the user knows how many skills matched.
4. WHEN the user clears all filters (e.g. via a "Clear all" action), BOTH views SHALL reset to
   the default state — no search query, no filters, page 0, ordered by recency — by updating the
   URL query parameters, without triggering a hard browser navigation or full page reload.

### Requirement 8: Search Result Quality (Non-Functional)

**User Story:** As a Skill_Consumer, I want search to be fast and return relevant results even
for partial or informal queries, so that the catalog feels useful in daily AI coding work.

#### Acceptance Criteria

1. FOR a skill catalog of up to 10,000 skills within a single organization, a search request
   with a free-text query and at most 3 simultaneous filter dimensions SHALL complete end-to-end
   in under 500ms at the 95th percentile, measured from API request receipt to response sent, on
   standard RDS PostgreSQL instance sizes (db.t3.medium or equivalent). The GIN index and B-tree
   filter indexes SHALL be sufficient; no additional caching layer is required.
2. THE Registry SHALL use `plainto_tsquery('english', :search)` for all user-supplied free-text
   queries so that multi-word inputs are tokenized and AND-combined automatically. Users SHALL
   NOT need to use PostgreSQL tsquery syntax (operators like `&`, `|`, `!`) for normal search
   use cases.
3. THE Registry SHALL trim the `search` parameter and treat it as absent when blank (empty string
   or whitespace only). Special tsquery characters within the input SHALL be handled safely by
   `plainto_tsquery` so that malformed input never causes a query error.
4. WHEN a free-text query matches no skills, THE Registry SHALL return HTTP 200 with an empty
   `content` array and `totalElements: 0`. HTTP 4xx or 5xx SHALL NOT be returned solely because
   the search term produced no results.
5. THE Registry SHALL cap the maximum page size at 100 results per request (`size` parameter).
   Requests with `size` greater than 100 SHALL be clamped to 100 rather than rejected, to avoid
   client errors from overly large page requests.

## Non-Goals

The following are explicitly out of scope for this milestone:

- **Semantic (embedding-based) search** — Phase 2 introduces pgvector and vector similarity.
  This milestone only extends Phase 1 FTS.
- **Saved searches and alerts** — out of scope for Phase 1.
- **Search analytics / query logging** — captured by the Usage Event Tracking milestone.
- **Autocomplete / type-ahead suggestions** — a future UX enhancement.
- **Cross-field boosting tuning beyond the A/B/C/D weight assignment** — advanced ranking
  configuration is deferred.
- **Variant instruction content in search** — variant-specific instructions are not indexed in
  this milestone. Only the canonical instructions of the current version are indexed.
- **Search across multiple organizations** — the system is single-organization; cross-org search
  is never relevant.
