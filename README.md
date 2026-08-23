# Alexandria

Open-source, self-hosted enterprise skill layer for AI-assisted software engineering — a governed, searchable, evidence-ranked registry of reusable AI coding workflows that organizations deploy on their own infrastructure.

Alexandria works across tools: GitHub Copilot, Codex, Cursor, AWS Kiro, Claude Code, and Sourcegraph Cody.

## Prerequisites

- **Java 25** (managed via [SDKMAN](https://sdkman.io/) — see `.sdkmanrc`)
- **Docker** and **Docker Compose**
- **Node.js 22+** (for frontend and documentation site)

## Quick Start

```bash
# Start all services (PostgreSQL, backend, frontend)
docker compose up

# Verify the backend is healthy
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

The backend API is available at `http://localhost:8080` and the frontend at `http://localhost:5173`.

## Architecture

Alexandria follows a **modular monolith** architecture with strict layer separation:

```
alexandria/
├── backend/                        # Java 25 + Spring Boot 4
│   ├── alexandria-api/             # Interfaces, DTOs, validation annotations
│   ├── alexandria-entities/        # JPA entities, repositories, Flyway migrations
│   └── alexandria-service/         # Resource implementations, services, config
├── frontend/                       # React/TypeScript SPA (Vite)
│   ├── src/admin/                  # Admin console (users, teams, roles, audit)
│   └── src/browser/                # Skill catalog (search, browse, export)
├── docs/                           # VitePress documentation site
└── docker-compose.yml              # Local development environment
```

### Backend Modules

| Module | Responsibility |
|--------|---------------|
| `alexandria-api` | REST API contracts (interfaces, DTOs) — no internal dependencies |
| `alexandria-entities` | Data layer (JPA entities, Spring Data repositories, Flyway migrations) — no internal dependencies |
| `alexandria-service` | Application layer (controllers, services, security config) — depends on api + entities |

### Key Technologies

- **Backend:** Java 25, Spring Boot 4, Spring Security (OIDC resource server), Flyway, PostgreSQL
- **Frontend:** React 19, TypeScript, Vite, Tailwind CSS, React Router, TanStack Query
- **Auth:** OIDC (any compliant provider — Okta, Entra ID, Google Workspace, etc.)
- **Infrastructure:** Docker Compose (local dev), AWS ECS/RDS/SQS (production)

## Development

```bash
# Backend only (requires PostgreSQL running)
cd backend
./mvnw spring-boot:run -pl alexandria-service

# Frontend only
cd frontend
npm run dev

# Run backend tests
cd backend
./mvnw test

# Run frontend tests
cd frontend
npm run test
```

## Documentation

Full documentation is available in the `docs/` directory and can be built with:

```bash
cd docs
npm install
npm run docs:dev
```

## License

[Apache License 2.0](LICENSE)
