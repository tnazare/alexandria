# Implementation Plan: Alexandria First Step

## Overview

This plan implements the foundational layer of the Alexandria platform — project documentation, database schema for identity management, OIDC authentication, user auto-provisioning, team management, RBAC, audit logging, health endpoints, a React/TypeScript frontend SPA (admin + skill browser), and a VitePress documentation site. The backend uses Java 25 + Spring Boot 4.0.7 with the existing multi-module Maven structure; the frontend uses Vite + React 19 + TypeScript.

## Tasks

- [ ] 1. Project structure and documentation cleanup
  - [ ] 1.1 Create root project files (README, LICENSE, CONTRIBUTING, .gitignore)
    - Create `README.md` at repository root with project description, prerequisites (Java 25, Docker, Docker Compose), quick-start commands (`docker compose up`, health-check verification), and high-level architecture overview identifying backend modules (api, entities, service), frontend, and infrastructure
    - Create `LICENSE` with Apache 2.0 license text
    - Create `CONTRIBUTING.md` with contribution guidelines, coding standards (reference .kiro/steering/java-coding-standards.md conventions), branch naming (`feature/`, `fix/`, `docs/`), and PR process
    - Update root `.gitignore` to cover Java build artifacts (`target/`), IDE files (`.idea/`, `.vscode/`), OS files (`.DS_Store`, `Thumbs.db`), environment secrets (`.env`), and Node artifacts (`node_modules/`, `dist/`)
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 2. Backend database migration (V2)
  - [ ] 2.1 Create Flyway V2 migration script for users, teams, and team_members tables
    - Create `backend/alexandria-entities/src/main/resources/db/migration/V2__users_teams_schema.sql`
    - Define `users` table with columns: id (UUID PK DEFAULT gen_random_uuid()), organization_id (UUID NOT NULL FK to organizations ON DELETE RESTRICT), external_subject (TEXT NOT NULL), email (TEXT NOT NULL), display_name (TEXT), role (TEXT NOT NULL DEFAULT 'Skill_Consumer'), enabled (BOOLEAN NOT NULL DEFAULT TRUE), created_at (TIMESTAMPTZ NOT NULL DEFAULT NOW()), updated_at (TIMESTAMPTZ NOT NULL DEFAULT NOW())
    - Create unique index `uq_users_org_subject` on (organization_id, external_subject)
    - Create index `idx_users_org_id` on users(organization_id)
    - Define `teams` table with columns: id (UUID PK DEFAULT gen_random_uuid()), organization_id (UUID NOT NULL FK to organizations ON DELETE RESTRICT), name (TEXT NOT NULL), slug (TEXT NOT NULL), description (TEXT), created_by (UUID NOT NULL FK to users ON DELETE RESTRICT), created_at (TIMESTAMPTZ NOT NULL DEFAULT NOW()), updated_at (TIMESTAMPTZ NOT NULL DEFAULT NOW())
    - Create unique index `uq_teams_org_slug` on (organization_id, slug)
    - Create indexes `idx_teams_org_id` and `idx_teams_created_by`
    - Define `team_members` table with columns: id (UUID PK DEFAULT gen_random_uuid()), team_id (UUID NOT NULL FK to teams ON DELETE RESTRICT), user_id (UUID NOT NULL FK to users ON DELETE RESTRICT), team_role (TEXT NOT NULL), joined_at (TIMESTAMPTZ NOT NULL DEFAULT NOW())
    - Create unique index `uq_team_members_team_user` on (team_id, user_id)
    - Create indexes `idx_team_members_team_id` and `idx_team_members_user_id`
    - Add FK constraint on audit_events.actor_user_id referencing users(id) ON DELETE RESTRICT
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

- [ ] 3. Backend entities and repositories
  - [ ] 3.1 Create Role enum and UserEntity
    - Create `Role` enum in `alexandria-entities` module at `com.alexandria.user.entity.Role` with values: Read_Only_Auditor(0), Skill_Consumer(1), Skill_Author(2), Reviewer(3), Team_Owner(4), Catalog_Admin(5), Org_Admin(6) and privilege field with `hasAtLeast(Role)` and `isAdmin()` methods
    - Create `UserEntity` extending `BaseEntity` in `com.alexandria.user.entity` with fields: externalSubject (String, not null), email (String, not null), displayName (String, nullable), role (String, not null default "Skill_Consumer"), enabled (boolean, default true). Map to `users` table
    - Create `UserRepository` in `com.alexandria.user` with methods: `findByOrganizationIdAndExternalSubject(UUID orgId, String subject)`, `findByOrganizationId(UUID orgId, Pageable)`, `findByIdAndOrganizationId(UUID id, UUID orgId)`
    - _Requirements: 2.1, 2.9, 2.10, 6.2_

  - [ ] 3.2 Create TeamEntity, TeamMemberEntity, and repositories
    - Create `TeamEntity` extending `BaseEntity` in `com.alexandria.team.entity` with fields: name (String, not null), slug (String, not null), description (String, nullable), createdBy (UUID, not null). Map to `teams` table
    - Create `TeamMemberEntity` in `com.alexandria.team.entity` (does NOT extend BaseEntity) with fields: id (UUID PK), teamId (UUID, not null), userId (UUID, not null), teamRole (String, not null), joinedAt (Instant, not null). Map to `team_members` table
    - Create `TeamRepository` in `com.alexandria.team` with methods: `findByOrganizationId(UUID orgId, Pageable)`, `findByIdAndOrganizationId(UUID id, UUID orgId)`, `existsByOrganizationIdAndSlug(UUID orgId, String slug)`
    - Create `TeamMemberRepository` in `com.alexandria.team` with methods: `findByTeamId(UUID teamId)`, `findByTeamIdAndUserId(UUID teamId, UUID userId)`, `countByTeamIdAndTeamRole(UUID teamId, String role)`, `deleteByTeamIdAndUserId(UUID teamId, UUID userId)`
    - _Requirements: 2.3, 2.4, 2.5, 2.6, 2.9_

  - [ ]* 3.3 Write property test for Role enum validation (Property 2)
    - **Property 2: Role enum membership validation**
    - Generate random strings and verify that only the 7 allowed role values are accepted; all others are rejected. Similarly verify team_role accepts only "owner" or "member"
    - Use jqwik with minimum 100 iterations
    - **Validates: Requirements 2.9, 6.7**

- [ ] 4. Backend security (OIDC, provisioning filter, RBAC)
  - [ ] 4.1 Configure OIDC resource server and update SecurityConfig
    - Add `spring-boot-starter-oauth2-resource-server` dependency to `alexandria-service/pom.xml`
    - Update `application.yml` with `spring.security.oauth2.resourceserver.jwt.issuer-uri` and audience configuration using `${OIDC_ISSUER_URI}` and `${OIDC_CLIENT_ID}` environment variables
    - Create `OidcProperties` configuration properties class in `com.alexandria.config`
    - Update `SecurityConfig` to configure JWT resource server, permit `/actuator/health` and `/actuator/info` without auth, require authentication for all other endpoints, and set session policy to STATELESS
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [ ] 4.2 Implement AlexandriaUserDetails and UserProvisioningFilter
    - Create `AlexandriaUserDetails` in `com.alexandria.auth` implementing custom principal with userId (UUID), organizationId (UUID), role (Role), email (String), and `getAuthorities()` returning a single GrantedAuthority matching the role
    - Create `UserProvisioningFilter` as a `OncePerRequestFilter` in `com.alexandria.config` that: extracts `sub` and `email` claims from `JwtAuthenticationToken`, validates email format (rejects with 403 if missing/invalid), looks up user by (orgId, sub), creates new user with Skill_Consumer role if not found, builds `AlexandriaUserDetails`, replaces SecurityContext authentication with enriched principal
    - Register the filter in SecurityConfig after `BearerTokenAuthenticationFilter`
    - Handle provisioning failures: respond 500, log audit event with action "user.auto_provision_failed"
    - _Requirements: 4.1, 4.2, 4.4, 4.5, 4.6, 4.7_

  - [ ] 4.3 Implement RBAC enforcement via @PreAuthorize
    - Configure method-level security expressions that check `AlexandriaUserDetails` role
    - Create `TeamSecurity` bean in `com.alexandria.config` with `isOwnerOrAdmin(UUID teamId, Authentication auth)` for team-member-scoped operations
    - Define SpEL expressions: `hasRole('Org_Admin')`, `hasAnyRole('Org_Admin', 'Catalog_Admin')`, `@teamSecurity.isOwnerOrAdmin(#teamId, authentication)`
    - Ensure org scoping is enforced: all data queries filter by authenticated user's organizationId
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.10, 6.11_

  - [ ]* 4.4 Write property test for email claim validation (Property 3)
    - **Property 3: Email claim validation on every request**
    - Generate invalid email formats (missing @, empty local part, empty domain, null, empty string) and verify the filter rejects with 403
    - Use jqwik with minimum 100 iterations
    - **Validates: Requirements 4.4, 4.7**

  - [ ]* 4.5 Write property test for user provisioning idempotency (Property 4)
    - **Property 4: User provisioning idempotency**
    - Generate random (orgId, subject, email) tuples, simulate multiple sequential provisioning calls, verify exactly one user record exists after all calls
    - Use jqwik with minimum 100 iterations, Testcontainers for database
    - **Validates: Requirements 4.1, 4.2**

- [ ] 5. Checkpoint - Verify security layer
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. Backend services and resources
  - [ ] 6.1 Implement AuditService
    - Create `AuditService` interface in `com.alexandria.audit` with method `record(UUID orgId, UUID actorUserId, String action, String targetType, UUID targetId, Map<String, Object> metadata)`
    - Create `AuditServiceImpl` in `com.alexandria.audit` with fire-and-forget pattern: catch all exceptions, log at ERROR level with full event details, never let audit failures propagate to callers
    - _Requirements: 7.1, 7.2, 7.6_

  - [ ] 6.2 Implement UserService and UserResource
    - Create `UserService` interface and `UserServiceImpl` in `com.alexandria.user` with methods: `getCurrentUser(AlexandriaUserDetails)`, `listUsers(UUID orgId, Pageable)`, `changeRole(UUID orgId, UUID userId, String newRole)`
    - Create DTOs in `alexandria-api`: `UserResponse`, `CurrentUserResponse`, `UpdateRoleRequest` (records with Jakarta validation)
    - Create `UserApi` interface in `alexandria-api` at `com.alexandria.user`
    - Create `UserResource` in `alexandria-service` at `com.alexandria.user` implementing `UserApi`
    - Implement GET `/api/me` (any authenticated user), GET `/api/users` (Org_Admin), PATCH `/api/users/{userId}/role` (Org_Admin)
    - Record audit events on role change: action "user.role_changed" with previous and new role in metadata
    - _Requirements: 6.6, 6.7, 6.8, 6.9, 8.4, 8.5_

  - [ ] 6.3 Implement TeamService and TeamResource
    - Create `TeamService` interface and `TeamServiceImpl` in `com.alexandria.team` with methods: `createTeam(UUID orgId, UUID actorId, CreateTeamRequest)`, `listTeams(UUID orgId, Pageable)`, `getTeamDetail(UUID orgId, UUID teamId)`, `addMember(UUID orgId, UUID teamId, AddMemberRequest)`, `removeMember(UUID orgId, UUID teamId, UUID userId)`
    - Create DTOs in `alexandria-api`: `CreateTeamRequest`, `TeamResponse`, `TeamDetailResponse`, `AddMemberRequest`, `TeamMemberResponse`
    - Create `TeamApi` interface in `alexandria-api` at `com.alexandria.team`
    - Create `TeamResource` in `alexandria-service` at `com.alexandria.team` implementing `TeamApi`
    - Implement slug generation: lowercase, replace spaces/underscores with hyphens, strip non-[a-z0-9-], collapse consecutive hyphens, trim leading/trailing hyphens
    - Enforce duplicate slug detection (409 Conflict), last-owner protection (409 Conflict), member/team not found (404)
    - Record audit events: "team.created", "team_member.added", "team_member.removed"
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 5.11, 5.12_

  - [ ] 6.4 Implement AuditResource
    - Create `AuditApi` interface in `alexandria-api` with query parameters: page, size, target_type, target_id, actor_user_id, action, start_date, end_date
    - Create `AuditEventResponse` and `AuditEventFilter` DTOs in `alexandria-api`
    - Create `AuditResource` in `alexandria-service` at `com.alexandria.audit` implementing `AuditApi`
    - Implement GET `/api/audit-events` with AND-combined filters, org scoping, and descending created_at ordering
    - Restrict access to Org_Admin and Read_Only_Auditor via @PreAuthorize
    - _Requirements: 7.3, 7.4, 7.5_

  - [ ] 6.5 Implement HealthResource (/api/me already in UserResource, verify actuator config)
    - Verify actuator health endpoint returns status UP/DOWN with DB check
    - Verify actuator info endpoint returns app name and version from build metadata
    - Configure health indicator with 5-second DB timeout
    - Ensure `/actuator/health` and `/actuator/info` are accessible without auth
    - _Requirements: 8.1, 8.2, 8.3, 8.6_

  - [ ]* 6.6 Write property test for slug generation (Property 1)
    - **Property 1: Slug generation is a deterministic, well-formed transformation**
    - For any valid team name (2-100 chars from [a-zA-Z0-9 \-_]), verify slug is lowercase, only [a-z0-9-], no consecutive hyphens, doesn't start/end with hyphen, non-empty. Names differing only in case/whitespace-vs-hyphen/underscore-vs-hyphen produce same slug
    - Use jqwik with minimum 100 iterations
    - **Validates: Requirements 5.9**

  - [ ]* 6.7 Write property test for role ordering consistency (Property 9)
    - **Property 9: Role privilege ordering consistency**
    - Generate all role pairs, verify that if A < B in privilege, B has access to at least every endpoint A can access. Ordering is strict and transitive
    - Use jqwik with minimum 100 iterations
    - **Validates: Requirements 6.2**

  - [ ]* 6.8 Write property test for audit filter monotonicity (Property 11)
    - **Property 11: Audit event ordering and filter correctness**
    - Generate sets of audit events and random filter combinations, verify adding a filter never increases result set size and results are ordered by created_at descending
    - Use jqwik with Testcontainers, minimum 100 iterations
    - **Validates: Requirements 7.3, 7.4**

- [ ] 7. Checkpoint - Verify backend services
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 8. Frontend scaffolding and setup
  - [ ] 8.1 Initialize Vite + React 19 + TypeScript project
    - Create `frontend/` directory with Vite 6 + React 19 + TypeScript 5.x project
    - Configure `vite.config.ts` with proxy to `http://localhost:8080` for `/api` and `/actuator` in dev
    - Add dependencies: react-router (v7), tailwindcss (v4), oidc-client-ts, axios, @tanstack/react-query, @tanstack/react-table
    - Configure `tailwind.config.ts` and create base styles
    - Create `tsconfig.json` with strict mode, path aliases (`@/` → `src/`)
    - Create `src/config.ts` for runtime config (API base URL from `VITE_API_BASE_URL`, OIDC authority from `VITE_OIDC_AUTHORITY`, client ID from `VITE_OIDC_CLIENT_ID`)
    - _Requirements: 10.1, 10.10, 11.1, 11.10_

  - [ ] 8.2 Set up shared components and types
    - Create `src/shared/types/` with TypeScript interfaces matching backend DTOs: UserResponse, CurrentUserResponse, TeamResponse, TeamDetailResponse, TeamMemberResponse, AuditEventResponse, PageResponse, ErrorResponse
    - Create `src/shared/components/` with reusable UI primitives: Button, Modal, Table, Pagination, LoadingSpinner, Toast/Notification
    - Create `src/shared/hooks/` with: useDebounce, usePagination
    - _Requirements: 10.8, 10.9, 11.11_

- [ ] 9. Frontend auth integration
  - [ ] 9.1 Implement OIDC auth provider and protected routes
    - Create `src/auth/AuthProvider.tsx` wrapping oidc-client-ts UserManager with React context: handles login redirect, callback, silent refresh, logout
    - Create `src/auth/useAuth.ts` hook exposing: user, isAuthenticated, isLoading, login, logout, getAccessToken
    - Create `src/auth/ProtectedRoute.tsx` component that checks authentication and role requirements before rendering children; redirects to OIDC login if unauthenticated, shows "insufficient permissions" if role check fails
    - Create `src/auth/CallbackPage.tsx` for `/auth/callback` route handling OIDC code exchange
    - _Requirements: 10.2, 10.4, 11.2_

  - [ ] 9.2 Implement API client with token interceptor
    - Create `src/api/client.ts` with Axios instance: base URL from config, request interceptor attaching `Authorization: Bearer <token>`, response interceptor handling 401 (trigger refresh/re-login), 403 (show permission error), 4xx/5xx (show toast)
    - Create `src/api/users.ts` with functions: `getMe()`, `listUsers(page, size)`, `changeRole(userId, role)`
    - Create `src/api/teams.ts` with functions: `createTeam(name, description)`, `listTeams(page, size)`, `getTeam(teamId)`, `addMember(teamId, userId, role)`, `removeMember(teamId, userId)`
    - Create `src/api/audit.ts` with function: `listAuditEvents(filters)`
    - _Requirements: 10.1, 10.8, 11.1, 11.11_

- [ ] 10. Frontend admin pages
  - [ ] 10.1 Create admin layout and routing
    - Create `src/App.tsx` with React Router 7 configuration: role-gated admin routes, browser routes, auth callback, unauthorized page
    - Create `src/admin/AdminLayout.tsx` with sidebar navigation (Users, Teams, Audit Logs) and content area
    - Create `src/pages/UnauthorizedPage.tsx` for insufficient permissions
    - Wire routing: `/admin/*` requires Org_Admin or Catalog_Admin role, `/catalog/*` requires any authenticated user
    - _Requirements: 10.3, 10.4_

  - [ ] 10.2 Implement Users management page
    - Create `src/admin/UsersPage.tsx` with paginated table (TanStack Table) showing email, display name, role, enabled status, creation date
    - Implement inline role change dropdown calling PATCH `/api/users/{id}/role`
    - Use TanStack Query for data fetching with loading/error states
    - _Requirements: 10.5_

  - [ ] 10.3 Implement Teams management pages
    - Create `src/admin/TeamsPage.tsx` with paginated team list showing name, member count, creation date, and "Create Team" button with modal form
    - Create `src/admin/TeamDetailPage.tsx` showing team members with add/remove controls
    - Handle form validation (name: 2-100 chars, [a-zA-Z0-9 \-_])
    - Handle API errors: 409 Conflict for duplicate name, 404 for not found
    - _Requirements: 10.6_

  - [ ] 10.4 Implement Audit Logs page
    - Create `src/admin/AuditLogsPage.tsx` with paginated table showing timestamp, actor, action, target type, target ID
    - Implement filter controls: action type dropdown, target type dropdown, actor search, date range picker
    - Results sorted by timestamp descending, filters applied as URL query params
    - _Requirements: 10.7_

- [ ] 11. Frontend skill browser pages
  - [ ] 11.1 Create browser layout and catalog page
    - Create `src/browser/BrowserLayout.tsx` with catalog-focused layout
    - Create `src/browser/CatalogPage.tsx` with search input, filter panel, and paginated skill results grid/list
    - Create `src/browser/components/SearchBar.tsx` with debounced input
    - Create `src/browser/components/FilterPanel.tsx` with filters for: task type, language, framework, tool, approval state, risk level, owner team, visibility
    - Create `src/browser/components/SkillCard.tsx` displaying: name, description, owner team, lifecycle status, approval state, tools, risk level, last updated
    - _Requirements: 11.3, 11.4, 11.5_

  - [ ] 11.2 Create skill detail page with export actions
    - Create `src/browser/SkillDetailPage.tsx` displaying: overview, instructions, variants, applicability, examples, limitations, risk level, review status, version history, feedback summary
    - Implement export/copy buttons: Copy generic prompt, Copy for GitHub Copilot, Copy for Codex, Export as Markdown, Export as AGENTS.md, Export as Cursor rule, Export as Kiro steering fragment
    - Handle clipboard API and file download triggers
    - _Requirements: 11.6, 11.7, 11.8_

  - [ ] 11.3 Implement lifecycle badge component and URL state management
    - Create `src/browser/components/LifecycleBadge.tsx` with distinct color/icon for each status: Draft, Published, Team_Approved, Company_Approved, Deprecated, Archived, Blocked
    - Implement URL state serialization: search query and all filter selections persisted as URL query parameters, restored on page load for direct navigation/bookmarking
    - _Requirements: 11.9, 11.12_

  - [ ]* 11.4 Write property test for lifecycle badge exhaustiveness (Property 13)
    - **Property 13: Lifecycle badge mapping is exhaustive**
    - For every lifecycle status value, verify badge renders a non-empty, distinct visual indicator
    - Use fast-check with Vitest
    - **Validates: Requirements 11.9**

  - [ ]* 11.5 Write property test for URL state round-trip (Property 14)
    - **Property 14: URL state round-trip for search and filters**
    - Generate arbitrary combinations of search query and filter selections, serialize to URL params, parse back, verify equivalence
    - Use fast-check with Vitest, minimum 100 iterations
    - **Validates: Requirements 11.12**

- [ ] 12. Checkpoint - Verify frontend builds
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 13. Documentation site
  - [ ] 13.1 Initialize VitePress documentation site
    - Create `docs/` directory with VitePress project: `package.json`, `.vitepress/config.ts`
    - Configure site title, navigation, and sidebar structure
    - Create `docs/index.md` landing page with project introduction
    - _Requirements: 9.5_

  - [ ] 13.2 Write installation and configuration guides
    - Create `docs/guide/installation.md` with prerequisites (Java 25, Node.js, Docker, Docker Compose, PostgreSQL), step-by-step setup
    - Create `docs/guide/configuration.md` with environment variable reference: every variable with name, description, required/optional, default, example
    - Create `docs/guide/oidc-setup.md` with OIDC provider configuration steps
    - Create `docs/guide/deployment-local.md` with Docker Compose local dev guide
    - Create `docs/guide/deployment-aws.md` with Terraform/AWS deployment guide
    - _Requirements: 9.1, 9.6_

  - [ ] 13.3 Write feature guides and API reference
    - Create `docs/features/authentication.md`, `users.md`, `teams.md`, `rbac.md`, `audit.md`, `health.md` — each describing the feature's capabilities
    - Create `docs/api/overview.md` with API conventions (pagination, error format, auth header)
    - Create `docs/api/users.md`, `docs/api/teams.md`, `docs/api/audit.md` — each with method, path, request/response schemas, required role, example pairs
    - Create `docs/troubleshooting.md` covering OIDC misconfiguration, DB connection errors, container health-check failures
    - _Requirements: 9.2, 9.3, 9.7_

- [ ] 14. Docker Compose and containerization
  - [ ] 14.1 Update Docker Compose with backend and frontend services
    - Move `docker-compose.yml` to repository root (or create root-level compose file)
    - Add `backend` service: build from `./backend`, expose 8080, configure env vars (datasource, OIDC), depends on postgres with health check
    - Add `frontend` service: build from `./frontend`, expose 5173→80, configure VITE env vars, depends on backend
    - Create `backend/Dockerfile` (multi-stage: Maven build → JRE runtime)
    - Create `frontend/Dockerfile` (multi-stage: npm build → nginx serving static assets)
    - _Requirements: 1.5_

- [ ] 15. Backend integration tests
  - [ ]* 15.1 Write integration tests for V2 migration and repositories
    - Test V2 migration applies cleanly on Testcontainers PostgreSQL
    - Test UserRepository CRUD: create, find by org+subject, unique constraint enforcement
    - Test TeamRepository CRUD: create, find by org, slug uniqueness enforcement
    - Test TeamMemberRepository: add member, unique constraint, count by role
    - Verify org scoping: queries only return records matching the org ID
    - _Requirements: 2.1–2.10_

  - [ ]* 15.2 Write integration tests for security filter chain
    - Test unauthenticated requests to `/actuator/health` succeed
    - Test unauthenticated requests to `/api/users` get 401
    - Test requests with invalid JWT get 401
    - Test user provisioning on first valid JWT request
    - Test email validation rejection (missing/invalid email claim → 403)
    - Use `spring-security-test` JWT mocking with MockMvc
    - _Requirements: 3.1–3.5, 4.1, 4.4, 4.7_

  - [ ]* 15.3 Write integration tests for RBAC and endpoint access
    - Test Org_Admin can access all endpoints (Property 6 validation)
    - Test Read_Only_Auditor cannot mutate (Property 7 validation)
    - Test Skill_Consumer/Skill_Author denied admin endpoints (Property 8 validation)
    - Test org scoping: user in org A cannot see org B data (Property 5 validation)
    - Use parameterized tests iterating over roles × endpoints
    - _Requirements: 6.1, 6.4, 6.5, 6.10, 6.11_

  - [ ]* 15.4 Write integration tests for team and audit endpoints
    - Test team creation: valid name → 201, duplicate slug → 409, invalid role for creation → 403
    - Test add/remove members: valid → 201/204, last owner removal → 409, not found → 404
    - Test audit event listing: filters combine with AND logic, results ordered descending
    - Test /api/me returns correct user profile (Property 12 validation)
    - _Requirements: 5.1–5.12, 7.3, 7.4, 8.4_

  - [ ]* 15.5 Write property test for audit completeness (Property 10)
    - **Property 10: Audit completeness and field correctness**
    - For each governance operation (user provisioning, role change, team creation, member add/remove), verify exactly one audit event is created with correct organization_id, action, target_type, target_id, and non-empty metadata
    - Use jqwik with Testcontainers, minimum 100 iterations
    - **Validates: Requirements 7.1, 7.2, 4.3, 5.7, 5.8, 6.9**

- [ ] 16. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- Backend uses Java 25 + Spring Boot 4.0.7 with jqwik for property-based tests
- Frontend uses TypeScript + Vite + React 19 with fast-check for property-based tests
- Integration tests use Testcontainers (PostgreSQL) + spring-security-test for JWT mocking
- The frontend is a single Vite SPA with role-gated routing (admin + browser in one app)

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "13.1"] },
    { "id": 1, "tasks": ["3.1", "3.2", "8.1", "14.1"] },
    { "id": 2, "tasks": ["3.3", "4.1", "8.2", "13.2"] },
    { "id": 3, "tasks": ["4.2", "4.3", "9.1"] },
    { "id": 4, "tasks": ["4.4", "4.5", "6.1", "9.2"] },
    { "id": 5, "tasks": ["6.2", "6.3", "6.4", "6.5", "10.1"] },
    { "id": 6, "tasks": ["6.6", "6.7", "6.8", "10.2", "10.3", "10.4"] },
    { "id": 7, "tasks": ["11.1", "11.2", "13.3"] },
    { "id": 8, "tasks": ["11.3", "11.4", "11.5"] },
    { "id": 9, "tasks": ["15.1", "15.2", "15.3", "15.4", "15.5"] }
  ]
}
```
