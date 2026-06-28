# Tech Stack

## Backend
- **Java 25 + Spring Boot** — modular monolith, REST API, background workers
- Modules: Auth, Organization, Team, Skill, Version, Variant, Search, Review, Feedback, Export, Audit, Admin, Import, Embedding, Context, Integration, Notification, Analytics

## Frontend
- **Next.js + TypeScript** — server/client rendered, Markdown editor, structured forms, syntax highlighting

## Database
- **PostgreSQL** — relational data, JSONB metadata, full-text search (Phase 1), pgvector (Phase 2)
- Every tenant-owned table has `organization_id UUID NOT NULL`

## Storage & Infra
- S3-compatible object storage
- AWS: ECS Fargate (or K8s), RDS PostgreSQL, SQS, CloudFront, WAF
- OpenTelemetry for observability
- Terraform or Pulumi for IaC

## Auth
- OIDC (MVP) → SAML SSO (enterprise) → SCIM (later)
- Providers: Okta, Microsoft Entra ID, Google Workspace, OneLogin

## IDE & CLI (Phase 2)
- VS Code extension (TypeScript)
- CLI in Go (cross-platform binaries)

## Background Jobs
- SQS on AWS (production), BullMQ/Redis (local dev)
- Tasks: search index updates, embedding generation, import processing, usage aggregation, notifications

## Common Commands

```bash
# Backend (Java/Spring Boot)
./mvnw spring-boot:run          # Start dev server
./mvnw test                     # Run unit tests
./mvnw verify                   # Run integration tests
./mvnw package                  # Build JAR

# Frontend (Next.js)
npm run dev                     # Start dev server
npm run build                   # Production build
npm run lint                    # Lint
npm run test                    # Run tests

# Database
./mvnw flyway:migrate           # Run migrations
./mvnw flyway:info              # Migration status
```

## Key Principles
- Modular monolith — extract to services only when justified by scale
- Embedding provider abstraction — never hardcode one model vendor
- Event capture from day one — usage data feeds future ranking
- Tenant scoping enforced at query layer AND application layer
- No microservices, no Kafka, no separate vector DB until necessary
