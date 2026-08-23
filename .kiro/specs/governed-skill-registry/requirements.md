# Requirements Document

## Introduction

Alexandria Phase 1 is a Governed Skill Registry — an open-source, self-hosted platform that enterprises deploy on their own AWS infrastructure. Teams can create, version, approve, search, and manually reuse AI engineering skills. Skills are structured, reusable AI coding workflows that work across tools (GitHub Copilot, Codex, Cursor, AWS Kiro, Claude Code, Sourcegraph Cody). The platform is designed for single-organization deployment, keeping all data within the enterprise's own cloud boundary to meet security and confidentiality requirements.

## Glossary

- **Skill**: A reusable AI engineering workflow with structured metadata, versioned content, and governance state
- **Skill_Version**: An immutable content snapshot of a Skill containing canonical instructions, applicability, examples, and limitations
- **Skill_Variant**: Tool-specific instructions for a particular Skill_Version targeting a specific AI coding tool
- **Registry**: The Alexandria platform instance deployed within an enterprise's AWS account
- **Organization**: The single enterprise entity that owns the deployed Registry instance
- **Team**: A group of users within the Organization that can own Skills
- **Skill_Author**: A user with permission to create and edit Skills
- **Skill_Consumer**: A user who searches for, views, and exports Skills
- **Reviewer**: A user with permission to approve, reject, or request changes on Skills
- **Catalog_Admin**: A user with permission to manage taxonomy, approval policies, and visibility defaults
- **Org_Admin**: A user with full administrative control over the Registry instance
- **Lifecycle_Status**: The governance state of a Skill (Draft, Published, Team_Approved, Company_Approved, Deprecated, Archived, Blocked)
- **Visibility**: The access scope of a Skill (private, team, organization, restricted)
- **Risk_Level**: The assessed risk classification of a Skill (low, medium, high, restricted)
- **Export_Format**: A target output format for copying or downloading a Skill (Generic Markdown, GitHub Copilot, Codex, AGENTS.md, Cursor Rule, AWS Kiro Steering)
- **Review_Request**: A formal request for a Reviewer to evaluate a Skill_Version
- **Feedback**: A structured user signal about Skill quality (worked_well, not_relevant, outdated, unclear, wrong_framework, unsafe, needs_improvement)
- **Audit_Event**: An immutable record of a governance action performed within the Registry
- **Search_Document**: A PostgreSQL full-text search index entry derived from Skill content and metadata
- **RBAC**: Role-Based Access Control governing what actions users can perform
- **Deployer**: The enterprise operations team responsible for deploying and maintaining the Registry instance on their AWS infrastructure

## Requirements

### Requirement 1: Authentication and Session Management

**User Story:** As a developer, I want to log in to the Registry using my organization's identity provider, so that I can access skills without managing a separate set of credentials.

#### Acceptance Criteria

1. WHEN a user navigates to the Registry, THE Registry SHALL redirect unauthenticated users to the configured OIDC identity provider for authentication
2. WHEN a user completes OIDC authentication, THE Registry SHALL create a session and associate the user with the Organization
3. WHEN a user's session expires, THE Registry SHALL require re-authentication before granting access to protected resources
4. IF an OIDC token validation fails, THEN THE Registry SHALL deny access and return a 401 Unauthorized response
5. THE Registry SHALL allow Deployers to configure any OIDC-compliant identity provider (including Okta, Microsoft Entra ID, Google Workspace, and OneLogin) through environment variables or configuration files

### Requirement 2: Self-Hosted Deployment

**User Story:** As a Deployer, I want to deploy the Registry on my organization's AWS infrastructure, so that all skill data remains within our security boundary.

#### Acceptance Criteria

1. THE Registry SHALL be deployable as container images (backend and frontend) on AWS ECS Fargate or Kubernetes
2. THE Registry SHALL store all persistent data in a Deployer-provisioned PostgreSQL database (RDS or self-managed)
3. THE Registry SHALL store file attachments in a Deployer-provisioned S3 bucket within the enterprise's AWS account
4. THE Registry SHALL be configurable through environment variables and configuration files without modifying source code
5. THE Registry SHALL provide Infrastructure-as-Code templates (Terraform) for standard AWS deployment
6. THE Registry SHALL operate as a single-organization instance with no multi-tenant data sharing

### Requirement 3: Team Management

**User Story:** As an Org_Admin, I want to create and manage teams, so that skills can have clear ownership and team-scoped visibility.

#### Acceptance Criteria

1. WHEN an Org_Admin creates a Team, THE Registry SHALL store the Team within the Organization
2. WHEN an Org_Admin assigns users to a Team, THE Registry SHALL grant those users Team-level visibility for Skills owned by that Team
3. THE Registry SHALL allow a user to belong to multiple Teams
4. WHEN a Team is deleted, THE Registry SHALL require reassignment of Skills previously owned by that Team before completing deletion

### Requirement 4: Role-Based Access Control

**User Story:** As an Org_Admin, I want to assign roles to users, so that I can control who can author, review, approve, and administer skills.

#### Acceptance Criteria

1. THE Registry SHALL support the following roles: Org_Admin, Catalog_Admin, Reviewer, Team_Owner, Skill_Author, Skill_Consumer, Read-Only Auditor
2. WHEN a user has the Skill_Author role, THE Registry SHALL allow that user to create, edit, and publish Skills
3. WHEN a user has the Reviewer role, THE Registry SHALL allow that user to approve, reject, and request changes on Skills
4. WHEN a user has the Catalog_Admin role, THE Registry SHALL allow that user to manage taxonomy, approval policies, and visibility defaults
5. IF a user without the required role attempts a restricted action, THEN THE Registry SHALL deny the action and return a 403 Forbidden response
6. THE Registry SHALL enforce permissions at both the application layer and the database query layer

### Requirement 5: Skill Creation

**User Story:** As a Skill_Author, I want to create a new skill with structured metadata, so that other developers can discover and reuse my AI workflow.

#### Acceptance Criteria

1. WHEN a Skill_Author submits a create skill request with a name, description, owner Team, and canonical instructions, THE Registry SHALL create a new Skill in Draft status
2. THE Registry SHALL generate a unique slug for the Skill based on the name
3. THE Registry SHALL require name, description, owner Team reference, and canonical instructions to create a Draft Skill
4. WHEN a Skill is created, THE Registry SHALL record an Audit_Event with the actor, action, target Skill, and timestamp
5. THE Registry SHALL validate that the Skill name is unique within the Registry

### Requirement 6: Skill Editing and Versioning

**User Story:** As a Skill_Author, I want to edit skills and create new versions, so that I can improve and evolve skills over time without losing history.

#### Acceptance Criteria

1. WHEN a Skill_Author edits a published Skill, THE Registry SHALL create a new Skill_Version with an incremented version number
2. THE Registry SHALL store each Skill_Version as an immutable record containing canonical instructions, applicability, limitations, when-to-use guidance, examples, and changelog
3. THE Registry SHALL maintain a reference from the Skill to its current (latest published) Skill_Version
4. WHEN a Skill_Author edits a Skill in Draft status, THE Registry SHALL allow updates to the current Skill_Version without creating a new version
5. THE Registry SHALL provide a Markdown editor for authoring canonical instructions

### Requirement 7: Skill Publishing

**User Story:** As a Skill_Author, I want to publish a skill, so that it becomes discoverable to other users according to its visibility settings.

#### Acceptance Criteria

1. WHEN a Skill_Author publishes a Skill, THE Registry SHALL validate that the Skill has: task type, visibility, supported tools, risk level, applicability, and at least one example or usage rationale
2. IF a Skill is missing required metadata for publishing, THEN THE Registry SHALL reject the publish request and list the missing fields
3. WHEN a Skill is published, THE Registry SHALL change its Lifecycle_Status from Draft to Published
4. WHEN a Skill is published, THE Registry SHALL make it discoverable in search results according to its Visibility setting
5. WHEN a Skill is published, THE Registry SHALL record an Audit_Event for the publish action

### Requirement 8: Skill Visibility Enforcement

**User Story:** As a developer, I want skills to respect visibility rules, so that private or team-scoped skills are not exposed to unauthorized users.

#### Acceptance Criteria

1. WHILE a Skill has Visibility set to private, THE Registry SHALL restrict access to the Skill_Author and Org_Admins only
2. WHILE a Skill has Visibility set to team, THE Registry SHALL restrict access to members of the owning Team and Org_Admins
3. WHILE a Skill has Visibility set to organization, THE Registry SHALL allow access to all authenticated users
4. WHILE a Skill has Visibility set to restricted, THE Registry SHALL limit access to explicitly granted users and roles
5. THE Registry SHALL enforce Visibility rules in search queries so that unauthorized Skills are excluded from results

### Requirement 9: Full-Text Search

**User Story:** As a Skill_Consumer, I want to search for skills using natural language queries, so that I can find relevant AI workflows for my current task.

#### Acceptance Criteria

1. WHEN a user submits a search query, THE Registry SHALL perform full-text search over Skill name, description, canonical instructions, when-to-use, limitations, examples, tags, and owner Team name
2. THE Registry SHALL use PostgreSQL full-text search with tsvector indexing for search execution
3. WHEN search results are returned, THE Registry SHALL apply a deterministic ranking score that includes text match relevance, approval boost, usage boost, freshness boost, and tag match boost
4. THE Registry SHALL exclude Blocked Skills from all search results
5. THE Registry SHALL exclude Archived Skills from search results by default
6. WHEN a search is executed, THE Registry SHALL enforce Visibility rules before returning results

### Requirement 10: Search Filters

**User Story:** As a Skill_Consumer, I want to filter search results by various attributes, so that I can narrow results to skills relevant to my context.

#### Acceptance Criteria

1. THE Registry SHALL support filtering search results by: task type, language, framework, tool, approval state, risk level, owner Team, Visibility, and freshness
2. WHEN a user applies multiple filters, THE Registry SHALL combine filters using AND logic
3. WHEN filters are applied with a search query, THE Registry SHALL apply filters before ranking results
4. THE Registry SHALL return filter counts so users can see how many Skills match each filter value

### Requirement 11: Skill Detail View

**User Story:** As a Skill_Consumer, I want to view a skill's full details, so that I can evaluate whether it suits my needs before reusing it.

#### Acceptance Criteria

1. WHEN a user requests a Skill detail, THE Registry SHALL return: name, description, owner Team, lifecycle status, approval state, visibility, risk level, canonical instructions, applicability, limitations, examples, supported tools, version history, feedback summary, and last updated timestamp
2. WHEN a user views a Skill, THE Registry SHALL record a view usage event
3. THE Registry SHALL display Lifecycle_Status and approval state as visible badges on the Skill detail view

### Requirement 12: Skill Variants

**User Story:** As a Skill_Author, I want to create tool-specific variants of a skill, so that consumers can get optimized instructions for their preferred AI coding tool.

#### Acceptance Criteria

1. WHEN a Skill_Author creates a Variant for a Skill_Version, THE Registry SHALL store tool-specific instructions associated with the specified tool identifier
2. THE Registry SHALL support Variants for the following tools: generic, github_copilot, codex, cursor, aws_kiro, sourcegraph_cody, claude_code, jetbrains_ai, internal_agent
3. THE Registry SHALL enforce a unique constraint of one Variant per tool per Skill_Version
4. WHEN a Variant is requested for a tool that has no specific Variant, THE Registry SHALL fall back to the canonical instructions

### Requirement 13: Skill Export

**User Story:** As a Skill_Consumer, I want to export a skill in a format compatible with my AI coding tool, so that I can use the skill in my daily workflow.

#### Acceptance Criteria

1. WHEN a user requests an export, THE Registry SHALL render the Skill_Version content in the requested Export_Format
2. THE Registry SHALL support the following Export_Formats: Generic Markdown, GitHub Copilot prompt, Codex instructions, AGENTS.md fragment, Cursor Rule, AWS Kiro steering/spec fragment
3. WHEN an export is generated, THE Registry SHALL record a usage event capturing the Skill, version, user, export format, and timestamp
4. WHEN a user clicks a copy button, THE Registry SHALL place the rendered content on the user's clipboard and record a copy usage event
5. IF a Skill is in Blocked status, THEN THE Registry SHALL deny export requests for that Skill

### Requirement 14: Review Workflow

**User Story:** As a Reviewer, I want to review submitted skills and provide approval decisions, so that the organization can govern skill quality.

#### Acceptance Criteria

1. WHEN a Skill_Author requests a review, THE Registry SHALL create a Review_Request associated with the Skill, the specific Skill_Version, and the requesting user
2. WHEN a Reviewer evaluates a Skill, THE Registry SHALL allow the Reviewer to: approve, reject, or request changes, with an optional comment
3. WHEN a Reviewer approves a Skill at team level, THE Registry SHALL transition the Skill Lifecycle_Status to Team_Approved
4. WHEN a Reviewer approves a Skill at company level, THE Registry SHALL transition the Skill Lifecycle_Status to Company_Approved
5. WHEN a review decision is made, THE Registry SHALL record an Audit_Event with the Reviewer, decision, Skill, version, and timestamp
6. THE Registry SHALL display approval state as a badge on Skills in search results and detail views

### Requirement 15: Skill Deprecation and Blocking

**User Story:** As a Catalog_Admin, I want to deprecate or block skills, so that consumers are warned about outdated skills and prevented from using unsafe ones.

#### Acceptance Criteria

1. WHEN a Catalog_Admin marks a Skill as Deprecated, THE Registry SHALL change its Lifecycle_Status to Deprecated and display a deprecation indicator in search results and detail views
2. WHILE a Skill has Lifecycle_Status Deprecated, THE Registry SHALL allow viewing and exporting but display a warning to users
3. WHEN a Catalog_Admin marks a Skill as Blocked, THE Registry SHALL change its Lifecycle_Status to Blocked
4. WHILE a Skill has Lifecycle_Status Blocked, THE Registry SHALL deny export and copy operations for that Skill
5. WHEN a Skill is deprecated or blocked, THE Registry SHALL record an Audit_Event

### Requirement 16: Feedback Collection

**User Story:** As a Skill_Consumer, I want to submit feedback on skills I've used, so that skill owners and the organization can improve skill quality.

#### Acceptance Criteria

1. WHEN a user submits feedback on a Skill, THE Registry SHALL store the feedback type, optional comment, user reference, Skill reference, Skill_Version reference, and timestamp
2. THE Registry SHALL support the following feedback types: worked_well, not_relevant, outdated, unclear, wrong_framework, unsafe, needs_improvement
3. THE Registry SHALL make feedback visible to the Skill's owning Team and Org_Admins
4. WHEN feedback is submitted, THE Registry SHALL record the event for analytics aggregation

### Requirement 17: Usage Event Tracking

**User Story:** As a Catalog_Admin, I want the platform to track skill usage events, so that I can understand adoption and identify popular or stale skills.

#### Acceptance Criteria

1. WHEN a user views, copies, or exports a Skill, THE Registry SHALL record a usage event with: skill_id, skill_version_id, user_id, event_type, source, and timestamp
2. THE Registry SHALL aggregate usage events to calculate usage counts displayed on Skill search results and detail views
3. THE Registry SHALL use usage data as an input to the search ranking score (usage boost)
4. WHEN a Skill has not been viewed or exported within 90 days, THE Registry SHALL flag it as stale in admin dashboards

### Requirement 18: Audit Logging

**User Story:** As an Org_Admin, I want all governance actions to be recorded in an immutable audit log, so that I can review the history of decisions and changes for compliance.

#### Acceptance Criteria

1. THE Registry SHALL record an Audit_Event for: skill creation, skill editing, version creation, skill publishing, review request, approval granted, approval rejected, visibility change, owner change, skill deprecation, skill archival, skill blocking, export generation, and permission changes
2. THE Registry SHALL store each Audit_Event with: actor user_id, action, target type, target_id, metadata (JSONB), and timestamp
3. THE Registry SHALL make Audit_Events immutable — once created, an Audit_Event cannot be modified or deleted
4. WHEN an Org_Admin queries audit logs, THE Registry SHALL return events filterable by target type, action, actor, and time range

### Requirement 19: Basic Analytics Dashboards

**User Story:** As a Catalog_Admin, I want to view analytics dashboards, so that I can monitor catalog health, adoption, quality, and governance metrics.

#### Acceptance Criteria

1. THE Registry SHALL provide a catalog health dashboard showing: total skills, published skills, approved skills, deprecated skills, stale skills, and orphaned skills
2. THE Registry SHALL provide an adoption dashboard showing: active users, searches per week, skill views, copies, exports, and repeat users
3. THE Registry SHALL provide a quality dashboard showing: average rating, negative feedback rate, most reported skills, and review backlog count
4. THE Registry SHALL provide a governance dashboard showing: pending reviews, overdue reviews, high-risk skills, restricted skills, and recent audit events

### Requirement 20: Skill Tags and Taxonomy

**User Story:** As a Skill_Author, I want to tag skills with structured metadata, so that consumers can filter and discover skills by language, framework, task type, and tool.

#### Acceptance Criteria

1. THE Registry SHALL support a controlled taxonomy with the following tag types: task_type, tool, language, and framework
2. WHEN a Skill_Author assigns tags to a Skill, THE Registry SHALL validate that tag values belong to the configured taxonomy
3. WHEN a Catalog_Admin manages taxonomy, THE Registry SHALL allow adding, editing, and deactivating tag values
4. THE Registry SHALL include tag content in the full-text search index so that tag matches boost relevance

### Requirement 21: Content Security Scanning

**User Story:** As a Catalog_Admin, I want skill content to be scanned for sensitive material, so that secrets, credentials, and unsafe instructions are not published.

#### Acceptance Criteria

1. WHEN a Skill is created or updated, THE Registry SHALL scan the content for: secrets, private keys, tokens, passwords, credentials, dangerous shell commands, and unsafe AI instructions
2. IF content scanning detects sensitive material, THEN THE Registry SHALL flag the Skill and notify the author with a description of the detected issue
3. THE Registry SHALL sanitize rendered Markdown output to prevent script injection and unsafe HTML

### Requirement 22: Markdown Import

**User Story:** As a Skill_Author, I want to import existing skill content from Markdown files, so that I can onboard my team's existing AI workflows without re-typing them.

#### Acceptance Criteria

1. WHEN a user uploads a Markdown file, THE Registry SHALL parse the content and map it to Skill fields (name, description, instructions, applicability, limitations)
2. WHEN a user pastes text, THE Registry SHALL convert the pasted content into a new Skill Draft with extracted metadata
3. WHERE LLM-assisted metadata extraction is enabled, THE Registry SHALL use an LLM to suggest task type, tools, languages, frameworks, and risk level from imported content
4. IF parsing fails to extract required fields, THEN THE Registry SHALL create the Skill Draft with the raw content as canonical instructions and prompt the user to complete missing metadata

### Requirement 23: Admin Console

**User Story:** As an Org_Admin, I want an admin console, so that I can manage users, teams, roles, taxonomy, and policies for my organization.

#### Acceptance Criteria

1. THE Registry SHALL provide an admin interface for managing users, teams, and role assignments
2. THE Registry SHALL provide an admin interface for managing taxonomy (task types, tools, languages, frameworks)
3. THE Registry SHALL provide an admin interface for configuring approval policies and visibility defaults
4. THE Registry SHALL provide an admin interface for viewing audit logs with filtering and pagination
5. WHEN an Org_Admin makes changes through the admin console, THE Registry SHALL record corresponding Audit_Events
