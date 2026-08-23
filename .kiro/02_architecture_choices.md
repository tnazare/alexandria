# Alexandria Architecture Choices

## Architecture objective

The architecture must support an open-source, self-hosted product that enterprises deploy on their own AWS infrastructure. It begins as a governed skill registry and grows into a context-aware, cross-tool skill intelligence platform.

The system must handle:

- single-organization deployment
- secure skill storage within the customer's security boundary
- ownership and permissions
- versioning
- review workflows
- full-text and semantic search
- IDE and CLI integrations
- usage telemetry
- tool-specific exports
- future skill ranking, adaptation, and execution
- simple deployment and operations

The first implementation should remain simple enough for a small team to build and for a platform team to deploy, but structured enough to support enterprise-scale usage.

## Recommended architecture summary

```text
Frontend:
  Next.js + TypeScript web app
  VS Code extension in Phase 2
  CLI in Phase 2

Backend:
  Java 25 + Spring Boot
  REST API
  background workers

Database:
  PostgreSQL
  JSONB metadata
  full-text search
  pgvector in Phase 2

Storage:
  S3-compatible object storage

Auth:
  OIDC (configurable provider)
  SAML for enterprise (later)
  SCIM later

Deployment:
  Self-hosted on customer AWS account
  Container images (Docker)
  ECS Fargate or Kubernetes
  RDS PostgreSQL
  S3
  Terraform IaC templates provided

Infrastructure:
  AWS ECS Fargate or Kubernetes
  RDS PostgreSQL
  S3
  SQS for background jobs
  CloudFront (optional)
  WAF (optional)
  OpenTelemetry
```

## Core architectural principle

Start with a **modular monolith**, not microservices.

The domain will evolve quickly. Splitting into many services too early will slow product discovery, data model changes, and deployment. A modular monolith gives clean boundaries while preserving implementation speed and simplifying operations for self-hosted deployers.

Recommended backend modules:

```text
AuthModule
OrganizationModule
TeamModule
SkillModule
VersionModule
VariantModule
SearchModule
ReviewModule
FeedbackModule
ExportModule
AuditModule
AdminModule
ImportModule
EmbeddingModule
ContextModule
IntegrationModule
NotificationModule
AnalyticsModule
```

These modules can later be extracted into services if scale, team structure, or deployment requirements justify it.

## Deployment model: self-hosted on customer AWS

### Single-organization instance

Each deployment serves one organization. There is no multi-tenant data sharing between organizations. This eliminates the complexity of tenant isolation in a shared environment and gives organizations full control over their data.

Every table still includes `organization_id` as a design convention for:

- future flexibility if multi-instance management is added
- consistent query patterns
- data export and migration support

### What the deployer provides

- AWS account with appropriate permissions
- PostgreSQL database (RDS recommended)
- S3 bucket for object storage
- Container orchestration (ECS Fargate or Kubernetes)
- OIDC-compatible identity provider
- DNS and TLS certificate

### What Alexandria provides

- Container images published to a public registry (Docker Hub or GitHub Container Registry)
- Terraform modules for standard AWS deployment
- Docker Compose for local development and evaluation
- Helm charts for Kubernetes deployment (later)
- Configuration documentation
- Upgrade guides

### Configuration

All configuration through environment variables and configuration files:

```text
DATABASE_URL
S3_BUCKET
S3_REGION
OIDC_ISSUER_URI
OIDC_CLIENT_ID
OIDC_CLIENT_SECRET
SQS_QUEUE_URL
EMBEDDING_PROVIDER (optional, Phase 2)
EMBEDDING_API_KEY (optional, Phase 2)
```

No source code modification required for deployment.

### Deployment options

#### Standard: ECS Fargate

```text
ALB → ECS Fargate (backend + frontend containers) → RDS PostgreSQL + S3
         ↓
      SQS → Worker container
```

#### Alternative: Kubernetes (EKS or self-managed)

```text
Ingress → Pods (backend + frontend) → RDS PostgreSQL + S3
            ↓
         Worker pods
```

#### Local development and evaluation

```text
Docker Compose:
  - Backend container
  - Frontend container
  - PostgreSQL container
  - LocalStack (S3 + SQS emulation)
```

## Frontend choice

### Recommendation

Use **Next.js with TypeScript**.

### Why

- strong application ecosystem
- server-rendered and client-rendered pages where needed
- good developer velocity
- easy integration with authentication providers
- strong support for structured forms, admin pages, and dashboards
- large contributor pool for open-source project

### Key frontend surfaces

- skill catalog
- skill detail page
- skill editor
- review queue
- admin console
- analytics dashboard
- search result page
- export preview
- organization settings

### UI requirements

The UI should support:

- Markdown editing and preview
- structured metadata forms
- syntax-highlighted prompt variants
- copy/export buttons
- lifecycle badges
- approval badges
- risk labels
- explanations for search results
- audit and review history

## Backend choice

### Recommendation

Use **Java 25 with Spring Boot** for the backend.

### Why

- excellent enterprise patterns
- strong security ecosystem
- robust data access
- familiar to large engineering organizations
- well-suited for open-source contributions from enterprise developers
- mature container image tooling
- good fit for the target adopter profile

### Decision

Use Java for the backend and consider Go for the CLI in Phase 2.

## Database choice

### Recommendation

Use **PostgreSQL**.

### Why

PostgreSQL supports the Phase 1 and Phase 2 requirements well:

- relational data
- transactions
- versioning
- JSONB metadata
- full-text search
- audit logs
- event tables
- optional vector search through `pgvector`

Deployers already know how to operate PostgreSQL (most use RDS).

### Avoid early overengineering

Do not start with:

- separate document database
- graph database
- dedicated vector database
- OpenSearch
- Kafka
- many microservices

Add these when justified by scale or product requirements.

## Search architecture

### Phase 1

Use PostgreSQL full-text search.

Search over:

- skill name
- description
- canonical instructions
- when to use
- limitations
- examples
- tags
- owner team

### Phase 2

Add hybrid search:

- keyword retrieval
- semantic retrieval
- metadata filters
- deterministic ranking
- explanation generation

Recommended initial implementation:

```text
PostgreSQL full-text search
+
pgvector
+
metadata filters
+
ranking function in application code
```

Move to OpenSearch, Qdrant, or another dedicated system only when:

- catalog size is large
- search latency is poor
- advanced faceting is required
- typo tolerance and synonym management become critical
- vector scale exceeds PostgreSQL comfort zone

## Vector search choice

### Recommendation for Phase 2

Use **pgvector** first.

### Why

- operationally simple (no additional infrastructure for deployers)
- avoids another database
- good enough for MVP-scale semantic search
- works with organization-scoped filtering
- easier backup and deployment story

### Future options

Use a dedicated vector database later if required:

- Qdrant
- Weaviate
- Pinecone
- OpenSearch vector search

## Embedding provider abstraction

Do not hardcode one model provider.

Define an internal interface:

```java
public interface EmbeddingProvider {
    String modelName();
    int dimensions();
    List<float[]> embed(List<String> inputs);
}
```

Potential providers:

- OpenAI
- Azure OpenAI
- AWS Bedrock
- local embedding model (Ollama, etc.)
- customer-provided embedding endpoint

This matters because organizations have different provider restrictions, data residency requirements, and existing contracts. Some may prefer to use a local model that keeps all data on-premises.

## Data model overview

Core entities:

```text
Organization
User
Team
Skill
SkillVersion
SkillVariant
SkillExample
SkillTag
SkillRelationship
SkillReview
SkillApproval
SkillFeedback
SkillUsageEvent
SkillSearchEvent
SkillRecommendationEvent
SkillCollection
SkillPermission
RepositoryProfile
AuditEvent
```

## Skill model

A skill is the main domain entity.

A skill has stable identity:

```text
skill.secure-rest-endpoint.spring
```

A skill version stores content:

- canonical instructions
- when to use
- when not to use
- limitations
- applicability
- input contract
- expected outputs
- evaluation notes
- changelog

A skill variant stores tool-specific instructions:

- generic
- GitHub Copilot
- Codex
- Cursor
- AWS Kiro
- Claude Code
- Sourcegraph Cody
- internal agent

This separation is important because approval and usage should reference exact versions.

## Lifecycle model

Use a simple lifecycle first:

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

Add `Blocked` for known unsafe skills.

Lifecycle must be explicit because search, governance, and ranking depend on it.

## Authorization model

Use organization-level RBAC plus team-scoped access.

Roles:

```text
Org Admin
Catalog Admin
Reviewer
Team Owner
Skill Author
Skill Consumer
Read-only Auditor
```

Permissions:

```text
skill:create
skill:update
skill:publish
skill:review
skill:approve_team
skill:approve_company
skill:deprecate
skill:delete
skill:view_private
taxonomy:manage
audit:view
admin:manage_users
```

Visibility levels:

```text
private
team
organization
restricted
```

Permissions must be enforced at the query layer and the application layer. Search results must never leak private skill names or metadata.

## Authentication

### MVP

Support OIDC with configurable provider.

The deployer configures their identity provider (Okta, Microsoft Entra ID, Google Workspace, OneLogin, or any OIDC-compliant provider) through environment variables.

### Later

Add SAML SSO support.
Add SCIM provisioning.

## Audit logging

Audit logs are not optional for enterprise trust.

Create an audit event for:

- skill created
- skill edited
- version created
- skill published
- approval requested
- approval granted
- approval rejected
- visibility changed
- owner changed
- skill deprecated
- skill archived
- export generated
- permission changed

Audit event schema:

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

## Event architecture

Track product and ranking events from day one.

Events:

- skill viewed
- skill copied
- skill exported
- skill favorited
- feedback submitted
- search issued
- result shown
- result clicked
- result copied
- result ignored
- review requested
- approval completed

Store these in PostgreSQL event tables. For organizations that want to export to a warehouse (BigQuery, Snowflake, Redshift, ClickHouse), provide event export capabilities.

## Background jobs

Use background jobs for:

- search index updates
- embedding generation
- import processing
- usage aggregation
- notification delivery
- stale approval checks

Implementation:

- SQS on AWS for production deployments
- In-memory queue or Redis for local development (Docker Compose)

The deployer provisions the SQS queue and provides the URL through configuration.

## Export architecture

The export service converts a skill version into a tool-specific format.

Initial formats:

- generic Markdown
- GitHub Copilot prompt
- Codex instructions
- `AGENTS.md` fragment
- Cursor rule
- AWS Kiro steering/spec fragment

Export flow:

```text
SkillVersion
  ↓
Select variant
  ↓
Apply simple template placeholders
  ↓
Render target format
  ↓
Track export event
  ↓
Return copyable content or downloadable file
```

Do not build full LLM adaptation in Phase 1 or early Phase 2.

## Context collection architecture

Phase 2 introduces context-aware search.

Context levels:

```text
Level 0: no repository context
Level 1: metadata only
Level 2: structural context
Level 3: selected text with explicit permission
Level 4: full contextual indexing, deferred
```

Default should be Level 1.

Allowed metadata examples:

- language
- framework
- dependency names
- build tool
- active file path
- file extension
- preferred AI tool

Do not upload full source code by default. Context stays within the organization's network.

## IDE integration architecture

### VS Code extension

Phase 2 should include a VS Code extension.

Capabilities:

- login
- search skills
- find skills for current file
- find skills for selection
- preview skill
- copy/export
- add to `AGENTS.md`
- add to Copilot instructions
- send feedback

Authentication should use browser-based OAuth or device authorization flow against the organization's identity provider. Tokens should be stored in VS Code SecretStorage.

The extension connects to the organization's deployed Alexandria instance (configurable URL).

### CLI

Phase 2 can include a CLI.

Commands:

```bash
alexandria login
alexandria search "query"
alexandria show <skill>
alexandria export <skill> --tool codex
alexandria add <skill> --target agents-md
alexandria feedback <skill> --worked
```

Use Go for easy distribution as cross-platform binaries.

## Security architecture

### Data security

- TLS everywhere
- encryption at rest (RDS encryption, S3 encryption)
- least privilege IAM
- secure secrets management (AWS Secrets Manager or similar)
- regular backups (RDS automated backups)
- point-in-time recovery
- all data stays within customer AWS account

### Content security

Scan skill content for:

- secrets
- private keys
- API tokens
- credentials
- destructive shell commands
- unsafe instructions
- suspicious prompt-injection patterns

### IDE context security

Never send content from:

```text
.env
*.pem
*.key
secrets.*
credentials.*
kubeconfig
```

The extension and CLI should block this locally before any API call.

### Prompt safety

Flag skill instructions that tell an AI tool to:

- ignore security policies
- bypass authentication
- disable tests
- exfiltrate code
- hardcode credentials
- use production data
- execute destructive commands

## Observability

Use OpenTelemetry from the start.

Track:

- API latency
- search latency
- database query latency
- embedding job latency
- export failures
- IDE authentication failures
- queue depth
- worker failures
- error rate

Structured logs should avoid storing sensitive source content.

Deployers can route telemetry to their preferred observability stack (CloudWatch, Datadog, Grafana, etc.).

## Testing strategy

### Unit tests

- skill lifecycle transitions
- permission checks
- export rendering
- ranking features
- context normalization
- taxonomy validation

### Integration tests

- skill creation and publishing
- search with ACL filtering
- review workflow
- export flow
- feedback flow
- audit log creation

### Security tests

- cross-team access attempts
- private skill leakage in search
- unauthorized review approval
- sensitive file context blocking
- malicious Markdown rendering

### Search relevance tests

Maintain a small evaluation set of queries and expected skills.

Metrics:

- Precision@5
- Mean Reciprocal Rank
- zero-result rate
- not-relevant feedback rate

## Deployment architecture

### Standard AWS deployment

```text
ALB (with TLS)
  ↓
ECS Fargate
  ├── Backend service (Spring Boot)
  └── Frontend service (Next.js)
  ↓
RDS PostgreSQL (encrypted)
  ↓
S3 (encrypted)
  ↓
SQS
  ↓
Worker service (ECS Fargate)
```

Optional additions:

- CloudFront for frontend caching
- WAF for additional protection
- Secrets Manager for credential storage
- CloudWatch or external observability
- VPC with private subnets

### Provided Infrastructure-as-Code

Terraform modules covering:

- VPC and networking
- ECS cluster and services
- RDS PostgreSQL
- S3 bucket
- SQS queue
- ALB and target groups
- IAM roles and policies
- Security groups

The Terraform modules should be composable — deployers can use them as-is or integrate into their existing infrastructure code.

### Local development

Docker Compose for:

- Backend
- Frontend
- PostgreSQL
- LocalStack (S3 + SQS)

## Why not build an AI agent first

The product should not start as an autonomous AI agent.

Reasons:

- trust has not been established
- skill schema is not mature
- customer governance requirements are unknown
- execution risk is high
- search and reuse must be validated first

The right sequence is:

```text
Registry → Discovery → Ranking → Portability → Adaptation → Managed execution
```

## Key architecture decisions

| Decision | Choice | Rationale |
|---|---|---|
| Distribution model | Open source, self-hosted | Security, trust, early market timing |
| Deployment target | Customer AWS account | Data sovereignty, existing infra |
| Backend | Modular monolith | Domain is evolving, simple to deploy and operate |
| Database | PostgreSQL | Covers relational, JSONB, full-text, events, and vectors |
| Search Phase 1 | PostgreSQL full-text | Simple, no extra infrastructure |
| Search Phase 2 | Hybrid with pgvector | Semantic search without new infrastructure |
| Auth | OIDC (configurable provider) | Works with any enterprise IdP |
| IDE | VS Code first | Largest initial developer reach |
| Export | File and clipboard first | Avoid brittle vendor API dependencies |
| Context | Metadata-first | Better privacy posture |
| Events | Store from day one | Future ranking depends on usage data |
| IaC | Terraform | Most common in AWS shops |
| Container registry | Public (GHCR or Docker Hub) | Easy pull for any deployer |

## Architectural risks

### Risk: Deployment too complex for adopters

Mitigation:

- provide complete Terraform modules
- provide Docker Compose for evaluation
- minimize required infrastructure (PostgreSQL + S3 + SQS)
- clear documentation with step-by-step guides
- sensible defaults that work out of the box

### Risk: Upgrades are painful for self-hosted

Mitigation:

- Flyway migrations handle schema changes
- semantic versioning for releases
- upgrade guides for each release
- backward-compatible API changes where possible
- database migration testing in CI

### Risk: Retrofitting permissions later

Mitigation:

- make organization and visibility part of the data model from day one
- enforce ACL before search results are returned

### Risk: Search quality is weak

Mitigation:

- structured metadata
- controlled taxonomy
- hybrid search in Phase 2
- search evaluation set

### Risk: Catalog becomes a wiki

Mitigation:

- structured skill schema
- lifecycle state
- variants
- examples
- review workflow
- usage events

### Risk: Too much architecture too early

Mitigation:

- modular monolith
- PostgreSQL first
- avoid microservices and multiple specialized databases until necessary

## Final recommendation

Build the first version as an open-source, self-hosted modular monolith using Next.js, Java/Spring Boot, PostgreSQL, S3, and SQS. Provide Terraform modules and Docker Compose for deployment. Use PostgreSQL full-text search in Phase 1 and add `pgvector` in Phase 2. Keep source code context out of the product by default. Prioritize versioning, permissions, auditability, and event capture from the beginning. Make deployment simple enough that a platform team can get Alexandria running in an afternoon.

This architecture is simple enough to build quickly, easy to deploy and operate, and credible for enterprise use — while being fully open source.
