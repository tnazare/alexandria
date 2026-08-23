# Alexandria Phase 1: Governed Skill Registry

## Phase 1 objective

Phase 1 builds the foundation of the product:

> An open-source, self-hosted registry where teams can create, version, approve, search, and manually reuse AI engineering skills — deployed entirely within the organization's AWS infrastructure.

The purpose is to validate that organizations want to treat AI coding workflows as reusable assets, and that an open-source self-hosted approach removes the adoption barriers that would slow down a SaaS product in this early market.

Phase 1 should prove:

1. teams have useful skills to contribute
2. developers want to search for skills
3. governance matters to organizations
4. copy/export is enough to create early value
5. the catalog can become the system of record for AI engineering workflows
6. self-hosted deployment is achievable for platform teams
7. the open-source community engages with the project

## Phase 1 scope

### In scope

- open-source repository with permissive license
- self-hosted deployment on AWS (Terraform + Docker Compose)
- container images published to public registry
- SSO login (OIDC, configurable provider)
- single-organization instance
- users, teams
- skill creation and editing
- skill versioning
- skill variants by tool
- skill examples
- tags and taxonomy
- lifecycle state
- visibility and permissions
- full-text search and filters
- copy/export
- review and approval workflow
- feedback collection
- basic usage events
- audit logs
- basic admin console

### Out of scope

- IDE extension
- CLI
- semantic search
- automatic recommendations
- ML ranking
- skill execution
- LLM adaptation
- drift detection
- full evaluation harness
- source code indexing
- deep integrations with coding assistants
- multi-tenant / SaaS hosting
- paid features or licensing

## Phase 1 user journeys

### Journey 1: Deploy Alexandria

A platform team deploys Alexandria on their AWS account:

- provision infrastructure using provided Terraform modules
- configure OIDC identity provider
- deploy container images
- verify health checks
- create initial admin user

### Journey 2: Create a skill

A skill author creates a new skill with:

- name
- description
- owner team
- canonical instructions
- task type
- supported tools
- languages and frameworks
- applicability
- limitations
- examples
- risk level
- visibility

The skill starts as a draft.

### Journey 3: Publish a skill

The author completes the required metadata and publishes the skill.

Published skills become visible according to their visibility:

- private
- team
- organization
- restricted

Published does not mean approved. It only means discoverable.

### Journey 4: Search for a skill

A developer searches the web catalog:

```text
terraform security review
```

Results show:

- name
- description
- owner
- lifecycle status
- approval state
- supported tools
- languages/frameworks
- risk level
- last updated
- usage count

The developer can filter results by:

- task type
- language
- framework
- tool
- approval state
- risk level
- owner team
- visibility
- freshness

### Journey 5: Reuse a skill

A developer opens a skill and clicks:

- Copy generic prompt
- Copy for GitHub Copilot
- Copy for Codex
- Export as Markdown
- Export as `AGENTS.md` fragment
- Export as Cursor rule
- Export as AWS Kiro steering/spec fragment

Each copy/export event is tracked.

### Journey 6: Review and approve a skill

A reviewer inspects a skill and can:

- approve
- reject
- request changes
- comment
- mark as deprecated
- mark as unsafe or blocked

Approvals change trust signals and search ranking.

### Journey 7: Submit feedback

A developer can mark a skill as:

- worked well
- not relevant
- outdated
- unclear
- wrong framework
- unsafe
- needs improvement

Feedback appears to owners and admins.

## Product screens

### 1. Catalog page

Purpose:

- search and browse skills

Features:

- search bar
- filters
- sortable result list
- lifecycle badges
- approval badges
- supported tool badges
- risk labels
- usage indicators

### 2. Skill detail page

Sections:

1. Overview
2. Instructions
3. Tool-specific variants
4. Applicability
5. Examples
6. Limitations
7. Risks
8. Review status
9. Version history
10. Feedback
11. Related metadata

### 3. Skill editor

Features:

- Markdown editor
- structured metadata form
- tool variant editor
- examples editor
- preview mode
- save draft
- publish
- request review

Required for draft:

- name
- description
- owner
- canonical instructions

Required for publishing:

- task type
- visibility
- supported tools
- risk level
- applicability
- at least one example or usage rationale

Required for company approval:

- owner team
- limitations
- risk classification
- reviewer
- approval notes

### 4. Review queue

Features:

- pending reviews
- review type filters
- status filters
- comments
- approve/reject/request changes
- high-risk queue

### 5. Admin console

Features:

- manage users
- manage teams
- manage roles
- manage taxonomy
- configure approval policies
- configure visibility defaults
- view audit logs
- view adoption metrics

## Skill lifecycle

Use this lifecycle:

```text
Draft
  ↓
Published
  ↓
Team Approved
  ↓
Company Approved
  ↓
Deprecated
  ↓
Archived
```

Add `Blocked` for unsafe skills.

### Draft

Visible only to author and collaborators.

### Published

Visible according to visibility settings. Not endorsed.

### Team Approved

Approved by owning team.

### Company Approved

Approved for broader organizational use.

### Deprecated

Visible but not recommended.

### Archived

Hidden from normal search.

### Blocked

Cannot be used or exported.

## Skill schema

A skill should be represented as a structured object.

### Example skill object

```json
{
  "id": "skill.secure-rest-endpoint.spring",
  "name": "Generate Secure REST Endpoint for Spring Boot",
  "description": "Creates a REST endpoint with validation, service-layer authorization, OpenAPI documentation, and tests.",
  "owner": {
    "team": "Billing Platform"
  },
  "version": "1.0.0",
  "status": "published",
  "visibility": "organization",
  "risk_level": "medium",
  "task_types": ["api_generation", "test_generation", "security_review"],
  "tools": ["github_copilot", "codex", "generic"],
  "applicability": {
    "languages": ["java"],
    "frameworks": ["spring_boot"],
    "required_files": ["pom.xml", "openapi.yaml"]
  },
  "instructions": {
    "canonical": "Use service-layer authorization. Do not put authorization logic in the controller. Update OpenAPI and generate tests."
  },
  "limitations": "Assumes service-layer authorization and Spring Boot 3 conventions.",
  "examples": [
    {
      "title": "Customer entitlement lookup endpoint",
      "input": "Create an endpoint for customer entitlement lookup.",
      "output_summary": "Generated controller, service method, OpenAPI path, and contract tests."
    }
  ]
}
```

## Database schema

### `skills`

```sql
CREATE TABLE skills (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  slug TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT NOT NULL,
  owner_team_id UUID,
  created_by UUID NOT NULL,
  current_version_id UUID,
  lifecycle_status TEXT NOT NULL,
  visibility TEXT NOT NULL,
  risk_level TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  archived_at TIMESTAMPTZ,
  UNIQUE (organization_id, slug)
);
```

### `skill_versions`

```sql
CREATE TABLE skill_versions (
  id UUID PRIMARY KEY,
  skill_id UUID NOT NULL REFERENCES skills(id),
  version_number INTEGER NOT NULL,
  semantic_version TEXT,
  canonical_instructions TEXT NOT NULL,
  when_to_use TEXT,
  when_not_to_use TEXT,
  limitations TEXT,
  applicability JSONB NOT NULL DEFAULT '{}',
  inputs JSONB NOT NULL DEFAULT '{}',
  outputs JSONB NOT NULL DEFAULT '{}',
  evaluation_notes TEXT,
  changelog TEXT,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (skill_id, version_number)
);
```

### `skill_variants`

```sql
CREATE TABLE skill_variants (
  id UUID PRIMARY KEY,
  skill_version_id UUID NOT NULL REFERENCES skill_versions(id),
  tool TEXT NOT NULL,
  instructions TEXT NOT NULL,
  format TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (skill_version_id, tool)
);
```

### `skill_examples`

```sql
CREATE TABLE skill_examples (
  id UUID PRIMARY KEY,
  skill_version_id UUID NOT NULL REFERENCES skill_versions(id),
  title TEXT NOT NULL,
  input_example TEXT,
  output_example TEXT,
  repository_context TEXT,
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL
);
```

### `skill_tags`

```sql
CREATE TABLE skill_tags (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  type TEXT NOT NULL,
  value TEXT NOT NULL,
  UNIQUE (organization_id, type, value)
);
```

### `skill_tag_links`

```sql
CREATE TABLE skill_tag_links (
  skill_id UUID NOT NULL REFERENCES skills(id),
  tag_id UUID NOT NULL REFERENCES skill_tags(id),
  PRIMARY KEY (skill_id, tag_id)
);
```

### `skill_reviews`

```sql
CREATE TABLE skill_reviews (
  id UUID PRIMARY KEY,
  skill_id UUID NOT NULL REFERENCES skills(id),
  skill_version_id UUID NOT NULL REFERENCES skill_versions(id),
  reviewer_id UUID NOT NULL,
  review_type TEXT NOT NULL,
  status TEXT NOT NULL,
  comments TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ
);
```

### `skill_feedback`

```sql
CREATE TABLE skill_feedback (
  id UUID PRIMARY KEY,
  skill_id UUID NOT NULL REFERENCES skills(id),
  skill_version_id UUID REFERENCES skill_versions(id),
  user_id UUID NOT NULL,
  rating INTEGER,
  feedback_type TEXT,
  comment TEXT,
  created_at TIMESTAMPTZ NOT NULL
);
```

### `skill_usage_events`

```sql
CREATE TABLE skill_usage_events (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  skill_id UUID NOT NULL REFERENCES skills(id),
  skill_version_id UUID REFERENCES skill_versions(id),
  user_id UUID,
  event_type TEXT NOT NULL,
  source TEXT NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL
);
```

### `audit_events`

```sql
CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  actor_user_id UUID,
  action TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id UUID NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL
);
```

## Controlled taxonomy

### Task types

```text
code_generation
test_generation
code_review
architecture_review
debugging
refactoring
documentation
migration
security_review
performance_review
infrastructure
ci_cd
data_processing
api_design
incident_response
```

### Tools

```text
generic
github_copilot
codex
cursor
aws_kiro
sourcegraph_cody
claude_code
jetbrains_ai
internal_agent
```

### Risk levels

```text
low
medium
high
restricted
```

### Approval states

```text
unverified
team_approved
company_approved
deprecated
blocked
```

### Visibility

```text
private
team
organization
restricted
```

## Search implementation

Phase 1 search should use PostgreSQL full-text search.

### Search document

```sql
CREATE TABLE skill_search_documents (
  skill_id UUID PRIMARY KEY REFERENCES skills(id),
  organization_id UUID NOT NULL,
  document TSVECTOR NOT NULL,
  raw_text TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
```

Search should include:

- name
- description
- canonical instructions
- when to use
- when not to use
- limitations
- examples
- tags
- owner team

### Basic ranking

Use a deterministic score:

```text
score =
  text_match_score
+ approval_boost
+ usage_boost
+ freshness_boost
+ tag_match_boost
- deprecated_penalty
```

Example boosts:

```text
Company approved: +30
Team approved: +15
Published only: +0
Copied more than 20 times: +10
Updated in last 90 days: +5
Exact tag match: +10
Deprecated: excluded by default
Blocked: always excluded
```

## Export formats

### Generic Markdown

```markdown
# Generate Secure REST Endpoint

## Purpose

Use this skill to generate a REST endpoint with validation, authorization, OpenAPI updates, and tests.

## Instructions

...

## When to use

...

## When not to use

...

## Expected output

...

## Checklist

...
```

### GitHub Copilot

```markdown
You are helping implement a secure REST endpoint.

Follow these rules:
1. Use service-layer authorization.
2. Do not put authorization logic in the controller.
3. Update OpenAPI documentation.
4. Generate unit and contract tests.
5. Follow existing repository patterns.

Before writing code:
- inspect nearby endpoints
- identify existing DTO patterns
- identify existing authorization services

Return:
- implementation plan
- code changes
- test changes
- assumptions
```

### Cursor rule

```markdown
---
description: Secure REST endpoint generation
globs:
  - "**/*.ts"
  - "**/*.java"
alwaysApply: false
---

When generating REST endpoints, follow this workflow:
...
```

### `AGENTS.md` fragment

```markdown
## Secure REST endpoint workflow

When asked to create or modify REST endpoints:

1. Inspect existing endpoint patterns.
2. Keep authorization in the service layer.
3. Update OpenAPI definitions.
4. Generate tests before implementation where possible.
5. Do not introduce new dependencies without justification.
```

### AWS Kiro steering/spec fragment

```markdown
# Steering: Secure API Endpoint Generation

## Objective

Ensure generated API endpoints follow security, validation, documentation, and test standards.

## Constraints

...

## Acceptance Criteria

...
```

## API endpoints

### Skills

```http
POST /api/skills
GET /api/skills
GET /api/skills/{skillId}
PATCH /api/skills/{skillId}
DELETE /api/skills/{skillId}
```

### Versions

```http
POST /api/skills/{skillId}/versions
GET /api/skills/{skillId}/versions
GET /api/skills/{skillId}/versions/{versionId}
POST /api/skills/{skillId}/versions/{versionId}/publish
```

### Search

```http
GET /api/search/skills?q=terraform+review&tool=github_copilot&status=company_approved
```

### Variants

```http
POST /api/skills/{skillId}/versions/{versionId}/variants
GET /api/skills/{skillId}/versions/{versionId}/variants/{tool}
```

### Export

```http
POST /api/skills/{skillId}/versions/{versionId}/export
```

Example request:

```json
{
  "tool": "github_copilot",
  "format": "markdown"
}
```

### Reviews

```http
POST /api/skills/{skillId}/reviews
GET /api/reviews
PATCH /api/reviews/{reviewId}
```

### Feedback

```http
POST /api/skills/{skillId}/feedback
GET /api/skills/{skillId}/feedback
```

### Usage events

```http
POST /api/events
```

## Security requirements

### Content scanning

Scan skills for:

- secrets
- private keys
- tokens
- passwords
- credentials
- dangerous shell commands
- unsafe instructions

### Permission enforcement

Search must enforce permissions before returning results.

Private skill titles should not leak in result counts, suggestions, or analytics visible to unauthorized users.

### Audit logging

Audit every meaningful governance action.

### Markdown safety

Sanitize rendered Markdown. Prevent script injection and unsafe HTML.

## Analytics

Phase 1 dashboards should include:

### Catalog health

- total skills
- published skills
- approved skills
- deprecated skills
- stale skills
- orphaned skills

### Adoption

- active users
- searches per week
- skill views
- copies
- exports
- repeat users

### Quality

- average rating
- negative feedback rate
- most reported skills
- review backlog

### Governance

- pending reviews
- overdue reviews
- high-risk skills
- restricted skills
- recent audit events

## Implementation milestones

### Milestone 0: Project setup and deployment infrastructure

Build:

- open-source repository structure
- CI/CD pipeline
- Docker Compose for local development
- Terraform modules for AWS deployment
- container image build and publish
- README and contributing guidelines

Deliverable:

> A contributor can clone the repo and run locally. A deployer can provision infrastructure on AWS.

### Milestone 1: Foundation

Build:

- authentication (OIDC)
- organization setup
- users
- teams
- RBAC
- base UI shell
- PostgreSQL schema
- audit events

Deliverable:

> Users can log in and see an empty catalog.

### Milestone 2: Skill CRUD

Build:

- create skill
- edit skill
- version creation
- skill detail page
- draft/publish states
- Markdown editor

Deliverable:

> Users can create and publish structured skills.

### Milestone 3: Search and filters

Build:

- full-text search
- tag filters
- lifecycle filters
- tool filters
- language/framework filters
- basic ranking

Deliverable:

> Users can discover skills.

### Milestone 4: Variants and export

Build:

- tool-specific variants
- generic export
- copy buttons
- Markdown export
- `AGENTS.md` export
- Cursor rule export
- Copilot prompt export

Deliverable:

> Users can reuse skills in their daily tools manually.

### Milestone 5: Review workflow

Build:

- review requests
- review queue
- comments
- approval states
- deprecation
- approval badges

Deliverable:

> Organizations can govern skill quality.

### Milestone 6: Feedback and analytics

Build:

- usage events
- feedback
- basic dashboards
- stale skill indicators

Deliverable:

> Platform owners can observe adoption and catalog quality.

### Milestone 7: Import

Build:

- Markdown import
- paste-to-skill conversion
- optional LLM-assisted metadata extraction

Deliverable:

> Teams can onboard existing skills quickly.

## MVP cut line

If scope must be reduced, keep:

1. OIDC login
2. teams and permissions
3. skill CRUD
4. versioning
5. tags and filters
6. full-text search
7. copy/export
8. review status
9. basic feedback
10. audit logs
11. Docker Compose local deployment
12. Terraform AWS deployment

Defer:

- semantic search
- IDE integration
- CLI
- Slack notifications
- advanced dashboards
- SCIM
- LLM-assisted import
- complex approval policies
- Helm charts

## Success metrics

### Adoption

- number of organizations deploying Alexandria
- number of active users per deployment
- number of published skills
- number of teams contributing skills
- number of searches per week
- number of copies/exports
- GitHub stars and community engagement

### Governance

- percentage of skills with owners
- percentage of skills approved
- review completion time
- number of deprecated or blocked skills
- number of high-risk skills reviewed

### Quality

- average skill rating
- feedback rate
- not-relevant rate
- outdated feedback rate
- repeat usage

### Community

- contributors
- issues and discussions
- pull requests
- deployments reported
- community-contributed integrations

### Validation

- organizations recognize the pain
- organizations agree this should be a platform
- organizations successfully deploy and use it
- feedback confirms governance is valuable
- community grows organically

## Phase 1 thesis

Phase 1 succeeds if the platform becomes the place where developers think:

> Before I reinvent an AI workflow, I should check whether another team already has a good one.

The product does not need to be magical yet. It needs to be trusted, searchable, structured, easy to contribute to, easy to reuse, governed enough for enterprise use, and simple enough to deploy in an afternoon.

The open-source model removes the procurement friction that would kill adoption at this stage of the market.
