# Requirements Document

## Introduction

This specification covers the foundational setup of the Alexandria platform — Milestones 0 and 1 from Phase 1. It establishes the project structure, database foundation (users, teams, roles), OIDC authentication, role-based access control, and audit logging. The outcome is a clean, well-documented repository where authenticated users can log in, be auto-provisioned, manage teams, and have their actions governed by RBAC — ready for skill CRUD in the next milestone.

## Glossary

- **Alexandria_Service**: The Spring Boot backend application serving the REST API
- **OIDC_Resource_Server**: The Spring Security component that validates JWT access tokens issued by an external OIDC identity provider
- **User**: An authenticated individual represented in the `users` table, linked to an organization
- **Team**: A named group of users within an organization, used for ownership and visibility scoping
- **Team_Member**: A join record associating a user with a team and a role within that team
- **Role**: A named set of permissions assigned to a user at the organization level (Org_Admin, Catalog_Admin, Reviewer, Team_Owner, Skill_Author, Skill_Consumer, Read_Only_Auditor)
- **RBAC_Filter**: The authorization enforcement layer that checks user roles and permissions before allowing access to a resource
- **Audit_Service**: The component responsible for recording governance-relevant actions as audit events
- **Flyway_Migration**: A versioned SQL script that evolves the database schema
- **Health_Endpoint**: The Spring Boot Actuator endpoint reporting application readiness and liveness
- **Organization**: The root tenant entity; every other entity references an organization
- **Documentation_Site**: A set of generated human-readable pages covering installation, configuration, and feature documentation for the Alexandria platform
- **Admin_Frontend**: A React/TypeScript single-page application providing administrative management capabilities for users, teams, roles, taxonomy, and audit logs
- **Skill_Browser_Frontend**: A React/TypeScript single-page application providing skill catalog browsing, searching, filtering, and detail viewing for end users
- **OIDC_Login_Flow**: The frontend-initiated authentication flow that redirects unauthenticated users to the configured OIDC identity provider and handles the callback with token exchange
- **API_Client**: The frontend HTTP client configured to send authenticated requests to the Alexandria_Service backend REST API at the configured base URL

## Requirements

### Requirement 1: Project Structure and Documentation

**User Story:** As a contributor, I want the repository to have proper documentation and structure, so that I can understand how to build, run, and contribute to the project.

#### Acceptance Criteria

1. THE Alexandria_Service repository SHALL contain a README.md file with project description, prerequisites (Java version, Docker, Docker Compose), step-by-step quick-start commands to build and run the system locally, a verification step confirming successful startup, and a high-level architecture overview identifying the backend modules (api, entities, service), frontend, and infrastructure components
2. THE Alexandria_Service repository SHALL contain a CONTRIBUTING.md file with contribution guidelines, coding standards, branch naming conventions, and pull request process including review requirements
3. THE Alexandria_Service repository SHALL contain a LICENSE file with the Apache 2.0 license
4. THE Alexandria_Service repository SHALL contain a .gitignore file covering Java build artifacts (target/), IDE files (.idea/, .vscode/), OS files (.DS_Store, Thumbs.db), and environment secrets (.env)
5. THE Alexandria_Service repository SHALL contain a root docker-compose.yml file that starts PostgreSQL and the backend service for local development, where successful startup is verified by both containers reporting healthy status and the backend responding to a health-check endpoint within 60 seconds of running `docker compose up`

### Requirement 2: Foundation Database Schema

**User Story:** As a platform operator, I want users, teams, and roles persisted in the database, so that the system can manage identity and access control.

#### Acceptance Criteria

1. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL create a `users` table with columns: id (UUID PK), organization_id (UUID NOT NULL FK to organizations), external_subject (TEXT NOT NULL), email (TEXT NOT NULL), display_name (TEXT), role (TEXT NOT NULL), enabled (BOOLEAN NOT NULL DEFAULT TRUE), created_at (TIMESTAMPTZ NOT NULL DEFAULT NOW()), updated_at (TIMESTAMPTZ NOT NULL DEFAULT NOW())
2. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL create a unique constraint on (organization_id, external_subject) in the `users` table
3. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL create a `teams` table with columns: id (UUID PK), organization_id (UUID NOT NULL FK to organizations), name (TEXT NOT NULL), slug (TEXT NOT NULL), description (TEXT), created_by (UUID NOT NULL FK to users), created_at (TIMESTAMPTZ NOT NULL DEFAULT NOW()), updated_at (TIMESTAMPTZ NOT NULL DEFAULT NOW())
4. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL create a unique constraint on (organization_id, slug) in the `teams` table
5. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL create a `team_members` table with columns: id (UUID PK), team_id (UUID NOT NULL FK to teams), user_id (UUID NOT NULL FK to users), team_role (TEXT NOT NULL), joined_at (TIMESTAMPTZ NOT NULL DEFAULT NOW())
6. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL create a unique constraint on (team_id, user_id) in the `team_members` table
7. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL create indexes on the following foreign key columns: users(organization_id), teams(organization_id), teams(created_by), team_members(team_id), team_members(user_id)
8. WHEN Flyway_Migration V2 runs, THE Alexandria_Service SHALL apply RESTRICT delete behavior on all foreign key constraints so that deletion of a referenced organization, user, or team is rejected while dependent rows exist
9. THE Alexandria_Service SHALL enforce allowed values for users.role as one of: Org_Admin, Catalog_Admin, Reviewer, Team_Owner, Skill_Author, Skill_Consumer, Read_Only_Auditor; and for team_members.team_role as one of: owner, member — enforced at the JPA entity layer via validation
10. THE Alexandria_Service SHALL validate the database schema against JPA entity mappings on startup using Hibernate validate mode

### Requirement 3: OIDC Authentication

**User Story:** As a developer in my organization, I want to log in with my corporate identity provider, so that I do not need a separate account for Alexandria.

#### Acceptance Criteria

1. THE OIDC_Resource_Server SHALL validate JWT access tokens by verifying the token signature, expiration, issuer claim against the OIDC_ISSUER_URI environment variable, and audience claim against the configured client identifier
2. WHEN a request contains a valid JWT access token, THE OIDC_Resource_Server SHALL extract the subject claim and make it available as the authenticated principal to downstream components
3. WHEN a request contains an expired or invalid JWT access token, THE OIDC_Resource_Server SHALL reject the request with HTTP 401 Unauthorized and a response body containing an error message indicating the reason for rejection
4. WHEN a request does not contain an Authorization header, THE OIDC_Resource_Server SHALL reject the request with HTTP 401 Unauthorized for all endpoints except those explicitly permitted for unauthenticated access
5. THE OIDC_Resource_Server SHALL allow unauthenticated access to health and info actuator endpoints
6. IF the OIDC issuer URI is unreachable or returns an invalid discovery document during initial application startup and no cached discovery document exists, THEN THE OIDC_Resource_Server SHALL fail to start and log an error message indicating the connection failure
7. IF the OIDC issuer URI is temporarily unreachable during a subsequent application startup and a previously cached discovery document exists, THEN THE OIDC_Resource_Server SHALL start using the cached discovery document and log a warning indicating that a stale discovery document is in use

### Requirement 4: User Auto-Provisioning

**User Story:** As a developer authenticating for the first time, I want my user account to be created automatically, so that I can start using Alexandria without manual setup.

#### Acceptance Criteria

1. WHEN an authenticated request arrives with a subject claim not present in the `users` table for the configured organization, THE Alexandria_Service SHALL create a new user record with the subject, email from the token claims, and default role Skill_Consumer, ensuring that concurrent requests for the same subject result in exactly one user record
2. WHEN an authenticated request arrives with a subject claim already present in the `users` table, THE Alexandria_Service SHALL use the existing user record for authorization
3. WHEN auto-provisioning a user, THE Alexandria_Service SHALL record an audit event with action "user.auto_provisioned", target_type "user", target_id set to the new user's identifier, and metadata containing the user's email and subject claim
4. IF the JWT token does not contain an email claim or the email claim is not a valid email address, THEN THE Alexandria_Service SHALL reject the request with HTTP 403 Forbidden and an error message indicating the missing or invalid email claim
5. IF user auto-provisioning fails due to a database or system error, THEN THE Alexandria_Service SHALL reject the request with HTTP 500 and an error message indicating that account creation failed, without persisting a partial user record
6. WHEN auto-provisioning fails due to a system error, THE Audit_Service SHALL record an audit event with action "user.auto_provision_failed", target_type "user", and metadata containing the error reason and the subject claim from the token
7. THE Alexandria_Service SHALL validate the email claim format on every authenticated request, rejecting requests with HTTP 403 Forbidden and an error message indicating an invalid email claim when the token email claim is missing or not a valid email address, regardless of whether the user has already been provisioned

### Requirement 5: Team Management

**User Story:** As an organization admin, I want to create and manage teams, so that I can organize users and scope skill visibility.

#### Acceptance Criteria

1. WHEN an Org_Admin or Catalog_Admin sends a POST request to /api/teams with a name between 2 and 100 characters containing only letters, numbers, spaces, hyphens, and underscores, THE Alexandria_Service SHALL create a new team and return HTTP 201 with the team resource including id, name, slug, and creation timestamp
2. WHEN any authenticated user sends a GET request to /api/teams, THE Alexandria_Service SHALL return a paginated list of teams in the user's organization with a default page size of 20 and a maximum page size of 100
3. WHEN an Org_Admin sends a GET request to /api/teams/{teamId}, THE Alexandria_Service SHALL return the team details including id, name, slug, creation timestamp, and member list with each member's user ID and team role
4. WHEN an Org_Admin or Team_Owner sends a POST request to /api/teams/{teamId}/members with a valid user ID and a team role of either "owner" or "member", THE Alexandria_Service SHALL add the user to the team and return HTTP 201
5. WHEN an Org_Admin or Team_Owner sends a DELETE request to /api/teams/{teamId}/members/{userId}, THE Alexandria_Service SHALL remove the user from the team and return HTTP 204
6. WHEN a user without Org_Admin or Catalog_Admin role attempts to create a team, THE RBAC_Filter SHALL reject the request with HTTP 403 Forbidden
7. WHEN a team is created, THE Audit_Service SHALL record an audit event with action "team.created"
8. WHEN a member is added to or removed from a team, THE Audit_Service SHALL record an audit event with action "team_member.added" or "team_member.removed"
9. THE Alexandria_Service SHALL generate a slug from the team name by lowercasing, replacing spaces and underscores with hyphens, stripping characters not in [a-z0-9-], and collapsing consecutive hyphens into one
10. IF a POST request to /api/teams contains a name that produces a slug already used by another team in the same organization, THEN THE Alexandria_Service SHALL reject the request with HTTP 409 Conflict and an error message indicating the team name is already taken
11. IF a POST request to /api/teams/{teamId}/members references a user ID that does not exist in the organization or a teamId that does not exist, THEN THE Alexandria_Service SHALL return HTTP 404 with an error message indicating the resource was not found
12. IF an Org_Admin or Team_Owner attempts to remove the last member with the "owner" role from a team, THEN THE Alexandria_Service SHALL reject the request with HTTP 409 Conflict and an error message indicating a team must have at least one owner

### Requirement 6: Role-Based Access Control

**User Story:** As a platform operator, I want role-based permissions enforced on all endpoints, so that users can only perform actions appropriate to their role.

#### Acceptance Criteria

1. THE RBAC_Filter SHALL enforce permissions based on the authenticated user's organization-level role for every endpoint except health check and public info endpoints
2. THE Alexandria_Service SHALL support the following roles in ascending privilege order: Read_Only_Auditor, Skill_Consumer, Skill_Author, Reviewer, Team_Owner, Catalog_Admin, Org_Admin
3. THE Alexandria_Service SHALL assign exactly one organization-level role per user
4. WHEN a user with Org_Admin role makes a request, THE RBAC_Filter SHALL grant access to all operations including user management, team management, role assignment, taxonomy management, approval policy configuration, and audit log viewing
5. IF a user with Read_Only_Auditor role makes a request to any endpoint that creates, updates, or deletes a resource, THEN THE RBAC_Filter SHALL reject the request with HTTP 403 Forbidden
6. WHEN an Org_Admin sends a PATCH request to /api/users/{userId}/role with a role value matching one of the supported roles, THE Alexandria_Service SHALL update the user's role and return HTTP 200
7. IF a role change request specifies a role value not in the supported roles list, THEN THE Alexandria_Service SHALL reject the request with HTTP 400 and return an error message indicating the invalid role value
8. IF a role change request targets a user that does not exist within the organization, THEN THE Alexandria_Service SHALL return HTTP 404
9. WHEN a user's role is changed, THE Audit_Service SHALL record an audit event with action "user.role_changed" including the actor, target user, previous role, and new role in metadata
10. THE RBAC_Filter SHALL scope all data queries to the authenticated user's organization_id
11. THE RBAC_Filter SHALL deny Skill_Consumer and Skill_Author roles access to user management, team creation, role assignment, taxonomy management, and audit log viewing endpoints, returning HTTP 403 Forbidden

### Requirement 7: Audit Logging Foundation

**User Story:** As a compliance officer, I want all administrative actions recorded, so that I can review who changed what and when.

#### Acceptance Criteria

1. THE Audit_Service SHALL record an audit event for every user provisioning, role change, team creation, team membership change, and team deletion
2. THE Audit_Service SHALL populate the organization_id, actor_user_id, action, target_type, target_id, metadata, and created_at timestamp fields for every audit event
3. WHEN an Org_Admin sends a GET request to /api/audit-events, THE Alexandria_Service SHALL return a paginated list of audit events filtered by the user's organization, sorted by created_at descending, with a default page size of 20 and a maximum page size of 100
4. WHEN an Org_Admin sends a GET request to /api/audit-events with optional query parameters target_type, target_id, actor_user_id, action, start_date, and end_date, THE Alexandria_Service SHALL return only audit events matching all provided filters combined with AND logic
5. IF a user without Org_Admin or Read_Only_Auditor role attempts to access /api/audit-events, THEN THE RBAC_Filter SHALL reject the request with HTTP 403 Forbidden
6. IF the Audit_Service fails to record an audit event due to a system error, THEN THE Alexandria_Service SHALL log the failure at ERROR level including the event details and allow the triggering operation to complete successfully

### Requirement 8: Health Check and Base API

**User Story:** As a platform operator deploying Alexandria, I want health and readiness endpoints, so that I can verify the application is running and connected to its dependencies.

#### Acceptance Criteria

1. IF the application is running and the PostgreSQL database connection responds to a validation query within 5 seconds, THEN THE Health_Endpoint SHALL return a JSON response with status "UP" and a 200 HTTP status code
2. IF the PostgreSQL database connection fails to respond to a validation query within 5 seconds, or the connection is refused or closed, THEN THE Health_Endpoint SHALL return a JSON response with status "DOWN" and a 503 HTTP status code
3. THE Health_Endpoint SHALL be accessible without authentication at /actuator/health and SHALL respond within 10 seconds
4. WHEN an authenticated user sends a GET request to /api/me, THE Alexandria_Service SHALL return a JSON response containing the current user's id, email, display_name, role, and organization_id
5. IF an authenticated user sends a GET request to /api/me and no matching user record exists in the database, THEN THE Alexandria_Service SHALL auto-provision the user per Requirement 4 and return the newly created user profile containing id, email, display_name, role, and organization_id
6. THE Alexandria_Service SHALL expose an /actuator/info endpoint without authentication that returns a JSON response containing the application name and version sourced from build metadata

### Requirement 9: Installation and Feature Documentation

**User Story:** As a platform operator or contributor, I want comprehensive documentation covering installation, configuration, and all available features, so that I can deploy and use Alexandria without reading source code.

#### Acceptance Criteria

1. THE Documentation_Site SHALL contain an installation guide with sections for prerequisites (Java version, Node.js version, Docker, Docker Compose, PostgreSQL), environment variable reference, OIDC provider configuration steps, and step-by-step deployment instructions for both local development (Docker Compose) and AWS production (Terraform)
2. THE Documentation_Site SHALL contain a feature guide describing each user-facing capability: authentication, user management, team management, role-based access control, audit logging, and health monitoring
3. THE Documentation_Site SHALL contain an API reference section documenting every REST endpoint with its HTTP method, path, request body schema, response body schema, required role, and example request/response pairs
4. WHEN a new feature or endpoint is added to the Alexandria_Service, THE Documentation_Site SHALL be updated to reflect the new capability before the feature is considered complete
5. THE Documentation_Site SHALL be generated from Markdown source files stored in a `docs/` directory at the repository root, buildable into static HTML pages using a documentation site generator
6. THE Documentation_Site SHALL contain a configuration reference listing every environment variable accepted by the Alexandria_Service with its name, description, required or optional status, default value where applicable, and example value
7. THE Documentation_Site SHALL contain a troubleshooting section covering common deployment failures including OIDC misconfiguration, database connection errors, and container health-check failures with resolution steps for each

### Requirement 10: Admin Frontend

**User Story:** As an organization administrator, I want a web-based admin console, so that I can manage users, teams, roles, taxonomy, and review audit logs without using the API directly.

#### Acceptance Criteria

1. THE Admin_Frontend SHALL be a React/TypeScript single-page application that communicates with the Alexandria_Service REST API at a configurable base URL defaulting to `http://localhost:8080` in development
2. WHEN an unauthenticated user navigates to the Admin_Frontend, THE OIDC_Login_Flow SHALL redirect the user to the configured OIDC identity provider for authentication and handle the callback by storing the access token for subsequent API requests
3. WHEN an authenticated user with Org_Admin or Catalog_Admin role accesses the Admin_Frontend, THE Admin_Frontend SHALL display a navigation layout with sections for Users, Teams, Roles, Taxonomy, and Audit Logs
4. IF an authenticated user without Org_Admin or Catalog_Admin role accesses the Admin_Frontend, THEN THE Admin_Frontend SHALL display a message indicating insufficient permissions and prevent access to administrative views
5. WHEN an Org_Admin navigates to the Users section, THE Admin_Frontend SHALL display a paginated table of users showing email, display name, role, enabled status, and creation date, with the ability to change a user's role via an inline control
6. WHEN an Org_Admin navigates to the Teams section, THE Admin_Frontend SHALL display a paginated list of teams with name, member count, and creation date, and provide controls to create a new team, view team members, add members, and remove members
7. WHEN an Org_Admin navigates to the Audit Logs section, THE Admin_Frontend SHALL display a paginated, reverse-chronological list of audit events with columns for timestamp, actor, action, target type, and target ID, and provide filters for action type, target type, actor, and date range
8. THE Admin_Frontend SHALL display API error responses as user-readable notification messages including the error reason returned by the Alexandria_Service
9. THE Admin_Frontend SHALL display a loading state indicator only while an asynchronous API request is in progress, and SHALL disable form submission controls during that time to prevent duplicate submissions
10. THE Admin_Frontend SHALL be buildable into static assets using a single `npm run build` command and servable from any static file host or CDN

### Requirement 11: Skill Browser Frontend

**User Story:** As a developer, I want a web-based catalog interface to browse, search, and view skills, so that I can discover reusable AI workflows without needing direct API access.

#### Acceptance Criteria

1. THE Skill_Browser_Frontend SHALL be a React/TypeScript single-page application that communicates with the Alexandria_Service REST API at a configurable base URL defaulting to `http://localhost:8080` in development
2. WHEN an unauthenticated user navigates to the Skill_Browser_Frontend, THE OIDC_Login_Flow SHALL redirect the user to the configured OIDC identity provider for authentication and handle the callback by storing the access token for subsequent API requests
3. WHEN an authenticated user accesses the Skill_Browser_Frontend, THE Skill_Browser_Frontend SHALL display a catalog page with a search input field, filter controls, and a paginated list of skill results showing name, description, owner team, lifecycle status, approval state, supported tools, risk level, and last updated date
4. WHEN a user enters a search query in the search input and submits, THE Skill_Browser_Frontend SHALL send the query to the Alexandria_Service search endpoint and display matching results ordered by relevance score
5. THE Skill_Browser_Frontend SHALL provide filter controls for task type, language, framework, tool, approval state, risk level, owner team, and visibility, applying selected filters as query parameters to the search API
6. WHEN a user selects a skill from the result list, THE Skill_Browser_Frontend SHALL navigate to a skill detail page displaying the skill overview, canonical instructions, tool-specific variants, applicability, examples, limitations, risk level, review status, version history, and feedback summary
7. WHEN a user views a skill detail page, THE Skill_Browser_Frontend SHALL display export and copy action buttons for each available format: Copy generic prompt, Copy for GitHub Copilot, Copy for Codex, Export as Markdown, Export as AGENTS.md fragment, Export as Cursor rule, and Export as Kiro steering fragment
8. WHEN a user clicks a copy or export button, THE Skill_Browser_Frontend SHALL call the Alexandria_Service export endpoint with the selected format and either copy the result to the clipboard with a success confirmation or trigger a file download for export actions
9. THE Skill_Browser_Frontend SHALL display a visual badge for each skill indicating its lifecycle status (Draft, Published, Team Approved, Company Approved, Deprecated, Archived, Blocked) using distinct colors or icons per status
10. THE Skill_Browser_Frontend SHALL be buildable into static assets using a single `npm run build` command and servable from any static file host or CDN
11. IF the Alexandria_Service API returns an error or is unreachable, THEN THE Skill_Browser_Frontend SHALL display a user-readable error message and provide a retry action where applicable
12. THE Skill_Browser_Frontend SHALL support direct navigation via URL to any page including search results with pre-applied filters and individual skill detail pages, preserving filter selections and search queries as URL parameters
