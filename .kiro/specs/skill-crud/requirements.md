# Requirements Document

## Introduction

This specification covers Milestone 2a of the Alexandria Governed Skill Registry — the core Skill CRUD operations. It builds on the existing foundation (OIDC auth, RBAC, teams, audit logging) and introduces the ability to create, edit, version, publish, and manage skills with tool-specific variants, visibility enforcement, and a controlled tag taxonomy. These operations form the backbone of the skill registry: once complete, Skill_Authors can author governed skills that are discoverable, versioned, and scoped by visibility rules.

## Glossary

- **Skill**: A reusable AI engineering workflow entity with structured metadata, owned by a Team, governed by lifecycle status and visibility rules
- **Skill_Version**: An immutable content snapshot of a Skill containing canonical instructions, applicability, limitations, when-to-use guidance, examples, and changelog
- **Skill_Variant**: Tool-specific instructions associated with a particular Skill_Version, targeting a specific AI coding tool
- **Lifecycle_Status**: The governance state of a Skill: Draft, Published, Team_Approved, Company_Approved, Deprecated, Archived, Blocked
- **Visibility**: The access scope of a Skill: private, team, organization, restricted
- **Risk_Level**: The assessed risk classification of a Skill: low, medium, high, restricted
- **Slug**: A URL-safe, unique identifier derived from the Skill name (lowercase, hyphen-separated)
- **Canonical_Instructions**: The primary Markdown content of a Skill_Version that describes the AI workflow in a tool-agnostic manner
- **Taxonomy**: A controlled vocabulary of tag values organized by type (task_type, tool, language, framework), managed by Catalog_Admins
- **Tag**: A metadata label from the controlled Taxonomy applied to a Skill for categorization and discoverability
- **Skill_Author**: A user with Skill_Author, Team_Owner, Catalog_Admin, or Org_Admin role permitted to create and edit Skills
- **Catalog_Admin**: A user with Catalog_Admin or Org_Admin role permitted to manage taxonomy and platform-wide governance settings
- **Skill_Service**: The backend service component handling Skill business logic
- **Skill_Repository**: The data access component for Skill persistence queries
- **Audit_Service**: The existing component responsible for recording governance-relevant actions as audit events

## Requirements

### Requirement 1: Skill Creation

**User Story:** As a Skill_Author, I want to create a new skill with a name, description, owner team, and canonical instructions, so that I can begin authoring a reusable AI workflow that my team can later publish.

#### Acceptance Criteria

1. WHEN a Skill_Author sends a POST request to /api/skills with a name (3–200 characters), description (1–2000 characters), owner team ID, and canonical instructions (Markdown, 1–100000 characters), THE Skill_Service SHALL create a new Skill in Draft Lifecycle_Status with an initial Skill_Version (version number 1) and return HTTP 201 with the created Skill resource
2. THE Skill_Service SHALL generate a unique slug for the Skill by lowercasing the name, replacing spaces and underscores with hyphens, stripping characters not in [a-z0-9-], and collapsing consecutive hyphens into one
3. IF the generated slug already exists for another Skill within the same organization, THEN THE Skill_Service SHALL append a numeric suffix (e.g., "-2", "-3") to produce a unique slug
4. THE Skill_Service SHALL validate that the owner team ID references a Team that exists within the authenticated user's organization, returning HTTP 404 if the team does not exist
5. THE Skill_Service SHALL validate that the authenticated user is a member of the specified owner Team or has Catalog_Admin or Org_Admin role, returning HTTP 403 if the user lacks ownership or administrative authority
6. WHEN a Skill is created, THE Audit_Service SHALL record an audit event with action "skill.created", target_type "skill", target_id set to the new Skill's ID, and metadata containing the skill name, slug, owner team ID, and author user ID
7. THE Skill_Service SHALL store organization_id on the Skill record matching the authenticated user's organization
8. IF the request body is missing required fields (name, description, owner team ID, or canonical instructions) or any field violates length constraints, THEN THE Skill_Service SHALL return HTTP 400 with field-level validation error messages
9. THE Skill_Service SHALL set the Skill's default Visibility to "private" upon creation
10. THE Skill_Service SHALL set the initial Skill_Version's changelog to "Initial draft"

### Requirement 2: Skill Retrieval

**User Story:** As a developer, I want to retrieve skill details by ID or slug, so that I can view the full content of a skill including its current version, metadata, and variants.

#### Acceptance Criteria

1. WHEN an authenticated user sends a GET request to /api/skills/{skillId}, THE Skill_Service SHALL return the Skill with its current Skill_Version content including: id, name, slug, description, owner team, lifecycle status, visibility, risk level, canonical instructions, applicability, limitations, when-to-use, examples, version number, tags, supported tools, variants, created_at, and updated_at
2. WHEN an authenticated user sends a GET request to /api/skills?slug={slug}, THE Skill_Service SHALL return the Skill matching the provided slug within the user's organization
3. WHEN an authenticated user sends a GET request to /api/skills with optional query parameters (page, size, status, visibility, teamId, search), THE Skill_Service SHALL return a paginated list of Skills the user is authorized to view, with a default page size of 20 and maximum page size of 100
4. THE Skill_Service SHALL enforce Visibility rules on retrieval: a user can view a Skill only if the Visibility permits access per Requirement 5
5. IF a Skill is not found or the user is not authorized to view it, THEN THE Skill_Service SHALL return HTTP 404
6. WHEN an authenticated user sends a GET request to /api/skills/{skillId}/versions, THE Skill_Service SHALL return a paginated list of all Skill_Versions for that Skill in descending version number order
7. WHEN an authenticated user sends a GET request to /api/skills/{skillId}/versions/{versionNumber}, THE Skill_Service SHALL return the specific Skill_Version content

### Requirement 3: Skill Editing and Versioning

**User Story:** As a Skill_Author, I want to edit a skill's content, so that I can improve instructions and metadata while maintaining version history for published content.

#### Acceptance Criteria

1. WHEN a Skill_Author sends a PUT request to /api/skills/{skillId} for a Skill in Draft status, THE Skill_Service SHALL update the current Skill_Version in-place with the provided fields (name, description, canonical instructions, applicability, limitations, when-to-use, examples) without creating a new version
2. WHEN a Skill_Author sends a PUT request to /api/skills/{skillId} for a Skill with Lifecycle_Status Published, Team_Approved, or Company_Approved, THE Skill_Service SHALL create a new Skill_Version with an incremented version number containing the provided content, set the Skill's Lifecycle_Status back to Draft, and update the Skill's current version reference to the new version
3. THE Skill_Service SHALL store each Skill_Version as an immutable record; once a Skill transitions away from Draft, the Skill_Version associated with that state SHALL NOT be modified
4. THE Skill_Service SHALL require a changelog entry (1–2000 characters) when creating a new Skill_Version for a Skill whose current Lifecycle_Status is not Draft (i.e., Published, Team_Approved, or Company_Approved); changelog is optional when the Skill is currently in Draft status
5. THE Skill_Service SHALL validate that the authenticated user is a member of the Skill's owner Team or has Catalog_Admin or Org_Admin role, returning HTTP 403 if the user lacks edit authority
6. IF a Skill has Lifecycle_Status Deprecated, Archived, or Blocked, THEN THE Skill_Service SHALL reject edit requests with HTTP 409 Conflict and a message indicating the Skill cannot be edited in its current state
7. WHEN a new Skill_Version is created, THE Audit_Service SHALL record an audit event with action "skill_version.created", target_type "skill_version", and metadata containing the skill ID, new version number, and changelog summary
8. WHEN a Draft Skill_Version is updated in-place, THE Audit_Service SHALL record an audit event with action "skill.updated", target_type "skill", and metadata containing the modified fields
9. THE Skill_Service SHALL support partial updates: only fields included in the request body are updated; omitted fields retain their current values
10. THE Skill_Service SHALL validate that the canonical instructions field contains valid Markdown content (non-empty string, 1–100000 characters)

### Requirement 4: Skill Publishing

**User Story:** As a Skill_Author, I want to publish a skill so that it becomes discoverable to other users according to its visibility settings.

#### Acceptance Criteria

1. WHEN a Skill_Author sends a POST request to /api/skills/{skillId}/publish, THE Skill_Service SHALL validate that the Skill's current version has all required metadata: at least one task_type tag, visibility set, at least one supported tool, risk_level set, applicability (non-empty), and at least one example
2. IF a Skill is missing required metadata for publishing, THEN THE Skill_Service SHALL return HTTP 422 Unprocessable Entity with a response body listing each missing or invalid field
3. WHEN all publish validations pass, THE Skill_Service SHALL transition the Skill's Lifecycle_Status from Draft to Published and set the published_at timestamp on the current Skill_Version
4. IF a Skill's Lifecycle_Status is not Draft when a publish request is received, THEN THE Skill_Service SHALL return HTTP 409 Conflict with a message indicating that only Draft skills can be published
5. WHEN a Skill is published, THE Audit_Service SHALL record an audit event with action "skill.published", target_type "skill", and metadata containing the skill ID, version number, and publishing user ID
6. THE Skill_Service SHALL validate that the authenticated user is a member of the Skill's owner Team or has Catalog_Admin or Org_Admin role, returning HTTP 403 if the user lacks publish authority
7. WHEN a Skill is published, THE Skill_Service SHALL make it discoverable in list and search queries according to its Visibility setting

### Requirement 5: Skill Visibility Enforcement

**User Story:** As a developer, I want skills to respect visibility rules, so that private or team-scoped skills are not exposed to unauthorized users.

#### Acceptance Criteria

1. WHILE a Skill has Visibility set to "private", THE Skill_Service SHALL restrict access to the Skill_Author who created it and users with Org_Admin role
2. WHILE a Skill has Visibility set to "team", THE Skill_Service SHALL restrict access to members of the Skill's owning Team and users with Org_Admin role
3. WHILE a Skill has Visibility set to "organization", THE Skill_Service SHALL allow access to all authenticated users within the same organization
4. WHILE a Skill has Visibility set to "restricted", THE Skill_Service SHALL limit access to users explicitly listed in the Skill's access grants and users with Org_Admin role
5. THE Skill_Repository SHALL enforce Visibility rules at the query layer: list and search queries SHALL include visibility predicates so that unauthorized Skills are never loaded from the database
6. WHEN a Skill_Author sends a PATCH request to /api/skills/{skillId}/visibility with a new visibility value, THE Skill_Service SHALL update the Skill's visibility and record an audit event with action "skill.visibility_changed"
7. THE Skill_Service SHALL validate that the visibility value is one of: private, team, organization, restricted — returning HTTP 400 for invalid values
8. THE Skill_Service SHALL validate that only users with edit authority (owner Team members, Catalog_Admin, Org_Admin) can change a Skill's visibility, returning HTTP 403 exclusively for authorization failures; validation errors for invalid visibility values SHALL use HTTP 400
9. WHILE a Skill has Visibility set to "private" or "team", THE Skill_Service SHALL exclude the Skill's name, description, and metadata from responses to unauthorized users — no information leakage through error messages or list endpoints

### Requirement 6: Skill Variants

**User Story:** As a Skill_Author, I want to create tool-specific variants of a skill version, so that consumers get optimized instructions for their preferred AI coding tool.

#### Acceptance Criteria

1. WHEN a Skill_Author sends a POST request to /api/skills/{skillId}/versions/{versionNumber}/variants with a tool identifier and tool-specific instructions, THE Skill_Service SHALL create a Skill_Variant associated with the specified Skill_Version
2. THE Skill_Service SHALL support the following tool identifiers for Variants: generic, github_copilot, codex, cursor, aws_kiro, sourcegraph_cody, claude_code, jetbrains_ai, internal_agent
3. IF a request specifies a tool identifier not in the supported list, THEN THE Skill_Service SHALL return HTTP 400 with an error message listing the valid tool identifiers
4. THE Skill_Service SHALL enforce a unique constraint of one Variant per tool per Skill_Version — returning HTTP 409 Conflict if a Variant for the specified tool already exists on that version
5. WHEN a consumer requests a Skill_Version's content for a tool that has a specific Variant, THE Skill_Service SHALL return the Variant's tool-specific instructions
6. WHEN a consumer requests a Skill_Version's content for a tool that has no specific Variant, THE Skill_Service SHALL fall back to the canonical instructions from the Skill_Version
7. WHEN a Skill_Author sends a PUT request to /api/skills/{skillId}/versions/{versionNumber}/variants/{tool}, THE Skill_Service SHALL update the existing Variant's instructions for that tool
8. WHEN a Skill_Author sends a DELETE request to /api/skills/{skillId}/versions/{versionNumber}/variants/{tool} and the Variant exists, THE Skill_Service SHALL remove the Variant and return HTTP 204; IF no Variant exists for that tool on the specified version, THE Skill_Service SHALL return HTTP 404
9. THE Skill_Service SHALL validate that the authenticated user has edit authority over the Skill (owner Team member, Catalog_Admin, or Org_Admin), returning HTTP 403 otherwise
10. THE Skill_Service SHALL validate that Variant instructions are non-empty Markdown content (1–100000 characters), returning HTTP 400 for invalid content
11. WHEN a Variant is created, updated, or deleted, THE Audit_Service SHALL record an audit event with action "skill_variant.created", "skill_variant.updated", or "skill_variant.deleted" with metadata containing the skill ID, version number, and tool identifier; IF the Audit_Service fails to record the event, THE Skill_Service SHALL allow the variant operation to complete successfully and log the audit failure at ERROR level

### Requirement 7: Skill Tags and Taxonomy

**User Story:** As a Skill_Author, I want to tag skills with structured metadata from a controlled taxonomy, so that consumers can filter and discover skills by task type, tool, language, and framework.

#### Acceptance Criteria

1. THE Skill_Service SHALL support a controlled taxonomy with four tag types: task_type, tool, language, and framework
2. WHEN a Skill_Author assigns tags to a Skill via PUT /api/skills/{skillId} (tags field), THE Skill_Service SHALL validate that each tag value belongs to an active entry in the configured taxonomy for its tag type, returning HTTP 422 with details of invalid tags if validation fails
3. WHEN a Catalog_Admin sends a POST request to /api/taxonomy/{tagType} with a value and optional description, THE Skill_Service SHALL create a new taxonomy entry and return HTTP 201
4. WHEN a Catalog_Admin sends a PUT request to /api/taxonomy/{tagType}/{tagId}, THE Skill_Service SHALL update the taxonomy entry's value or description
5. WHEN a Catalog_Admin sends a PATCH request to /api/taxonomy/{tagType}/{tagId}/deactivate, THE Skill_Service SHALL mark the taxonomy entry as inactive — inactive entries remain on existing Skills but cannot be assigned to new Skills
6. WHEN an authenticated user sends a GET request to /api/taxonomy, THE Skill_Service SHALL return all taxonomy entries grouped by tag type, indicating which entries are active and which are deactivated
7. WHEN an authenticated user sends a GET request to /api/taxonomy/{tagType}, THE Skill_Service SHALL return all entries for the specified tag type with active/inactive status
8. IF a user without Catalog_Admin or Org_Admin role attempts to create, edit, or deactivate taxonomy entries, THEN THE Skill_Service SHALL return HTTP 403 Forbidden
9. THE Skill_Service SHALL include tag values in the full-text search index so that searching for a tag value (e.g., "python" or "code-review") returns Skills with matching tags
10. THE Skill_Service SHALL validate that taxonomy entry values are unique within their tag type (case-insensitive), returning HTTP 409 Conflict for duplicates
11. WHEN taxonomy entries are created, updated, or deactivated, THE Audit_Service SHALL record an audit event with action "taxonomy.created", "taxonomy.updated", or "taxonomy.deactivated" with metadata containing the tag type and value
12. THE Skill_Service SHALL prevent deletion of taxonomy entries that are in use by existing Skills — only deactivation is permitted

### Requirement 8: Skill Deletion

**User Story:** As a Skill_Author, I want to delete a draft skill that I no longer need, so that unused drafts do not clutter the registry.

#### Acceptance Criteria

1. WHEN a Skill_Author sends a DELETE request to /api/skills/{skillId} for a Skill in Draft status, THE Skill_Service SHALL permanently remove the Skill and its associated Skill_Versions and Variants, returning HTTP 204
2. IF a DELETE request targets a Skill with Lifecycle_Status other than Draft, THEN THE Skill_Service SHALL return HTTP 409 Conflict with a message indicating that only Draft skills can be deleted — published skills should be archived instead
3. THE Skill_Service SHALL validate that the authenticated user has edit authority (owner Team member, Catalog_Admin, or Org_Admin), returning HTTP 403 otherwise
4. WHEN a Skill is deleted, THE Audit_Service SHALL record an audit event with action "skill.deleted", target_type "skill", and metadata containing the skill name and owner team ID; IF the Audit_Service fails to record the event, THE Skill_Service SHALL allow the deletion to complete successfully and log the audit failure at ERROR level

