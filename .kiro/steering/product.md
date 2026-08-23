# Product: Alexandria

Open-source, self-hosted enterprise skill layer for AI-assisted software engineering — a governed, searchable, evidence-ranked registry of reusable AI coding workflows that organizations deploy on their own AWS infrastructure. Works across tools (GitHub Copilot, Codex, Cursor, AWS Kiro, Claude Code, Sourcegraph Cody).

## Distribution Model

- **Open source** with permissive license
- **Self-hosted** on customer's AWS account (ECS Fargate or Kubernetes)
- **Single-organization** instance — no multi-tenant SaaS
- All data stays within the organization's security boundary
- Container images published to public registry (GHCR)
- Terraform modules provided for standard AWS deployment
- Docker Compose for local development and evaluation

## Domain Model

- **Skill**: reusable AI engineering workflow with structured metadata, versioned and governed
- **Skill Version**: immutable content snapshot (instructions, applicability, examples, limitations)
- **Skill Variant**: tool-specific instructions for a version (Copilot, Cursor, Codex, Kiro, etc.)
- **Lifecycle**: Draft → Published → Team Approved → Company Approved → Deprecated → Archived | Blocked
- **Visibility**: private | team | organization | restricted
- **Risk Level**: low | medium | high | restricted

## Phases

1. **Governed Skill Registry** — CRUD, versioning, approval workflows, full-text search, copy/export, audit, self-hosted deployment
2. **Hybrid Search & Workflow Integration** — semantic search (pgvector), VS Code extension, CLI, context-aware discovery
3. **Ranking & Telemetry** — trust scoring, usage-based recommendations, ML-assisted ranking
4. **Skill Compiler & Adapters** — tool-specific compilation, managed export
5. **Contextual Adaptation & Composition** — LLM-assisted adaptation, multi-skill workflows

## Organization Scoping

Every table includes `organization_id UUID NOT NULL` as a design convention. Queries enforce scoping. Search results respect ACL — private skill names/metadata must never leak.

## Why Not SaaS

- Market too early for paid SaaS — category doesn't exist yet
- Enterprises won't store proprietary AI workflows in third-party infrastructure
- Open source builds trust and accelerates adoption
- Self-hosted removes procurement friction
- Community contributions improve the platform faster
