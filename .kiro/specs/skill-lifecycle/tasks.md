# Implementation Plan: Skill Lifecycle (Deprecation & Blocking)

## Overview

This plan implements Milestone 2c — Skill Lifecycle — bottom-up: persistence (two new skill
columns + listing exclusion), the transition policy (state machine), the API contract, the service
orchestration, the REST controller, and the frontend governance controls. Property tests validate
the 8 correctness properties from the design; integration tests cover the transition endpoints and
listing effects. Tasks marked `*` are optional (tests) and can be deferred for a faster path to a
working feature.

## Tasks

- [x] 1. Persistence — lifecycle fields and listing exclusion
  - [x] 1.1 Create V6 Flyway migration
    - Create `backend/alexandria-entities/src/main/resources/db/migration/V6__skill_lifecycle_fields.sql`
    - `ALTER TABLE skills ADD COLUMN deprecation_reason TEXT;` and `ADD COLUMN prior_status TEXT;`
    - No CHECK change (V4 already permits all seven lifecycle statuses)
    - _Requirements: 1.2, 1.4, 2.3, 3.3_

  - [x] 1.2 Add fields to SkillEntity
    - Add nullable `deprecationReason` (`deprecation_reason`) and `priorStatus` (`prior_status`)
      String fields with getters/setters
    - _Requirements: 1.2, 1.4_

  - [x] 1.3 Extend SkillRepository.findVisibleSkills for listing exclusion
    - Add `boolean includeArchived` parameter
    - Add predicates: always exclude `Blocked`; exclude `Archived` unless `includeArchived` is true
      or `status = 'Archived'`
    - _Requirements: 6.1, 6.2_

  - [x]* 1.4 Write repository integration test for listing exclusion (Property 5)
    - **Property 5: Listing exclusion** — default listing excludes Blocked always and Archived by
      default; include-archived / status=Archived includes archived but never blocked
    - Testcontainers; seed skills across statuses; assert membership
    - **Validates: Requirements 6.1, 6.2**

- [x] 2. Transition policy (state machine)
  - [x] 2.1 Create LifecycleAction enum and SkillLifecycleTransition policy
    - `com.alexandria.skill.lifecycle.LifecycleAction` (DEPRECATE, BLOCK, ARCHIVE, RESTORE)
    - `SkillLifecycleTransition.isAllowed(currentStatus, action)` per the design legality table;
      `targetStatus(action)` for the non-restore actions
    - _Requirements: 1.3, 2.2, 3.2, 4.2, 5.4_

  - [x]* 2.2 Write property test for transition legality (Property 1)
    - **Property 1: Transition legality is total and matches the table** — for every
      (status, action) pair, `isAllowed` returns exactly the table value; no pair is undefined
    - jqwik, pure. **Validates: Requirements 1.3, 2.2, 3.2, 4.2, 5.4**

- [x] 3. API contract
  - [x] 3.1 Create SkillLifecycleApi interface and DeprecateRequest DTO
    - `com.alexandria.skill.lifecycle.SkillLifecycleApi`, `@RequestMapping("/api/skills/{skillId}")`
    - `POST /deprecate` (body `DeprecateRequest`), `POST /block`, `POST /archive`, `POST /restore`,
      all returning `ResponseEntity<SkillResponse>`
    - `DeprecateRequest` record `{ @Size(max = 1000) String reason }` in `...lifecycle.dto`
    - _Requirements: 1.1, 1.2, 2.1, 3.1, 4.1_

  - [x] 3.2 Add nullable deprecationReason to SkillResponse and SkillSummaryResponse
    - Additive field; mapper populates it from the entity
    - _Requirements: 6.5_

- [x] 4. Checkpoint — entity + policy + API compile; migration applies; policy/listing tests pass

- [x] 5. Service layer
  - [x] 5.1 Create SkillLifecycleService and SkillLifecycleServiceImpl
    - Methods `deprecate(skillId, reason, user)`, `block`, `archive`, `restore`
    - Each: load via `findByIdAndOrganizationId` (404), `isAllowed` check (409), apply transition,
      save, fire-and-forget audit
    - deprecate/block/archive: set `priorStatus` = current **only if current is an Active_State**
      (preserve existing priorStatus when transitioning between governed states — Property 6);
      deprecate also sets `deprecationReason`
    - restore: `lifecycleStatus = priorStatus != null ? priorStatus : "Draft"`, then clear
      `deprecationReason` and `priorStatus`
    - Audit actions: `skill.deprecated|blocked|archived|restored` with `{fromStatus,toStatus,priorStatus,(reason)}`
    - _Requirements: 1.1, 1.4, 1.5, 1.6, 2.1, 2.3, 2.6, 3.1, 3.3, 3.5, 4.1, 4.3, 4.4, 4.5, 5.5_

  - [x] 5.2 Update SkillResponseMapper to populate deprecationReason
    - _Requirements: 6.5_

  - [x]* 5.3 Write service property/behavior tests (Properties 2, 3, 4, 6, 8)
    - **Property 2:** deprecate/block/archive set target + record prior status
    - **Property 3:** restore round-trip returns to original active state; clears reason + prior
    - **Property 4:** version neutrality (currentVersionId + version count + content unchanged)
    - **Property 6:** block-from-deprecated preserves the original active prior status on restore
    - **Property 8:** exactly one audit event per transition with correct action + metadata
    - Mockito; **Validates: Requirements 1.1,1.4,2.1,2.3,3.1,3.3,4.1,4.3,4.4,5.5,1.6,2.6,3.5,4.5**

- [x] 6. REST layer
  - [x] 6.1 Create SkillLifecycleResource controller
    - Implements `SkillLifecycleApi`; `@PreAuthorize("hasAnyRole('Catalog_Admin','Org_Admin')")` on
      every method; resolve current user; delegate to service; map via `SkillResponseMapper`
    - _Requirements: 5.1, 1.1, 2.1, 3.1, 4.1_

  - [x] 6.2 Thread includeArchived through the skill list endpoint
    - Add optional `includeArchived` (default false) to `SkillApi.listSkills`, `SkillService.list`,
      and the `findVisibleSkills` call; existing callers default to false
    - _Requirements: 6.2_

  - [x]* 6.3 Write REST integration tests (Property 7 + endpoint behavior)
    - `@SpringBootTest` + Testcontainers + JWT-bypass post-processor (per skill-crud)
    - Happy path per transition (200) with status/reason assertions
    - 409 for illegal transitions (e.g. block already-blocked, restore an active skill)
    - **Property 7:** 403 for non-admin roles; 404 for missing / other-org skill
    - 400 for reason > 1000 chars; audit rows written
    - Verify blocked/deprecated skills interact correctly with the export endpoint (blocked→409)
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 1.6, 2.4, 6.4**

- [x] 7. Checkpoint — backend `./mvnw verify` green

- [x] 8. Frontend
  - [x] 8.1 Add lifecycle API client + hooks
    - `deprecateSkill(id, reason)`, `blockSkill(id)`, `archiveSkill(id)`, `restoreSkill(id)` in the
      catalog API module + TanStack Query mutation hooks invalidating skill detail + list
    - Add `deprecationReason` to the SkillResponse/summary TS types; add `includeArchived` to the
      list params
    - _Requirements: 7.1, 6.5_

  - [x] 8.2 Add governance controls to the skill detail page
    - Render Deprecate / Block / Archive / Restore controls only for Catalog_Admin/Org_Admin,
      showing only transitions valid from the current status (TS mirror of the legality table)
    - Deprecate opens an optional-reason form
    - Deprecation warning banner (with reason) when Deprecated; blocked indicator + disabled
      export/copy when Blocked (export disable already from skill-export)
    - User-readable error on failure
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x]* 8.3 Write frontend tests
    - Controls render per status/role; deprecate reason form; deprecation warning + blocked
      indicator; error path
    - _Requirements: 7.1, 7.3, 7.4, 7.5_

- [x] 9. Final checkpoint — backend `./mvnw verify` and frontend `npm run build && npm run test` green

## Notes

- Transitions are skill-level and never create a new Skill_Version (mirrors `changeVisibility`).
- Reuses `SkillConflictException` (409), `SkillNotFoundException` (404), `SkillResponseMapper`,
  `AuditService`, and the existing `GlobalExceptionHandler` — no new exception types.
- `SkillLifecycleTransition` is the single source of truth for legality; the frontend mirrors the
  same table for enabling/disabling controls but the backend is authoritative.
- Complements `skill-export`, which already denies export/copy for `Blocked` and warns on
  `Deprecated`; this milestone provides the transitions that reach those states.
- Builds on V4/V5; adds only V6. `Req 6.3` (surfacing blocked skills to admins in listings) is
  intentionally deferred — admins reach blocked skills via direct detail retrieval.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1"] },
    { "id": 1, "tasks": ["1.2", "2.2"] },
    { "id": 2, "tasks": ["1.3", "3.1"] },
    { "id": 3, "tasks": ["1.4", "3.2"] },
    { "id": 4, "tasks": ["5.1"] },
    { "id": 5, "tasks": ["5.2", "5.3"] },
    { "id": 6, "tasks": ["6.1", "6.2"] },
    { "id": 7, "tasks": ["6.3"] },
    { "id": 8, "tasks": ["8.1"] },
    { "id": 9, "tasks": ["8.2"] },
    { "id": 10, "tasks": ["8.3"] }
  ]
}
```
