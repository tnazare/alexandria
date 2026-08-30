# Requirements Document

## Introduction

This specification covers Milestone 2c of the Alexandria platform — **Skill Lifecycle:
Deprecation & Blocking**. It gives Catalog_Admins the governance controls to deprecate,
block, archive, and restore skills, and it wires those states into the behaviors that
already depend on them: export/copy denial for blocked skills (implemented in
`skill-export`), deprecation warnings, and default exclusion of blocked/archived skills
from catalog listings.

Today the lifecycle enum already includes `Deprecated`, `Archived`, and `Blocked`, and
`skill-crud` rejects edits to skills in those states, but there is **no way to set them** —
skills can only move Draft ↔ Published. This milestone adds the missing lifecycle
transition endpoints and the state machine that governs them.

It maps to **Requirement 15 (Skill Deprecation and Blocking)** and the blocked/archived
list-exclusion portions of **Requirements 9.4 and 9.5** from the `governed-skill-registry`
parent spec.

## Glossary

- **Lifecycle_Status**: The governance state of a skill. One of: `Draft`, `Published`,
  `Team_Approved`, `Company_Approved`, `Deprecated`, `Archived`, `Blocked`.
- **Governed_State**: One of the three states this milestone introduces transitions into —
  `Deprecated`, `Archived`, `Blocked`.
- **Active_State**: Any non-governed state — `Draft`, `Published`, `Team_Approved`,
  `Company_Approved`.
- **Transition**: A change of a skill's Lifecycle_Status via a dedicated lifecycle operation
  (deprecate, block, archive, restore), as opposed to the implicit transitions from
  create/publish/edit in `skill-crud`.
- **Restore**: A transition from a Governed_State back to an Active_State.
- **Deprecation_Reason**: An optional human-readable note explaining why a skill was
  deprecated, shown to consumers alongside the deprecation warning.
- **Prior_Status**: The Lifecycle_Status a skill held immediately before entering a
  Governed_State, used to restore it to a sensible Active_State.
- **Catalog_Admin / Org_Admin**: The administrative roles permitted to perform lifecycle
  governance transitions.

## Requirements

### Requirement 1: Deprecate a Skill

**User Story:** As a Catalog_Admin, I want to deprecate a skill, so that consumers are warned it is outdated while still being able to use it.

#### Acceptance Criteria

1. WHEN a Catalog_Admin or Org_Admin deprecates a skill, THE Registry SHALL change the skill's Lifecycle_Status to `Deprecated` and return the updated skill.
2. THE Registry SHALL accept an optional Deprecation_Reason (up to 1000 characters) when deprecating a skill and SHALL store it on the skill.
3. THE Registry SHALL allow deprecation only from an Active_State (`Draft`, `Published`, `Team_Approved`, `Company_Approved`); deprecating a skill already `Deprecated`, `Archived`, or `Blocked` SHALL be rejected with HTTP 409.
4. WHEN a skill is deprecated, THE Registry SHALL record the skill's Prior_Status so a later Restore returns it to that state.
5. WHILE a skill is `Deprecated`, THE Registry SHALL continue to allow viewing and export/copy (per the `skill-export` milestone) and SHALL surface the deprecated status and Deprecation_Reason in the skill detail response so the frontend can warn users.
6. WHEN a skill is deprecated, THE Registry SHALL record an Audit_Event with action `skill.deprecated`, the actor, the target skill, and metadata including the Prior_Status and Deprecation_Reason.

### Requirement 2: Block a Skill

**User Story:** As a Catalog_Admin, I want to block an unsafe skill, so that no one can export or copy it.

#### Acceptance Criteria

1. WHEN a Catalog_Admin or Org_Admin blocks a skill, THE Registry SHALL change the skill's Lifecycle_Status to `Blocked` and return the updated skill.
2. THE Registry SHALL allow blocking from any state except `Blocked` itself; blocking an already-`Blocked` skill SHALL be rejected with HTTP 409.
3. WHEN a skill is blocked, THE Registry SHALL record the skill's Prior_Status so a later Restore returns it to that state.
4. WHILE a skill is `Blocked`, THE Registry SHALL deny export and copy operations for that skill with HTTP 409 (already enforced by the `skill-export` milestone).
5. WHILE a skill is `Blocked`, THE Registry SHALL exclude the skill from default catalog listings for all users, and detail retrieval SHALL clearly indicate the blocked status to users with edit authority.
6. WHEN a skill is blocked, THE Registry SHALL record an Audit_Event with action `skill.blocked`, the actor, the target skill, and metadata including the Prior_Status.

### Requirement 3: Archive a Skill

**User Story:** As a Catalog_Admin, I want to archive a retired skill, so that it is hidden from normal discovery without being deleted.

#### Acceptance Criteria

1. WHEN a Catalog_Admin or Org_Admin archives a skill, THE Registry SHALL change the skill's Lifecycle_Status to `Archived` and return the updated skill.
2. THE Registry SHALL allow archiving from an Active_State or from `Deprecated`; archiving a skill that is `Archived` or `Blocked` SHALL be rejected with HTTP 409.
3. WHEN a skill is archived, THE Registry SHALL record the skill's Prior_Status so a later Restore returns it to that state.
4. WHILE a skill is `Archived`, THE Registry SHALL exclude the skill from default catalog listings but SHALL include it when the caller explicitly requests archived skills.
5. WHEN a skill is archived, THE Registry SHALL record an Audit_Event with action `skill.archived`, the actor, the target skill, and metadata including the Prior_Status.

### Requirement 4: Restore a Skill

**User Story:** As a Catalog_Admin, I want to restore a deprecated, archived, or blocked skill, so that governance actions are reversible.

#### Acceptance Criteria

1. WHEN a Catalog_Admin or Org_Admin restores a skill, THE Registry SHALL change the skill's Lifecycle_Status from its Governed_State back to its recorded Prior_Status and return the updated skill.
2. THE Registry SHALL allow restore only from a Governed_State (`Deprecated`, `Archived`, `Blocked`); restoring a skill in an Active_State SHALL be rejected with HTTP 409.
3. IF a skill in a Governed_State has no recorded Prior_Status (e.g. it was seeded directly in that state), THEN THE Registry SHALL restore it to `Draft`.
4. WHEN a skill is restored, THE Registry SHALL clear the Deprecation_Reason and the recorded Prior_Status.
5. WHEN a skill is restored, THE Registry SHALL record an Audit_Event with action `skill.restored`, the actor, the target skill, and metadata including the from-status and the resulting status.

### Requirement 5: Transition Authorization and Validation

**User Story:** As an Org_Admin, I want lifecycle transitions restricted to governance roles, so that ordinary authors cannot silently block or unblock skills.

#### Acceptance Criteria

1. THE Registry SHALL permit the deprecate, block, archive, and restore transitions only to users with the `Catalog_Admin` or `Org_Admin` role; all other roles SHALL be rejected with HTTP 403.
2. THE Registry SHALL enforce organization scoping on lifecycle transitions — a transition SHALL only apply to a skill within the acting user's organization; a skill outside it SHALL return HTTP 404.
3. IF a lifecycle transition targets a skill that does not exist, THEN THE Registry SHALL return HTTP 404.
4. THE Registry SHALL reject any transition that is not permitted from the skill's current Lifecycle_Status with HTTP 409 and a message identifying the current status and the attempted transition.
5. THE Registry SHALL treat lifecycle transitions as skill-level operations that do not create a new Skill_Version and do not modify version content.

### Requirement 6: Listing and Retrieval Effects

**User Story:** As a Skill_Consumer, I want blocked and archived skills kept out of my way, so that discovery surfaces only usable skills.

#### Acceptance Criteria

1. THE Registry SHALL exclude `Blocked` skills from default catalog listings for all users regardless of visibility or role.
2. THE Registry SHALL exclude `Archived` skills from default catalog listings but SHALL include them when the caller passes an explicit "include archived" indicator or filters by `status=Archived`.
3. WHEN a caller filters the listing by a specific status (e.g. `status=Deprecated`), THE Registry SHALL return skills in that status subject to visibility rules, except that `Blocked` skills SHALL remain excluded from listings for users without edit authority.
4. THE Registry SHALL continue to return a `Blocked` or `Archived` skill on direct detail retrieval to users who have visibility of it, so that admins and owners can inspect and restore it.
5. THE Registry SHALL include the Lifecycle_Status and, when `Deprecated`, the Deprecation_Reason in skill detail and summary responses so the frontend can render status badges and warnings.

### Requirement 7: Frontend Governance Controls

**User Story:** As a Catalog_Admin, I want lifecycle controls in the UI, so that I can govern skills without using the API directly.

#### Acceptance Criteria

1. THE Skill_Browser_Frontend SHALL present Deprecate, Block, Archive, and Restore controls on the skill detail page to users with `Catalog_Admin` or `Org_Admin` role, showing only the transitions valid from the skill's current status.
2. WHEN deprecating from the frontend, THE Skill_Browser_Frontend SHALL let the admin enter an optional Deprecation_Reason.
3. WHEN a skill is `Deprecated`, THE Skill_Browser_Frontend SHALL show a deprecation warning (including the Deprecation_Reason when present) on the detail view and in listings.
4. WHEN a skill is `Blocked`, THE Skill_Browser_Frontend SHALL show a blocked indicator and SHALL disable export/copy controls (consistent with the `skill-export` milestone).
5. WHEN a lifecycle transition fails, THE Skill_Browser_Frontend SHALL display a user-readable error message.

## Non-Goals

- **Ranked full-text search and its blocked/archived exclusion inside the ranking query** —
  this milestone excludes blocked/archived from the existing catalog *listing* only. The
  ranked search endpoint and its scoring belong to the Full-Text Search milestone
  (parent Requirements 9–10).
- **Review/approval transitions** (`Team_Approved`, `Company_Approved`) — those belong to the
  Review Workflow milestone (parent Requirement 14). This milestone treats them only as
  valid *source* states for deprecate/block/archive.
- **Bulk lifecycle operations** — transitions are per-skill; batch operations are out of scope.
- **Scheduled/automatic deprecation** (e.g. auto-deprecate stale skills) — out of scope; the
  staleness signal belongs to the Usage Tracking milestone (parent Requirement 17).
- **Hard deletion of governed skills** — delete remains Draft-only per `skill-crud`; blocking
  and archiving are the non-destructive alternatives.
