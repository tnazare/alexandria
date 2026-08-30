# Implementation Plan: Skill CRUD

## Overview

This implementation plan covers Milestone 2a — the core Skill CRUD subsystem. It follows a bottom-up approach: database schema first, then JPA entities, repositories, service layer, REST controllers, security, and finally frontend pages/components. Property-based tests validate correctness properties from the design. Each task builds incrementally on the previous, with checkpoints to validate integration.

## Tasks

- [x] 1. Database migration and entity layer
  - [x] 1.1 Create V4 Flyway migration for skills schema
    - Create `backend/alexandria-entities/src/main/resources/db/migration/V4__skills_schema.sql`
    - Include all 6 tables: `taxonomy_entries`, `skills`, `skill_versions`, `skill_variants`, `skill_tags`, `skill_access_grants`
    - Include all unique indexes, check constraints, FK constraints, and the self-referencing FK from `skills.current_version_id` to `skill_versions`
    - Include the `search_vector` tsvector column, trigger function `skills_search_vector_update()`, trigger `trg_skills_search_vector`, and GIN index
    - _Requirements: 1.1, 1.2, 2.1, 5.5, 7.1_

  - [x] 1.2 Create TaxonomyEntryEntity JPA entity
    - Create `com.alexandria.taxonomy.entity.TaxonomyEntryEntity` extending `BaseEntity` in `alexandria-entities`
    - Fields: `tagType` (String, not null), `value` (String, not null), `description` (String, nullable), `active` (boolean, default true)
    - Map to `taxonomy_entries` table with `@Entity` and `@Table`
    - _Requirements: 7.1, 7.3_

  - [x] 1.3 Create SkillEntity JPA entity
    - Create `com.alexandria.skill.entity.SkillEntity` extending `BaseEntity` in `alexandria-entities`
    - Fields: `ownerTeamId`, `name`, `slug`, `description`, `lifecycleStatus` (default "Draft"), `visibility` (default "private"), `riskLevel` (nullable), `currentVersionId` (nullable), `createdBy`, `searchVector` (insertable=false, updatable=false)
    - Map to `skills` table
    - _Requirements: 1.1, 1.7, 1.9_

  - [x] 1.4 Create SkillVersionEntity JPA entity
    - Create `com.alexandria.skill.entity.SkillVersionEntity` in `alexandria-entities`
    - Fields: `id` (UUID PK), `skillId`, `versionNumber`, `canonicalInstructions`, `applicability`, `limitations`, `whenToUse`, `examples`, `changelog`, `supportedTools` (List<String> mapped as TEXT[] via `@JdbcTypeCode`), `publishedAt`, `createdAt`
    - Use `@PrePersist` for `createdAt` default
    - _Requirements: 1.1, 1.10, 3.1, 3.2_

  - [x] 1.5 Create SkillVariantEntity, SkillTagEntity, and SkillAccessGrantEntity
    - Create `com.alexandria.skill.entity.SkillVariantEntity` with fields: `id`, `skillVersionId`, `tool`, `instructions`, `createdAt`, `updatedAt`
    - Create `com.alexandria.skill.entity.SkillTagEntity` with fields: `id`, `skillId`, `taxonomyEntryId`, `createdAt`
    - Create `com.alexandria.skill.entity.SkillAccessGrantEntity` with fields: `id`, `skillId`, `userId`, `grantedAt`
    - _Requirements: 5.4, 6.1, 7.2_

  - [x] 1.6 Create repository interfaces
    - Create `SkillRepository` with `findByIdAndOrganizationId`, `findByOrganizationIdAndSlug`, `existsByOrganizationIdAndSlug`, and `findVisibleSkills` native query with visibility predicates
    - Create `SkillVersionRepository` with `findBySkillIdOrderByVersionNumberDesc`, `findBySkillIdAndVersionNumber`, `countBySkillId`
    - Create `SkillVariantRepository` with `findBySkillVersionId`, `findBySkillVersionIdAndTool`, `existsBySkillVersionIdAndTool`
    - Create `TaxonomyEntryRepository` with `findByOrganizationIdOrderByTagTypeAscValueAsc`, `findByOrganizationIdAndTagTypeOrderByValueAsc`, `existsByOrganizationIdAndTagTypeAndValueIgnoreCase`, `findByOrganizationIdAndIdIn`
    - Create `SkillTagRepository` with `findBySkillId`, `deleteBySkillId`, `existsByTaxonomyEntryId`
    - Create `SkillAccessGrantRepository` with `existsBySkillIdAndUserId`, `deleteBySkillId`
    - _Requirements: 2.3, 5.5, 6.4, 7.6, 7.7_

  - [x]* 1.7 Write integration test for V4 migration and repository queries
    - Verify V4 migration applies cleanly with Testcontainers
    - Test unique index constraints (slug uniqueness, variant uniqueness per tool/version, taxonomy value uniqueness)
    - Test `findVisibleSkills` native query with various visibility/role combinations
    - Test full-text search via `search_vector` GIN index
    - _Requirements: 1.2, 5.5, 6.4, 7.10_

- [x] 2. Checkpoint - Ensure entity layer compiles and migration tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Service layer — Taxonomy
  - [x] 3.1 Create TaxonomyService interface and TaxonomyServiceImpl
    - Create `com.alexandria.taxonomy.TaxonomyService` interface in `alexandria-service`
    - Create `com.alexandria.taxonomy.TaxonomyServiceImpl` implementing taxonomy CRUD
    - Implement `getAllGrouped(orgId)` — returns entries grouped by tag type
    - Implement `getByType(orgId, tagType)` — returns entries for a type
    - Implement `create(orgId, tagType, request)` — validates uniqueness (case-insensitive), creates entry
    - Implement `update(orgId, tagType, tagId, request)` — updates value/description, validates uniqueness
    - Implement `deactivate(orgId, tagType, tagId)` — sets `active = false`, does not delete
    - Fire audit events for create/update/deactivate operations
    - _Requirements: 7.3, 7.4, 7.5, 7.6, 7.7, 7.10, 7.11, 7.12_

  - [x]* 3.2 Write property tests for taxonomy uniqueness (Property 13)
    - **Property 13: Taxonomy value uniqueness within type**
    - Generate random tag type + value combinations with case variations
    - Verify that duplicate values (case-insensitive) within the same type are rejected with 409
    - **Validates: Requirements 7.10**

  - [x]* 3.3 Write property test for taxonomy deactivation (Property 14)
    - **Property 14: Taxonomy deactivation preserves existing references**
    - Generate scenarios with active tags assigned to skills, then deactivate
    - Verify existing associations remain intact, new assignments are rejected with 422
    - **Validates: Requirements 7.5, 7.12**

- [x] 4. Service layer — Skill lifecycle
  - [x] 4.1 Create SkillSlugGenerator utility
    - Create `com.alexandria.skill.SkillSlugGenerator` in `alexandria-service`
    - Implement `generateSlug(name)` — lowercase, replace spaces/underscores with hyphens, strip invalid chars, collapse consecutive hyphens, trim leading/trailing hyphens
    - Implement `generateUniqueSlug(name, orgId, repository)` — append numeric suffix if collision
    - _Requirements: 1.2, 1.3_

  - [x]* 4.2 Write property test for slug generation (Property 1)
    - **Property 1: Slug generation produces well-formed, unique identifiers**
    - Generate random valid skill names (3–200 chars)
    - Verify slug is non-empty, lowercase, only `[a-z0-9-]`, no consecutive hyphens, no leading/trailing hyphens
    - Verify collision resolution produces unique slugs
    - **Validates: Requirements 1.2, 1.3**

  - [x] 4.3 Create SkillService interface and SkillServiceImpl — creation logic
    - Create `com.alexandria.skill.SkillService` interface in `alexandria-service`
    - Create `com.alexandria.skill.SkillServiceImpl` with skill creation logic
    - Implement `create(request, userDetails)` — validate team ownership/membership, generate slug, create skill with Draft status + private visibility, create initial SkillVersion (version 1, changelog "Initial draft"), fire audit event
    - Inject `SkillSlugGenerator`, `SkillRepository`, `SkillVersionRepository`, `TeamRepository`, `TeamMemberRepository`, `AuditService`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10_

  - [x]* 4.4 Write property tests for skill creation invariants (Property 2)
    - **Property 2: Skill creation invariants**
    - Generate valid creation requests with different names/teams
    - Verify lifecycle_status="Draft", visibility="private", organization_id matches user, version_number=1, changelog="Initial draft"
    - **Validates: Requirements 1.1, 1.7, 1.9, 1.10**

  - [x] 4.5 Implement skill retrieval and list logic in SkillServiceImpl
    - Implement `getById(skillId, userDetails)` — load skill, enforce visibility, return full detail with current version content
    - Implement `list(filters, userDetails)` — call `findVisibleSkills` with visibility predicates, support pagination/filtering/search
    - Implement `getBySlug(slug, userDetails)` — lookup by slug within user's org
    - Implement `listVersions(skillId, userDetails, pageable)` — paginated version list descending
    - Implement `getVersion(skillId, versionNumber, userDetails)` — specific version detail
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [x]* 4.6 Write property test for visibility enforcement (Property 5)
    - **Property 5: Visibility enforcement**
    - Generate tuples of (visibility, user role, team membership, creator status, access grants)
    - Verify view access is granted/denied exactly per visibility rules
    - Verify denied access returns 404 with no metadata leakage
    - **Validates: Requirements 2.4, 5.1, 5.2, 5.3, 5.4, 5.9**

  - [x] 4.7 Implement skill editing and versioning in SkillServiceImpl
    - Implement `update(skillId, request, userDetails)` — support partial updates
    - For Draft skills: update current version in-place, no new version
    - For Published/Team_Approved/Company_Approved: create new version with incremented number, revert to Draft, require changelog
    - Reject edits for Deprecated/Archived/Blocked with 409
    - Update tags via `SkillTagRepository` (delete-and-reinsert pattern), validate tag IDs against active taxonomy
    - Fire appropriate audit events
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 7.2_

  - [x]* 4.8 Write property tests for versioning behavior (Property 6) and partial updates (Property 7)
    - **Property 6: Versioning behavior based on lifecycle state**
    - Generate lifecycle states + edit requests, verify Draft = in-place, Published+ = new version
    - **Property 7: Partial update preserves omitted fields**
    - Generate random field subsets, verify omitted fields unchanged
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.9**

  - [x] 4.9 Implement skill publishing in SkillServiceImpl
    - Implement `publish(skillId, userDetails)` — validate all required metadata present (task_type tag, visibility, supported tool, risk_level, applicability, example)
    - Return 422 with exact list of missing fields if incomplete
    - Transition lifecycle to Published, set `publishedAt` on current version
    - Reject publish for non-Draft with 409
    - Fire audit event
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

  - [x]* 4.10 Write property tests for lifecycle state machine (Property 8) and publish validation (Property 9)
    - **Property 8: Lifecycle state machine constraints**
    - Generate all lifecycle states + operations (publish, delete, edit), verify correct accept/reject
    - **Property 9: Publish validation completeness**
    - Generate skills with random subsets of required metadata, verify 422 lists exactly the missing fields
    - **Validates: Requirements 3.6, 4.1, 4.2, 4.4, 8.2**

  - [x] 4.11 Implement visibility change and skill deletion in SkillServiceImpl
    - Implement `changeVisibility(skillId, request, userDetails)` — validate value, update, fire audit
    - Implement `delete(skillId, userDetails)` — only Draft can be deleted (409 otherwise), cascade delete versions/variants/tags/grants, fire audit
    - _Requirements: 5.6, 5.7, 5.8, 8.1, 8.2, 8.3, 8.4_

- [x] 5. Service layer — Variants
  - [x] 5.1 Create SkillVariantService interface and SkillVariantServiceImpl
    - Create `com.alexandria.skill.variant.SkillVariantService` interface in `alexandria-service`
    - Create `com.alexandria.skill.variant.SkillVariantServiceImpl`
    - Implement `create(skillId, versionNumber, request, userDetails)` — validate tool enum, enforce unique per tool/version (409), create variant
    - Implement `update(skillId, versionNumber, tool, request, userDetails)` — update existing variant instructions
    - Implement `delete(skillId, versionNumber, tool, userDetails)` — remove variant or return 404
    - Implement variant resolution: return tool-specific instructions if variant exists, else fall back to canonical instructions
    - Fire audit events for create/update/delete
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10, 6.11_

  - [x]* 5.2 Write property tests for variant resolution (Property 10) and uniqueness (Property 11)
    - **Property 10: Variant resolution with fallback**
    - Generate skill versions with various variant sets, query different tools, verify correct resolution
    - **Property 11: Unique variant per tool per version**
    - Attempt duplicate variant creation, verify 409
    - **Validates: Requirements 6.4, 6.5, 6.6**

- [x] 6. Checkpoint - Ensure all service layer tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Security and REST layer
  - [x] 7.1 Create SkillSecurity SpEL bean
    - Create `com.alexandria.config.SkillSecurity` in `alexandria-service`
    - Implement `canEdit(skillId, authentication)` — team member OR Catalog_Admin OR Org_Admin
    - Implement `canView(skillId, authentication)` — apply full visibility rules
    - Annotate with `@Component("skillSecurity")`
    - _Requirements: 1.5, 3.5, 4.6, 5.1, 5.2, 5.3, 5.4, 5.8, 6.9, 8.3_

  - [x]* 7.2 Write property test for edit authority (Property 3)
    - **Property 3: Edit authority enforcement**
    - Generate all (role, team membership) combinations
    - Verify access granted iff team member OR Catalog_Admin OR Org_Admin
    - **Validates: Requirements 1.5, 3.5, 4.6, 5.8, 6.9, 8.3**

  - [x] 7.3 Create SkillApi interface and Skill DTOs in alexandria-api
    - Create `com.alexandria.skill.SkillApi` interface with all skill endpoint declarations
    - Create all DTOs in `com.alexandria.skill.dto`: `CreateSkillRequest`, `UpdateSkillRequest`, `SkillResponse`, `SkillSummaryResponse`, `SkillVersionResponse`, `ChangeVisibilityRequest`, `TagResponse`, `PublishValidationResponse`
    - Include Jakarta Validation annotations (`@NotBlank`, `@Size`, `@Pattern`)
    - _Requirements: 1.1, 1.8, 2.1, 3.9_

  - [x] 7.4 Create SkillVariantApi interface and Variant DTOs in alexandria-api
    - Create `com.alexandria.skill.variant.SkillVariantApi` interface
    - Create DTOs in `com.alexandria.skill.variant.dto`: `CreateVariantRequest`, `VariantResponse`
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 7.5 Create TaxonomyApi interface and Taxonomy DTOs in alexandria-api
    - Create `com.alexandria.taxonomy.TaxonomyApi` interface
    - Create DTOs in `com.alexandria.taxonomy.dto`: `CreateTaxonomyEntryRequest`, `TaxonomyEntryResponse`, `TaxonomyGroupResponse`
    - _Requirements: 7.3, 7.6, 7.7_

  - [x] 7.6 Create SkillResource REST controller
    - Create `com.alexandria.skill.SkillResource` implementing `SkillApi` in `alexandria-service`
    - Wire `@PreAuthorize` with `@skillSecurity.canEdit(#skillId, authentication)` for edit/publish/delete/visibility endpoints
    - Wire `@PreAuthorize` with `@skillSecurity.canView(#skillId, authentication)` for GET endpoints
    - Delegate to `SkillService` for all business logic
    - _Requirements: 1.1, 2.1, 3.1, 4.3, 5.6, 8.1_

  - [x] 7.7 Create SkillVariantResource REST controller
    - Create `com.alexandria.skill.variant.SkillVariantResource` implementing `SkillVariantApi`
    - Wire security checks via `@PreAuthorize`
    - Delegate to `SkillVariantService`
    - _Requirements: 6.1, 6.7, 6.8_

  - [x] 7.8 Create TaxonomyResource REST controller
    - Create `com.alexandria.taxonomy.TaxonomyResource` implementing `TaxonomyApi`
    - Wire `@PreAuthorize` for Catalog_Admin-only endpoints
    - Delegate to `TaxonomyService`
    - _Requirements: 7.3, 7.4, 7.5, 7.6, 7.7, 7.8_

  - [x] 7.9 Create SkillExceptionHandler for error mapping
    - Satisfied by the existing `com.alexandria.common.exception.GlobalExceptionHandler` `@RestControllerAdvice`
    - Maps `SkillNotFoundException` → 404, `SkillConflictException` → 409, `PublishValidationException` → 422 (with `missingFields`), `SkillAccessDeniedException` → 403, `TaxonomyConflictException` → 409, `MethodArgumentNotValidException`/`IllegalArgumentException` → 400
    - Exception classes already exist: `SkillNotFoundException`, `SkillConflictException`, `PublishValidationException`, `SkillAccessDeniedException`
    - No duplicate handler created to avoid conflicting `@RestControllerAdvice` beans
    - _Requirements: 1.8, 2.5, 3.6, 4.2, 4.4, 6.3, 6.4, 7.10, 8.2_

  - [x]* 7.10 Write property test for input validation (Property 4)
    - **Property 4: Input validation rejects invalid payloads**
    - Generate requests with fields violating length constraints or missing required fields
    - Verify HTTP 400 with field-level error messages, skill state unchanged
    - **Validates: Requirements 1.8, 3.10**

  - [x]* 7.11 Write integration tests for full REST endpoint round-trips
    - Test skill creation → retrieval → edit → publish → visibility change → delete flow with MockMvc + JWT mocking
    - Test variant CRUD lifecycle
    - Test taxonomy CRUD with admin/non-admin users
    - Test visibility enforcement end-to-end with different user roles
    - Verify audit events recorded correctly
    - _Requirements: 1.6, 2.1, 3.7, 4.5, 5.6, 6.11, 7.11, 8.4_

  - [x]* 7.12 Write property tests for retrieval round-trip (Property 15) and version ordering (Property 16)
    - **Property 15: Skill retrieval round-trip**
    - Create skills with random valid inputs, retrieve by ID, verify all fields match
    - **Property 16: Version list ordering**
    - Create skills with multiple versions, verify descending order with no gaps
    - **Validates: Requirements 2.1, 2.6**

  - [x]* 7.13 Write property test for tag validation (Property 12)
    - **Property 12: Tag validation against active taxonomy**
    - Generate tag ID sets mixing valid, invalid, inactive, and wrong-org entries
    - Verify assignment is rejected entirely if any tag ID fails validation
    - **Validates: Requirements 7.2**

- [x] 8. Checkpoint - Ensure backend compiles and all tests pass
  - `./mvnw verify` → 221 tests, 0 failures, 0 errors, BUILD SUCCESS.

- [x] 9. Frontend — API client and shared components
  - [x] 9.1 Create skills and taxonomy API client modules
    - Create `src/api/skills.ts` with all skill endpoint functions using axios/TanStack Query patterns
    - Create `src/api/taxonomy.ts` with all taxonomy endpoint functions
    - Define TypeScript types/interfaces for all request/response DTOs
    - _Requirements: 1.1, 2.1, 6.1, 7.6_

  - [x] 9.2 Create shared UI components — LifecycleStatusBadge, RiskLevelBadge, VisibilitySelector
    - Create `LifecycleStatusBadge` — color-coded badge for lifecycle states (Draft=gray, Published=green, Deprecated=orange, etc.)
    - Create `RiskLevelBadge` — badge for risk level display
    - Create `VisibilitySelector` — dropdown with visibility options (private, team, organization, restricted) and descriptions
    - _Requirements: 2.1, 4.3, 5.6_

  - [x] 9.3 Create MarkdownEditor and TagPicker components
    - Create `MarkdownEditor` — textarea with Markdown preview for canonical instructions and variant instructions
    - Create `TagPicker` — multi-select component with taxonomy autocomplete, grouped by tag type, shows only active entries
    - _Requirements: 1.1, 7.2, 7.9_

- [x] 10. Frontend — Skill pages
  - [x] 10.1 Create SkillCreatePage
    - Create `src/pages/SkillCreatePage.tsx` at route `/catalog/skills/new`
    - Implement `SkillForm` with fields: name, description, owner team (dropdown), canonical instructions (MarkdownEditor)
    - Client-side validation matching backend constraints (name 3-200, description 1-2000, instructions 1-100000)
    - On success: navigate to skill detail page
    - _Requirements: 1.1, 1.8_

  - [x] 10.2 Create SkillEditPage with versioning awareness
    - Create `src/pages/SkillEditPage.tsx` at route `/catalog/skills/:skillId/edit`
    - Load current skill data, pre-fill form
    - Show changelog field (required if editing published skill, optional for draft)
    - Include TagPicker, VisibilitySelector, risk level selector, supported tools multi-select
    - Show PublishChecklist component when skill is in Draft — highlight missing metadata for publishing
    - _Requirements: 3.1, 3.4, 3.9, 4.1, 7.2_

  - [x] 10.3 Enhance SkillDetailPage with variants and version info
    - Update existing `SkillDetailPage` to show full skill detail including: metadata badges, current version content, tag list, variant tabs
    - Add `VariantTabs` component showing all variants for the current version with tool-specific instructions
    - Add publish button (visible for Draft skills with edit authority)
    - Add visibility change control
    - Add link to version history
    - _Requirements: 2.1, 6.5, 6.6_

  - [x] 10.4 Create VariantEditor and PublishChecklist components
    - Create `VariantEditor` — tool selector dropdown + Markdown instructions editor, supports create/edit/delete
    - Create `PublishChecklist` — displays publish readiness showing which required fields are present/missing
    - _Requirements: 4.1, 4.2, 6.1, 6.7_

  - [x] 10.5 Create SkillVersionsPage and SkillVersionDetailPage
    - Create `src/pages/SkillVersionsPage.tsx` at route `/catalog/skills/:skillId/versions`
    - Show paginated list of versions with `VersionTimeline` component (version number, changelog, published_at, created_at)
    - Create `src/pages/SkillVersionDetailPage.tsx` at route `/catalog/skills/:skillId/versions/:versionNumber`
    - Show full version content with variants
    - _Requirements: 2.6, 2.7_

- [x] 11. Frontend — Taxonomy management and skill list
  - [x] 11.1 Create TaxonomyManagementPage (admin)
    - Create `src/admin/TaxonomyManagementPage.tsx` at route `/admin/taxonomy`
    - Show taxonomy entries grouped by tag type in tabbed view
    - Support create, edit, deactivate operations with inline forms
    - Show active/inactive status, prevent re-use of deactivated entries in tag picker
    - Only accessible to Catalog_Admin and Org_Admin roles
    - _Requirements: 7.3, 7.4, 7.5, 7.6, 7.7, 7.8_

  - [x] 11.2 Create SkillSearchFilters and enhance skill list page
    - Create `SkillSearchFilters` component with: status filter, visibility filter, team filter, tag filter, full-text search input
    - Integrate filters with skill list query using TanStack Query's URL param sync
    - Show `SkillSummaryResponse` cards with name, description, team, lifecycle badge, risk badge, tags, updated_at
    - _Requirements: 2.3, 7.9_

  - [x]* 11.3 Write frontend component tests
    - Test SkillForm validation (required fields, length limits)
    - Test VisibilitySelector renders correct options
    - Test TagPicker filters out inactive entries
    - Test PublishChecklist correctly identifies missing fields
    - Test LifecycleStatusBadge renders correct colors
    - _Requirements: 1.8, 4.1, 7.2_

- [x] 12. Frontend — Routing and integration wiring
  - [x] 12.1 Wire frontend routes and navigation
    - Add routes to `App.tsx`: `/catalog/skills/new`, `/catalog/skills/:skillId`, `/catalog/skills/:skillId/edit`, `/catalog/skills/:skillId/versions`, `/catalog/skills/:skillId/versions/:versionNumber`, `/admin/taxonomy`
    - Add navigation links in sidebar/header for Skills and Taxonomy (admin)
    - Implement role-based route guards (taxonomy management requires Catalog_Admin+)
    - _Requirements: 2.1, 7.8_

  - [x]* 12.2 Write frontend integration tests
    - Test full skill creation flow (form → API → navigation)
    - Test skill list with search and filters
    - Test version history navigation
    - Test variant CRUD in edit page
    - _Requirements: 1.1, 2.3, 2.6, 6.1_

- [x] 13. Final checkpoint - Ensure all tests pass
  - Backend `./mvnw verify` → 221 tests, 0 failures, 0 errors, BUILD SUCCESS.
  - Frontend `npm run test` → 23 tests passed; `npm run build` (tsc -b && vite build) → success.
  - Note: `npm run lint` fails due to a pre-existing `eslint.config.js` issue (`eslint-plugin-react-hooks` `.configs.flat` undefined in the installed version); unrelated to this work.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation between major layers
- Property tests validate the 16 universal correctness properties from the design document
- The backend uses Java 25 + Spring Boot 4, jqwik for property-based tests, Testcontainers for integration tests
- The frontend uses Vite + React 19 + TypeScript + Tailwind CSS + TanStack Query, fast-check + Vitest for property tests
- V4 migration builds on existing V3 schema (users, organizations, teams already exist)
- All entities use the `BaseEntity` pattern (UUID id, organization_id, created_at, updated_at) where applicable
- Audit events follow the existing fire-and-forget pattern with ERROR-level logging on failure

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["1.4", "1.5"] },
    { "id": 3, "tasks": ["1.6"] },
    { "id": 4, "tasks": ["1.7", "4.1"] },
    { "id": 5, "tasks": ["3.1", "4.2"] },
    { "id": 6, "tasks": ["3.2", "3.3", "4.3"] },
    { "id": 7, "tasks": ["4.4", "4.5"] },
    { "id": 8, "tasks": ["4.6", "4.7"] },
    { "id": 9, "tasks": ["4.8", "4.9"] },
    { "id": 10, "tasks": ["4.10", "4.11", "5.1"] },
    { "id": 11, "tasks": ["5.2", "7.1"] },
    { "id": 12, "tasks": ["7.2", "7.3", "7.4", "7.5"] },
    { "id": 13, "tasks": ["7.6", "7.7", "7.8", "7.9"] },
    { "id": 14, "tasks": ["7.10", "7.11", "7.12", "7.13"] },
    { "id": 15, "tasks": ["9.1"] },
    { "id": 16, "tasks": ["9.2", "9.3"] },
    { "id": 17, "tasks": ["10.1", "10.4", "10.5"] },
    { "id": 18, "tasks": ["10.2", "10.3"] },
    { "id": 19, "tasks": ["11.1", "11.2"] },
    { "id": 20, "tasks": ["11.3", "12.1"] },
    { "id": 21, "tasks": ["12.2"] }
  ]
}
```
