# Design Document: Skill Lifecycle (Deprecation & Blocking)

## Overview

This design covers Milestone 2c — **Skill Lifecycle: Deprecation & Blocking** — adding the
governance transitions (deprecate, block, archive, restore) that move a skill into and out of
the `Deprecated`, `Archived`, and `Blocked` states, and wiring those states into listing
exclusion and detail responses.

It builds on `skill-crud` (skills, versions, visibility, `SkillSecurity`, audit) and complements
`skill-export` (which already denies export/copy for `Blocked` skills and warns on `Deprecated`).
It reuses the established modular-monolith architecture: `alexandria-api` (interfaces + DTOs),
`alexandria-entities` (JPA + repositories), `alexandria-service` (business logic + controllers);
PostgreSQL 16 via Flyway; React/TypeScript frontend.

Key architectural decisions:
- **Explicit state machine in one place.** A single `SkillLifecycleTransition` policy defines
  which transitions are legal from which states, so the four endpoints share one source of truth
  and invalid transitions fail uniformly with 409.
- **Prior-status capture for reversibility.** Entering a Governed_State stores the skill's
  previous status in a new `prior_status` column; `restore` returns to it (or `Draft` if absent).
  This keeps governance actions non-destructive and reversible.
- **Transitions are skill-level, version-neutral.** No new Skill_Version is created; only the
  skill's `lifecycle_status` (and `deprecation_reason`/`prior_status`) change. This mirrors how
  `changeVisibility` already works in `skill-crud`.
- **Listing exclusion at the query layer.** The `findVisibleSkills` native query is extended to
  drop `Blocked` always and `Archived` unless explicitly requested, so excluded skills never load
  into memory — consistent with the visibility-at-query-layer principle.
- **Governance authorization via role, not team membership.** Unlike edit (team-scoped), lifecycle
  governance is a Catalog_Admin/Org_Admin action, enforced with `@PreAuthorize("hasAnyRole(...)")`,
  matching the taxonomy-admin pattern.

## Architecture

### High-Level Integration Diagram

```mermaid
graph TB
    subgraph Frontend["Skill Browser (React/TypeScript)"]
        DetailPage["Skill Detail Page"]
        LifecycleControls["Lifecycle controls<br/>(Deprecate / Block / Archive / Restore)"]
    end

    subgraph Backend["Backend (Spring Boot 4 / Java 25)"]
        subgraph API["alexandria-api"]
            LifecycleApi["SkillLifecycleApi"]
            LifecycleDTOs["DeprecateRequest, LifecycleAction"]
        end
        subgraph Service["alexandria-service"]
            LifecycleResource["SkillLifecycleResource"]
            LifecycleService["SkillLifecycleService / Impl"]
            TransitionPolicy["SkillLifecycleTransition (state machine)"]
            SkillService["SkillService (existing)"]
            AuditService["AuditService (existing)"]
        end
        subgraph Entities["alexandria-entities"]
            SkillEntity["SkillEntity (+ deprecationReason, priorStatus)"]
            SkillRepo["SkillRepository (findVisibleSkills updated)"]
        end
    end

    subgraph Infra
        PG["PostgreSQL 16"]
    end

    DetailPage --> LifecycleControls
    LifecycleControls -->|POST /api/skills/:id/{deprecate|block|archive|restore}| LifecycleResource
    LifecycleResource --> LifecycleService
    LifecycleService --> TransitionPolicy
    LifecycleService --> SkillService
    LifecycleService --> SkillRepo
    LifecycleService --> AuditService
    SkillRepo --> PG
```

### Transition Flow

```mermaid
sequenceDiagram
    participant Client
    participant Resource as SkillLifecycleResource
    participant Service as SkillLifecycleServiceImpl
    participant Policy as SkillLifecycleTransition
    participant Repo as SkillRepository
    participant Audit as AuditService

    Client->>Resource: POST /api/skills/{id}/block  (Catalog_Admin JWT)
    Resource->>Service: block(skillId, user)
    Service->>Repo: findByIdAndOrganizationId (404 if missing/other org)
    Service->>Policy: canTransition(current -> Blocked)?  (409 if illegal)
    Service->>Service: priorStatus = current; lifecycle = Blocked
    Service->>Repo: save
    Service->>Audit: record("skill.blocked", metadata{priorStatus}) [fire-and-forget]
    Service-->>Resource: updated skill
    Resource-->>Client: 200 SkillResponse
```

## Components and Interfaces

### 1. `alexandria-api` — contract

**`SkillLifecycleApi`** interface, `@RequestMapping("/api/skills/{skillId}")`:
```
POST /api/skills/{skillId}/deprecate   body: DeprecateRequest (optional reason)  -> SkillResponse
POST /api/skills/{skillId}/block                                                  -> SkillResponse
POST /api/skills/{skillId}/archive                                                -> SkillResponse
POST /api/skills/{skillId}/restore                                                -> SkillResponse
```
- Verb sub-resources (not a generic `PATCH status`) so each transition has its own audited,
  authorizable endpoint and the illegal-target space is smaller.
- All return the updated `SkillResponse` (reusing the existing `skill-crud` DTO).

**`DeprecateRequest`** record: `{ @Size(max = 1000) String reason }` (reason optional/nullable).

### 2. `alexandria-service` — transition policy + orchestration

**`SkillLifecycleTransition`** (the state machine; a small enum-keyed policy, no Spring needed):
```java
public enum LifecycleAction { DEPRECATE, BLOCK, ARCHIVE, RESTORE }
```
- `boolean isAllowed(String currentStatus, LifecycleAction action)` — the single legality check.
- `String targetStatus(LifecycleAction action)` — DEPRECATE→`Deprecated`, BLOCK→`Blocked`,
  ARCHIVE→`Archived`, RESTORE→(prior status resolved by the service).
- Legality table below.

**`SkillLifecycleService`**:
```java
public interface SkillLifecycleService {
    SkillEntity deprecate(UUID skillId, String reason, AlexandriaUserDetails user);
    SkillEntity block(UUID skillId, AlexandriaUserDetails user);
    SkillEntity archive(UUID skillId, AlexandriaUserDetails user);
    SkillEntity restore(UUID skillId, AlexandriaUserDetails user);
}
```
Each method:
1. Loads the skill via `skillRepository.findByIdAndOrganizationId(skillId, orgId)` → 404 if absent.
   (Org scoping enforced here; role is enforced by `@PreAuthorize` on the controller.)
2. Checks `SkillLifecycleTransition.isAllowed(current, action)` → `SkillConflictException` (409)
   if not.
3. Applies the transition:
   - **deprecate/block/archive**: set `priorStatus = current` (only if current is not already a
     Governed_State — guaranteed by the legality table except for block-from-deprecated/archived,
     where prior is the deprecated/archived skill's own prior... see note), set
     `lifecycleStatus = target`; for deprecate also set `deprecationReason`.
   - **restore**: `lifecycleStatus = priorStatus != null ? priorStatus : "Draft"`; clear
     `deprecationReason` and `priorStatus`.
4. Saves and fires a fire-and-forget audit event (`skill.deprecated|blocked|archived|restored`)
   with metadata `{ priorStatus, (reason), fromStatus, toStatus }`.
5. Returns the updated `SkillEntity` (controller maps to `SkillResponse`).

> **Prior-status note for block-from-governed:** block is allowed from `Deprecated`/`Archived`
> (Req 2.2 allows block from any non-Blocked state). To keep restore meaningful, when blocking a
> skill that is already in a Governed_State, the service preserves the *existing* `priorStatus`
> (the original active state) rather than overwriting it with `Deprecated`/`Archived`. Restoring a
> block therefore returns to the original active state. This is captured as Property 6.

**`SkillLifecycleResource`** implements `SkillLifecycleApi`:
- `@PreAuthorize("hasAnyRole('Catalog_Admin','Org_Admin')")` on every method (Req 5.1).
- Resolves the current user from the security context (same helper pattern as other resources).
- Delegates to `SkillLifecycleService`, maps the result via the existing `SkillResponseMapper`.

**Reuse of existing pieces:**
- `SkillConflictException` → 409 (already handled by `GlobalExceptionHandler`).
- `SkillNotFoundException` → 404 (already handled).
- `SkillResponseMapper` → builds `SkillResponse`/`SkillSummaryResponse` (extended to carry the
  new fields; see Data Models).
- `AuditService.record(...)` → audit events.

### 3. `alexandria-entities` — persistence + listing

**V6 Flyway migration** `V6__skill_lifecycle_fields.sql`:
```sql
ALTER TABLE skills ADD COLUMN deprecation_reason TEXT;
ALTER TABLE skills ADD COLUMN prior_status TEXT;
```
- Both nullable; no backfill needed (existing skills are in active states with null prior_status).
- The `lifecycle_status` CHECK constraint from V4 already permits all seven states, so no constraint
  change is required.

**`SkillEntity`** gains two fields: `deprecationReason` (`deprecation_reason`) and `priorStatus`
(`prior_status`), both nullable Strings with getters/setters.

**`SkillRepository.findVisibleSkills`** is extended with an `includeArchived` boolean parameter and
two status predicates:
```sql
...
  AND s.lifecycle_status <> 'Blocked'
  AND (:includeArchived = true OR :status = 'Archived' OR s.lifecycle_status <> 'Archived')
  AND (:status IS NULL OR s.lifecycle_status = :status)
...
```
- `Blocked` is always excluded from the listing (Req 6.1, parent Req 9.4).
- `Archived` is excluded unless `includeArchived` is true or the caller filters by
  `status = 'Archived'` (Req 6.2, parent Req 9.5).
- Direct detail retrieval (`getById`) is unchanged, so admins/owners can still open a
  blocked/archived skill to inspect or restore it (Req 6.4).

> Note on Req 6.3 (blocked visible to edit-authority users in filtered listings): the default
> listing excludes `Blocked` for everyone. Surfacing blocked skills to admins in listings is
> deferred to an explicit admin filter and is not required for this milestone's core flow; the
> detail endpoint already lets admins reach blocked skills. This is called out in the tasks as an
> optional enhancement to avoid over-scoping the query.

### 4. Frontend

- On the browse `SkillDetailPage`, add a governance control group visible only when
  `userRole ∈ {Catalog_Admin, Org_Admin}`, rendering the transitions valid from the current status
  (derived from the same legality table, mirrored in TS):
  - Active → Deprecate, Block, Archive
  - Deprecated → Restore, Block, Archive
  - Archived → Restore, Block
  - Blocked → Restore
- Deprecate opens a small form for the optional reason.
- Show a deprecation warning banner (with reason) when `Deprecated`, and a blocked indicator with
  disabled export/copy when `Blocked` (the latter already wired by `skill-export`).
- New API client functions: `deprecateSkill(id, reason)`, `blockSkill(id)`, `archiveSkill(id)`,
  `restoreSkill(id)` plus TanStack Query mutation hooks that invalidate the skill detail + list.

## Data Models

### `skills` (two new columns, V6)

| Column               | Type | Nullable | Notes                                                              |
| -------------------- | ---- | -------- | ------------------------------------------------------------------ |
| `deprecation_reason` | TEXT | yes      | Set on deprecate, cleared on restore; ≤1000 chars (validated in API) |
| `prior_status`       | TEXT | yes      | Status held before entering a Governed_State; used/cleared by restore |

No new tables. `lifecycle_status` values are unchanged (V4 CHECK already covers all seven).

### Lifecycle state machine (legality table)

| Current \ Action        | DEPRECATE | BLOCK | ARCHIVE | RESTORE            |
| ----------------------- | :-------: | :---: | :-----: | :----------------: |
| `Draft`                 |    ✔      |  ✔    |   ✔     |        ✘           |
| `Published`             |    ✔      |  ✔    |   ✔     |        ✘           |
| `Team_Approved`         |    ✔      |  ✔    |   ✔     |        ✘           |
| `Company_Approved`      |    ✔      |  ✔    |   ✔     |        ✘           |
| `Deprecated`            |    ✘      |  ✔    |   ✔     |        ✔           |
| `Archived`              |    ✘      |  ✔    |   ✘     |        ✔           |
| `Blocked`               |    ✘      |  ✘    |   ✘     |        ✔           |

- ✘ transitions return HTTP 409.
- RESTORE resolves the target from `prior_status` (or `Draft` if null) and clears governance fields.

### DTO changes

- **`SkillResponse`** and **`SkillSummaryResponse`** gain a nullable `deprecationReason` field
  (already carry `lifecycleStatus`). This is additive and backward compatible.
- **`DeprecateRequest`** (new): `{ reason?: string (≤1000) }`.

## Error Handling

| Condition                                   | Exception                  | HTTP | Handler            |
| ------------------------------------------- | -------------------------- | ---- | ------------------ |
| Non-admin attempts a transition             | `AccessDeniedException`    | 403  | existing           |
| Skill missing / other org                   | `SkillNotFoundException`   | 404  | existing           |
| Transition illegal from current status      | `SkillConflictException`   | 409  | existing           |
| Deprecation reason > 1000 chars             | `MethodArgumentNotValid`   | 400  | existing           |

No new exception types are introduced; all map through the existing `GlobalExceptionHandler`.

## Correctness Properties

### Property 1: Transition legality is total and matches the table

*For any* current Lifecycle_Status and any LifecycleAction, `SkillLifecycleTransition.isAllowed`
SHALL return exactly the value in the legality table — no status/action pair SHALL be undefined,
and an illegal transition SHALL always produce HTTP 409 at the service layer.

**Validates: Requirements 1.3, 2.2, 3.2, 4.2, 5.4**

### Property 2: Deprecate/block/archive record prior status and set the target

*For any* skill in a legal source state, deprecate/block/archive SHALL set `lifecycle_status` to
the action's target state and SHALL populate `prior_status` such that a subsequent restore returns
the skill to an Active_State.

**Validates: Requirements 1.1, 1.4, 2.1, 2.3, 3.1, 3.3**

### Property 3: Restore round-trip

*For any* skill moved into a Governed_State from an Active_State and then restored, the resulting
Lifecycle_Status SHALL equal the original Active_State, and `deprecation_reason` and `prior_status`
SHALL both be null afterward.

**Validates: Requirements 4.1, 4.3, 4.4**

### Property 4: Version neutrality

*For any* lifecycle transition, the skill's `current_version_id`, version count, and all version
content SHALL be unchanged.

**Validates: Requirements 5.5**

### Property 5: Listing exclusion

*For any* organization, a default catalog listing SHALL contain no `Blocked` skills and no
`Archived` skills; a listing with the include-archived indicator (or `status=Archived`) SHALL
include `Archived` skills but still SHALL contain no `Blocked` skills.

**Validates: Requirements 6.1, 6.2, 2.5, 3.4**

### Property 6: Block preserves the original active prior status

*For any* skill that is deprecated (recording prior = active state) and then blocked, restoring the
block SHALL return the skill to the original Active_State, not to `Deprecated`.

**Validates: Requirements 2.3, 4.1**

### Property 7: Governance authorization

*For any* user whose role is not `Catalog_Admin` or `Org_Admin`, every lifecycle transition SHALL
return HTTP 403 and SHALL not change the skill's status; org scoping SHALL cause transitions on
out-of-org skills to return HTTP 404.

**Validates: Requirements 5.1, 5.2, 5.3**

### Property 8: Audit completeness

*For any* successful transition, exactly one Audit_Event SHALL be recorded with the corresponding
action (`skill.deprecated|blocked|archived|restored`), the actor, the target skill, and metadata
capturing the from/to statuses (and reason for deprecate).

**Validates: Requirements 1.6, 2.6, 3.5, 4.5**

## Testing Strategy

- **Transition-policy property tests** (jqwik, pure, no Spring): Property 1 over the full
  status × action matrix.
- **Service tests** (Mockito): Properties 2, 3, 4, 6, 8 — prior-status capture, restore round-trip,
  version neutrality, block-from-governed prior preservation, audit recording. Fast, no DB.
- **Repository/integration tests** (`@SpringBootTest` + Testcontainers): Property 5 — listing
  excludes blocked always and archived by default, includes archived on request; detail retrieval
  still returns blocked/archived to visible users.
- **REST integration tests** (MockMvc + JWT-bypass post-processor, per skill-crud): each transition
  endpoint happy path (200), 409 illegal transitions, 403 for non-admin (Property 7), 404 for
  missing/other-org, 400 for over-long reason; audit rows written.
- **Frontend tests** (Vitest + RTL): governance controls render per status and role; deprecate
  reason form; deprecation warning + blocked indicator; error path.

## Migration & Compatibility

- Adds `V6__skill_lifecycle_fields.sql` (two nullable columns). No changes to existing tables' data.
- `findVisibleSkills` gains an `includeArchived` parameter — the `SkillService.list` call site and
  the `SkillResource` list endpoint gain an optional `includeArchived` query param (default false).
  Existing callers pass `false`, so default behavior tightens (blocked/archived now hidden) as
  intended by Req 6.
- `SkillResponse`/`SkillSummaryResponse` gain a nullable field — additive, backward compatible with
  the frontend.
- Complements `skill-export`: this milestone provides the transitions that put skills into the
  `Blocked`/`Deprecated` states that `skill-export` already handles.
