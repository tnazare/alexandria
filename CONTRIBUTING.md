# Contributing to Alexandria

Thank you for your interest in contributing to Alexandria! This guide covers our conventions, workflow, and expectations for contributions.

## Getting Started

1. Fork the repository and clone your fork
2. Ensure you have the prerequisites installed (see [README.md](README.md))
3. Run `docker compose up` to verify the local environment works
4. Create a feature branch from `main`

## Branch Naming

Use the following prefixes for branch names:

- `feature/` — new functionality (e.g., `feature/team-management-api`)
- `fix/` — bug fixes (e.g., `fix/slug-generation-edge-case`)
- `docs/` — documentation changes (e.g., `docs/oidc-setup-guide`)

## Coding Standards

### Backend (Java)

Alexandria follows strict coding conventions for the Java backend. Key principles:

- **Architecture:** Three-layer module separation (api → entities → service)
- **Naming:** `{Domain}Resource`, `{Domain}Api`, `{Domain}Service`, `{Domain}Entity`, `{Domain}Repository`
- **DI:** Constructor injection via `@RequiredArgsConstructor` — never field injection
- **DTOs:** Java records for all request/response models
- **Transactions:** `@Transactional(readOnly = true)` for reads, `@Transactional` for writes
- **Organization scoping:** Every query must filter by `organizationId`
- **Documentation:** Javadoc on all public interfaces and service methods

For the complete coding standards reference, see `.kiro/steering/java-coding-standards.md`.

### Frontend (TypeScript/React)

- TypeScript strict mode enabled
- Functional components with hooks
- Shared types must mirror backend DTOs
- Use TanStack Query for server state management
- Tailwind CSS for styling — no custom CSS unless necessary

### General

- Write tests for new functionality (unit + property-based where applicable)
- Keep commits focused and atomic
- No commented-out code in PRs
- Update documentation when adding or changing features

## Pull Request Process

1. **Create a PR** against `main` with a clear title and description
2. **Link related issues** in the PR description
3. **Ensure CI passes** — all tests, linting, and builds must be green
4. **Request review** from at least one maintainer
5. **Address feedback** — push additional commits (don't force-push during review)
6. **Squash merge** — PRs are squash-merged to keep `main` history clean

### PR Description Template

```
## Summary
Brief description of what this PR does.

## Changes
- List of notable changes

## Testing
- How the changes were tested
- New tests added

## Related Issues
Closes #123
```

## Running Tests

```bash
# Backend unit tests
cd backend
./mvnw test

# Backend integration tests (requires Docker for Testcontainers)
cd backend
./mvnw verify

# Frontend tests
cd frontend
npm run test

# Frontend lint
cd frontend
npm run lint
```

## Reporting Issues

When reporting bugs, include:

- Steps to reproduce
- Expected behavior vs actual behavior
- Environment details (OS, Java version, Docker version)
- Relevant log output

## License

By contributing to Alexandria, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
