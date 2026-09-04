# Implementation Plan: Skill Search

## Overview

This plan implements Milestone 2c — Skill Search — in dependency order: the backend repository
and index changes first, then the service and API layer, then the frontend. Each task is a
self-contained commit. Optional (`*`) tasks can be deferred for a faster path to a working
search endpoint.

## Tasks

- [ ] 1. Extended search vector — backend
  - [ ] 1.1 Add `rebuildSearchVector` to `SkillRepository`
    - Add a `@Modifying @Transactional @Query(nativeQuery=true)` method that sets
      `search_vector` to the concatenation of name(A), description(B), tag values(C),
      instructions(D) for a given skill ID. Parameters: `skillId` (UUID), `tagValues`
      (space-joined string, may be null/blank), `instructions` (String, may be null/blank).
    - The SQL must reference the current `name` and `description` columns directly (not pass
      them as parameters) so A/B are always consistent with the saved row.
    - *File:* `backend/alexandria-entities/src/main/java/com/alexandria/skill/SkillRepository.java`
    - *Requirements: 1.1, 1.2, 1.5*

  - [ ] 1.2 Add V7 migration to bulk-rebuild existing search vectors
    - Create `V7__rebuild_search_vectors.sql`. The migration runs a single `UPDATE skills`
      that sets `search_vector` for every existing skill using the same A/B/C/D weight formula,
      sourcing tag values via a correlated subquery to `skill_tags`/`taxonomy_entries` and
      instructions via a subquery to `skill_versions` on `current_version_id`. The migration
      is idempotent (safe to run twice).
    - This migration runs after V6 (`skill_lifecycle_fields`).
    - *File:* `backend/alexandria-entities/src/main/resources/db/migration/V7__rebuild_search_vectors.sql`
    - *Requirements: 1.1, 1.2*

  - [ ] 1.3 Add `rebuildSearchVector` helper to `SkillServiceImpl`
    - Add a private `rebuildSearchVector(SkillEntity skill)` method that:
      1. Loads all `SkillTagEntity` rows for the skill via `skillTagRepository.findBySkillId`.
      2. Loads the corresponding `TaxonomyEntryEntity` rows via
         `taxonomyEntryRepository.findAllById`; filters to active-only; space-joins their
         `value` fields into a single string (empty string if none).
      3. Loads canonical instructions from the current version via
         `skillVersionRepository.findById(skill.getCurrentVersionId())` — null-safe (use empty
         string if `currentVersionId` is null or version not found).
      4. Calls `skillRepository.rebuildSearchVector(skill.getId(), tagValues, instructions)`.
    - Call this method at the end of `create(...)` and `update(...)`, **after** `save()` has
      persisted the skill row and `handleTagUpdate` has completed. Do NOT call it inside
      `handleTagUpdate` — the outer call sites cover all cases and a double call would fire
      two redundant database UPDATEs per operation.
    - Important: `rebuildSearchVector` must always be called after the skill entity is
      persisted because it reads `name` and `description` from the database row directly.
    - *File:* `backend/alexandria-service/src/main/java/com/alexandria/skill/SkillServiceImpl.java`
    - *Requirements: 1.1, 1.2, 1.3, 1.5*

  - [ ]* 1.4 Repository integration tests for extended index
    - Extend `V4MigrationAndSkillRepositoryIntegrationTest` with a `SearchVectorRebuild` nested
      test class. Tests:
      - Tag values become searchable after `rebuildSearchVector` is called; search returns
        the skill.
      - After removing all tags and rebuilding, the same search returns no result.
      - Instruction content becomes searchable after rebuild.
      - Rebuilding twice produces the same result (idempotency check via two consecutive
        searches yielding identical results).
      - A skill whose search term appears in the name ranks above a skill where it appears
        only in instructions (weight A > D).
    - *Requirements: 1.1, 1.2, 1.4*

- [ ] 2. Full filter wiring — backend
  - [ ] 2.1 Add new filter params and relevance ordering to `findVisibleSkills` in `SkillRepository`
    - Extend the `findVisibleSkills` method signature with six new parameters:
      `visibility` (String), `riskLevel` (String), `taskType` (String), `language` (String),
      `framework` (String), `tool` (String). All are nullable/optional (null means no filter).
    - Update the native SQL `@Query` to add:
      - `AND (:visibility IS NULL OR s.visibility = :visibility)`
      - `AND (:riskLevel IS NULL OR s.risk_level = :riskLevel)`
      - Four `AND (:X IS NULL OR EXISTS (SELECT 1 FROM skill_tags st JOIN taxonomy_entries te ...
        WHERE te.tag_type = 'X' AND LOWER(te.value) = LOWER(:X) AND te.active = true))` clauses
        for taskType, language, framework, and tool.
    - Update the `ORDER BY` to: `CASE WHEN :searchQuery IS NULL THEN 0.0 ELSE
      ts_rank(s.search_vector, plainto_tsquery('english', :searchQuery), 1) END DESC,
      s.updated_at DESC`. Use `THEN 0.0` (not `THEN NULL`) to give the CASE expression an
      unambiguous `float4` type.
    - Add the `countQuery` annotation alongside the data query. The count query uses exactly
      the same predicates (including all new filter params and visibility rules) but selects
      `COUNT(*)` with no `ORDER BY` and no `ts_rank` expression.
    - Update all existing call sites of `findVisibleSkills` (in `SkillServiceImpl` and in
      integration tests) to pass `null` for each new parameter until task 2.3 wires them.
    - *File:* `backend/alexandria-entities/src/main/java/com/alexandria/skill/SkillRepository.java`
    - *Requirements: 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2*

  - [ ] 2.2 Update `SkillApi.listSkills` to expose new query params
    - Add to the `@GetMapping` method signature (all `required = false`):
      `String riskLevel`, `String taskType`, `String language`, `String framework`, `String tool`.
    - `visibility` is already declared on the interface — verify it is present and leave it as-is.
    - *File:* `backend/alexandria-api/src/main/java/com/alexandria/skill/SkillApi.java`
    - *Requirements: 3.1, 3.5*

  - [ ] 2.3 Update `SkillService.list` and `SkillServiceImpl.list` to pass all new params
    - Extend the `SkillService.list` interface signature to:
      `list(String status, String visibility, String riskLevel, UUID teamId, String taskType,
      String language, String framework, String tool, String searchQuery, boolean includeArchived,
      AlexandriaUserDetails userDetails, Pageable pageable)`.
    - Update `SkillServiceImpl.list` to pass all new parameters to
      `skillRepository.findVisibleSkills`. Keep `isOrgAdmin` computed as
      `userDetails.role() == Role.Org_Admin` (not `.isAdmin()`) — this is intentional: Org_Admin
      bypasses visibility restrictions for listing; Catalog_Admin does not.
    - Update `SkillResource.listSkills` to: (a) accept all new params in its method signature to
      match the updated `SkillApi` interface, (b) pass them all through `blankToNull(...)` before
      forwarding to `skillService.list`, and (c) stop silently dropping `visibility` (which was
      accepted but never forwarded before this change).
    - *Files:*
      `backend/alexandria-service/src/main/java/com/alexandria/skill/SkillService.java`
      `backend/alexandria-service/src/main/java/com/alexandria/skill/SkillServiceImpl.java`
      `backend/alexandria-service/src/main/java/com/alexandria/skill/SkillResource.java`
    - *Requirements: 3.1, 3.2, 3.5, 3.6*

  - [ ]* 2.4 Repository integration tests for new filter dimensions and relevance ordering
    - Extend `V4MigrationAndSkillRepositoryIntegrationTest` with three nested test classes:
    - `TaxonomyFilters`: create skills with different tag combinations; assert `taskType=X`
      returns only skills with a `task_type` tag matching X (case-insensitive); assert two
      simultaneous taxonomy filters (AND) work correctly; assert a non-existent value returns
      an empty page (HTTP 200) not an error.
    - `ExactFilters`: test `visibility` filter returns only skills with the matching visibility
      value; test `riskLevel` filter; test combining `status + riskLevel` as AND.
    - `RelevanceOrdering`: create two skills — one with the search term in the name, one only
      in description. Assert the name-match appears first on page 0. Also assert a filter-only
      request (no `search` param) returns results in `updated_at DESC` order.
    - `CountAccuracy`: assert `Page.getTotalElements()` equals the manual `COUNT(*)` for
      several different filter combinations, including taxonomy filters.
    - *Requirements: 2.1, 2.2, 3.2, 3.3, 3.4, 3.6, 4.1, 4.2*

- [ ] 3. Checkpoint — backend compiles, all migrations apply, all existing tests pass
  - Run `./mvnw verify` and confirm BUILD SUCCESS. Confirm V7 runs cleanly after V6 in a
    fresh Testcontainers boot. This is a validation step; no new commit needed unless fixes
    are required.

- [ ] 4. Frontend — unified search params type
  - [ ] 4.1 Extend `SkillListParams` in `src/types/skill.ts`
    - Add `riskLevel?: string`, `taskType?: string`, `language?: string`, `framework?: string`,
      `tool?: string` to the existing `SkillListParams` interface.
    - No logic changes needed — `listSkills(params)` in `skillsCatalog.ts` already passes the
      params object directly as Axios query params; the new fields will be forwarded automatically.
    - *File:* `frontend/src/types/skill.ts`
    - *Requirements: 5.2*

  - [ ] 4.2 Verify `listSkills` / `useSkills` in `skillsCatalog.ts` forward all params
    - Confirm that `listSkills(params: SkillListParams)` passes `{ params }` to `apiClient.get`
      with no field filtering (all `SkillListParams` fields forwarded as query params).
    - If any explicit field allowlist exists, remove it so new fields are forwarded automatically.
    - *File:* `frontend/src/api/skillsCatalog.ts`
    - *Requirements: 5.1, 5.2*

- [ ] 5. Frontend — migrate browse catalog to unified API
  - [ ] 5.1 Replace `searchSkills` in `CatalogPage` with `useSkills`
    - Replace the `useQuery({ queryFn: () => searchSkills(apiParams) })` call in
      `src/browser/CatalogPage.tsx` with `useSkills(params)` imported from
      `@/api/skillsCatalog`.
    - Map the `CatalogPage` filter fields to `SkillListParams`: `query`→`search`,
      `taskType`→`taskType`, `language`→`language`, `framework`→`framework`, `tool`→`tool`
      (taxonomy tool tag — not variant tool), `riskLevel`→`riskLevel`, `visibility`→`visibility`,
      `ownerTeam`→`teamId`. For `ownerTeam`, replace the free-text input with a team UUID
      dropdown (use the same `listTeams(0, 100)` call that `SkillSearchFilters` already uses).
    - Before removing `searchSkills` from `src/api/skills.ts`, verify no other file imports it
      beyond `CatalogPage.tsx`. Then remove it. Leave `getSkill` and `exportSkill` in place.
    - *Files:*
      `frontend/src/browser/CatalogPage.tsx`
      `frontend/src/api/skills.ts`
    - *Requirements: 5.1, 5.3, 5.4, 7.1*

- [ ] 6. Frontend — live taxonomy in filter dropdowns
  - [ ] 6.1 Add `getAllTaxonomy` function and taxonomy types
    - Check whether `TaxonomyGroupResponse` and `TaxonomyEntryResponse` types already exist in
      `src/api/taxonomy.ts` or elsewhere; reuse if so, otherwise add them.
    - Add `async function getAllTaxonomy(): Promise<TaxonomyGroupResponse[]>` calling
      `GET /api/taxonomy`. Add a `useAllTaxonomy()` hook with a 5-minute `staleTime` (taxonomy
      changes infrequently). Place in `src/api/skillsCatalog.ts` or a dedicated
      `src/api/taxonomy.ts` — wherever the existing taxonomy types live.
    - *Requirements: 6.1, 6.3*

  - [ ] 6.2 Hydrate `FilterPanel` (`src/browser/components/FilterPanel.tsx`) from taxonomy API
    - Remove the hardcoded `TASK_TYPE_OPTIONS`, `LANGUAGE_OPTIONS`, `FRAMEWORK_OPTIONS`, and
      `TOOL_OPTIONS` arrays. Replace with `useAllTaxonomy()` and an `optionsFor(tagType)` helper
      that filters to active entries and maps to their `value` strings.
    - While the taxonomy query is loading: disable the four taxonomy `<select>` elements.
    - If the taxonomy query errors: leave the selects empty (no crash, no error banner).
    - *File:* `frontend/src/browser/components/FilterPanel.tsx`
    - *Requirements: 6.1, 6.2, 6.3, 6.4, 6.5*

  - [ ] 6.3 Add taxonomy dimension filters to `SkillSearchFilters`
    (`src/components/skill/SkillSearchFilters.tsx`)
    - The authoring filter bar currently exposes: search, status, visibility, teamId.
    - Add four new `<select>` controls for taskType, language, framework, tool, populated
      from `useAllTaxonomy()` with active-only filtering.
    - Extend the `SkillFilterValues` type to include `taskType`, `language`, `framework`,
      `tool`, and `riskLevel` fields.
    - *File:* `frontend/src/components/skill/SkillSearchFilters.tsx`
    - *Requirements: 6.1, 6.3, 7.1*

- [ ] 7. Frontend — URL persistence for new filter dimensions
  - [ ] 7.1 Extend URL serialization in `CatalogPage` / `urlState.ts`
    - In `src/browser/utils/urlState.ts`, add `riskLevel`, `taskType`, `language`, `framework`,
      `tool` to both `parseCatalogState` (read from `URLSearchParams`) and `serializeCatalogState`
      (write to `URLSearchParams`). Empty/undefined values should be omitted from the URL.
    - *File:* `frontend/src/browser/utils/urlState.ts`
    - *Requirements: 7.2*

  - [ ] 7.2 Extend URL serialization in `SkillListPage`
    - In `src/pages/skills/SkillListPage.tsx`, extend `readFilters` and `writeParams` to
      include `riskLevel`, `taskType`, `language`, `framework`, `tool`. Mirror the existing
      pattern: read from `URLSearchParams` via `params.get('X') ?? ''`, set via
      `if (next.X) params.set('X', next.X)`.
    - *File:* `frontend/src/pages/skills/SkillListPage.tsx`
    - *Requirements: 7.2*

- [ ]* 8. Frontend tests
  - [ ]* 8.1 Test filter params are forwarded to the API
    - Mock `GET /api/skills` (via `vi.mock('@/api/client')`) and `GET /api/taxonomy`. Set a
      `taskType` filter value in the filter UI. Assert the mocked `apiClient.get` was called
      with `taskType` present in the `params` object. Repeat for `riskLevel`.
    - *Requirements: 3.1, 5.2*

  - [ ]* 8.2 Test live taxonomy populates filter dropdowns
    - Mock `GET /api/taxonomy` returning two `task_type` entries (A active, B inactive).
      Render `FilterPanel`. Assert the task-type dropdown contains option A but not B. Assert
      it does not contain any previously hardcoded values.
    - *Requirements: 6.1, 6.3*

  - [ ]* 8.3 Test taxonomy loading and failure states
    - Loading: mock `useAllTaxonomy` in a pending state; assert the four taxonomy dropdowns are
      disabled.
    - Failure: mock `GET /api/taxonomy` returning HTTP 500; assert `FilterPanel` renders
      without throwing and the taxonomy dropdowns are empty.
    - *Requirements: 6.2, 6.5*

  - [ ]* 8.4 Test search debounce
    - Render `SkillListPage`. Type a search term one character at a time (fire multiple
      `onChange` events). Assert `apiClient.get` is called only once (after the 300ms debounce
      window elapses), not once per keystroke.
    - *Requirements: 7.2, 8.2*

  - [ ]* 8.5 Test URL state round-trip
    - Set a filter combination (search + taskType + riskLevel). Assert the URL params update to
      include all three. Call `parseCatalogState`/`readFilters` on those URL params. Assert the
      reconstructed filter state matches the original exactly.
    - *Requirements: 7.2*

- [ ] 9. Final checkpoint — `./mvnw verify` and `npm run build && npm run test` green
  - Run the full backend verify and frontend build/test. Fix any failures introduced by this
    milestone. No commit needed if all passing already.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3"] },
    { "id": 2, "tasks": ["1.4", "2.1"] },
    { "id": 3, "tasks": ["2.2"] },
    { "id": 4, "tasks": ["2.3"] },
    { "id": 5, "tasks": ["2.4", "3"] },
    { "id": 6, "tasks": ["4.1"] },
    { "id": 7, "tasks": ["4.2"] },
    { "id": 8, "tasks": ["5.1", "6.1"] },
    { "id": 9, "tasks": ["6.2", "6.3", "7.1", "7.2"] },
    { "id": 10, "tasks": ["8.1", "8.2", "8.3", "8.4", "8.5"] },
    { "id": 11, "tasks": ["9"] }
  ]
}
```

## Notes

- **Task ordering for `SkillRepository`:** Tasks 1.1 and 2.1 both modify `SkillRepository`
  but are in separate waves because 2.1 requires the new filter params before writing the
  `countQuery`. In practice both changes can land in a single commit for `SkillRepository`.

- **Double-rebuild guard:** The `rebuildSearchVector` helper (task 1.3) must only be called
  from `create` and `update`, not from `handleTagUpdate`. `handleTagUpdate` is always called
  from within `create` or `update`, so the outer call sites are sufficient. Calling it from
  `handleTagUpdate` as well would fire two redundant database UPDATEs per operation.

- **`isOrgAdmin` stays narrow:** `SkillServiceImpl.list` computes
  `isOrgAdmin = userDetails.role() == Role.Org_Admin`, not `role().isAdmin()`. This is
  intentional — only Org_Admins bypass visibility restrictions for listing. Catalog_Admins
  can govern skills but see only what visibility rules permit.

- **`ORDER BY CASE WHEN` type safety:** The `ORDER BY` clause uses `THEN 0.0` (not
  `THEN NULL`) so PostgreSQL can infer a `float4` type for the CASE expression rather than
  an ambiguous null. `NULLS LAST` is unnecessary when using `THEN 0.0`.

- **V7 migration:** Runs after V6 (`skill_lifecycle_fields`). Safe to run on an empty
  database (UPDATE touches 0 rows). On a live instance with data, it locks the `skills` table
  briefly — acceptable for a single-org self-hosted deployment.

- **`searchSkills` removal:** The `searchSkills` function in `src/api/skills.ts` is removed in
  task 5.1. Before removing it, verify no other file imports it beyond `CatalogPage.tsx`.
  The `getSkill` and `exportSkill` functions in the same file are NOT removed.

- **Taxonomy `tool` vs. variant `tool`:** The `tool` filter param in `SkillListParams`
  is a taxonomy tag value (e.g. a `tag_type='tool'` entry). It is distinct from the variant
  tool identifier used for export. The frontend must not conflate them.
