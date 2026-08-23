---
layout: home

hero:
  name: Alexandria
  text: Enterprise Skill Layer for AI-Assisted Engineering
  tagline: A governed, searchable, evidence-ranked registry of reusable AI coding workflows — open-source and self-hosted.
  actions:
    - theme: brand
      text: Get Started
      link: /guide/installation
    - theme: alt
      text: View on GitHub
      link: https://github.com/alexandria

features:
  - title: Self-Hosted & Secure
    details: Deploy on your own AWS infrastructure. All data stays within your organization's security boundary.
  - title: OIDC Authentication
    details: Integrate with your existing identity provider — Okta, Microsoft Entra ID, Google Workspace, or any OIDC-compliant IdP.
  - title: Role-Based Access Control
    details: Fine-grained permissions with seven organizational roles, team-scoped visibility, and audit logging for compliance.
  - title: Tool-Agnostic
    details: Works across GitHub Copilot, Codex, Cursor, AWS Kiro, Claude Code, and Sourcegraph Cody.
  - title: Governed Workflows
    details: Lifecycle management with Draft, Published, Team Approved, Company Approved, Deprecated, and Archived states.
  - title: Open Source
    details: Apache 2.0 licensed. Community-driven development with enterprise-grade quality.
---

## What is Alexandria?

Alexandria is an open-source, self-hosted enterprise skill layer for AI-assisted software engineering. It provides a governed, searchable, evidence-ranked registry of reusable AI coding workflows that organizations deploy on their own infrastructure.

### Key Capabilities

- **Skill Registry** — Create, version, and manage AI coding workflows with structured metadata
- **Team Collaboration** — Organize users into teams with ownership and visibility scoping
- **Approval Workflows** — Multi-stage lifecycle from Draft to Company Approved
- **Full-Text Search** — Discover skills by keyword, category, language, framework, or tool
- **Multi-Tool Export** — Export skills in formats for Copilot, Cursor, Codex, Kiro, and more
- **Audit Trail** — Complete record of who changed what and when for compliance

### Architecture

Alexandria uses a modular monolith architecture:

- **Backend** — Java 25 + Spring Boot with REST API
- **Frontend** — React + TypeScript SPA with admin and catalog views
- **Database** — PostgreSQL with Flyway migrations
- **Auth** — OIDC resource server with JWT validation
- **Deployment** — Docker Compose (local) or Terraform on AWS (production)
