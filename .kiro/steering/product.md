# Product: Alexandria

Enterprise skill layer for AI-assisted software engineering — a governed, searchable, evidence-ranked registry of reusable AI coding workflows that works across tools (GitHub Copilot, Codex, Cursor, AWS Kiro, Claude Code, Sourcegraph Cody).

## Domain Model

- **Skill**: reusable AI engineering workflow with structured metadata, versioned and governed
- **Skill Version**: immutable content snapshot (instructions, applicability, examples, limitations)
- **Skill Variant**: tool-specific instructions for a version (Copilot, Cursor, Codex, Kiro, etc.)
- **Lifecycle**: Draft → Published → Team Approved → Company Approved → Deprecated → Archived | Blocked
- **Visibility**: private | team | organization | restricted
- **Risk Level**: low | medium | high | restricted

## Phases

1. **Governed Skill Registry** — CRUD, versioning, approval workflows, full-text search, copy/export, audit
2. **Hybrid Search & Workflow Integration** — semantic search (pgvector), VS Code extension, CLI, context-aware discovery
3. **Ranking & Telemetry** — trust scoring, usage-based recommendations, ML-assisted ranking
4. **Skill Compiler & Adapters** — tool-specific compilation, managed export
5. **Contextual Adaptation & Composition** — LLM-assisted adaptation, multi-skill workflows

## Multi-Tenancy

Every tenant-owned resource is scoped by `organization_id`. All queries must enforce tenant isolation. Search results must respect ACL — private skill names/metadata must never leak.
