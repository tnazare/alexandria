# Tech Stack

## Backend
- **Java 25 + Spring Boot** — modular monolith, REST API, background workers
- Modules: Auth, Organization, Team, Skill, Version, Variant, Search, Review, Feedback, Export, Audit, Admin, Import, Embedding, Context, Integration, Notification, Analytics

## Frontend
- **Next.js + TypeScript** — server/client rendered, Markdown editor, structured forms, syntax highlighting

## Database
- **PostgreSQL** — relational data, JSONB metadata, full-text search (Phase 1), pgvector (Phase 2)
- Every table has `organization_id UUID NOT NULL` (design convention for single-org deployment)

## Storage & Infra
- S3-compatible object storage (deployer-provisioned)
- Self-hosted on customer AWS: ECS Fargate (or K8s), RDS PostgreSQL, SQS
- OpenTelemetry for observability
- Terraform modules provided for deployment
- Docker Compose for local dev and evaluation

## Auth
- OIDC (configurable provider) — Okta, Microsoft Entra ID, Google Workspace, OneLogin, or any OIDC-compliant IdP
- SAML SSO (later)
- SCIM (later)

## IDE & CLI (Phase 2)
- VS Code extension (TypeScript) — connects to organization's Alexandria instance
- CLI in Go (cross-platform binaries)

## Background Jobs
- SQS on AWS (production deployment)
- In-memory or Redis queue (local dev via Docker Compose)
- Tasks: search index updates, embedding generation, import processing, usage aggregation, notifications

## Deployment Model
- Open-source, self-hosted on customer AWS account
- Single-organization instance (no multi-tenant SaaS)
- Container images published to GHCR
- Deployer provisions: AWS account, RDS, S3, SQS, OIDC provider
- All config via environment variables (no source modification needed)

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

# Docker (local dev)
docker compose up               # Start all services
docker compose down             # Stop all services

# Container images
docker build -t alexandria-backend ./backend
docker build -t alexandria-frontend ./frontend
```

## Key Principles
- Open source with permissive license
- Self-hosted — all data stays in customer's security boundary
- Modular monolith — extract to services only when justified by scale
- Embedding provider abstraction — never hardcode one model vendor
- Event capture from day one — usage data feeds future ranking
- Organization scoping enforced at query layer AND application layer
- Simple to deploy and operate — platform team can get running in an afternoon
- No microservices, no Kafka, no separate vector DB until necessary
- Configuration through environment variables, not code changes
