# Role-Based Access Control

Alexandria uses organization-level roles to control access to features and API endpoints. Each user is assigned exactly one role.

## Role Hierarchy

Roles are ordered by privilege level (lowest to highest):

| Role | Privilege | Description |
|------|-----------|-------------|
| `Read_Only_Auditor` | 0 | View audit logs only; cannot modify any resources |
| `Skill_Consumer` | 1 | Browse and use skills from the catalog |
| `Skill_Author` | 2 | Create and edit skills |
| `Reviewer` | 3 | Review and approve skills |
| `Team_Owner` | 4 | Manage team membership |
| `Catalog_Admin` | 5 | Manage teams and skills organization-wide |
| `Org_Admin` | 6 | Full administrative access |

## Access Rules

### Org_Admin

Full access to all endpoints and operations. Cannot be denied by role-based checks.

### Catalog_Admin

- Create and manage teams
- Manage skill catalog
- Cannot access user role management or audit logs (unless also granted via another mechanism)

### Read_Only_Auditor

- View audit event logs
- Cannot create, update, or delete any resources (all mutating operations return 403)

### Skill_Consumer, Skill_Author, Reviewer

- Cannot access admin endpoints (user management, team creation, audit logs)
- Access is limited to catalog browsing and skill-related operations

## Endpoint Access Matrix

| Endpoint | Org_Admin | Catalog_Admin | Read_Only_Auditor | Others |
|----------|-----------|---------------|-------------------|--------|
| `GET /api/me` | ✓ | ✓ | ✓ | ✓ |
| `GET /api/users` | ✓ | ✗ | ✗ | ✗ |
| `PATCH /api/users/{id}/role` | ✓ | ✗ | ✗ | ✗ |
| `POST /api/teams` | ✓ | ✓ | ✗ | ✗ |
| `GET /api/teams` | ✓ | ✓ | ✓ | ✓ |
| `GET /api/teams/{id}` | ✓ | ✗ | ✗ | ✗ |
| `POST /api/teams/{id}/members` | ✓ | owner | ✗ | ✗ |
| `DELETE /api/teams/{id}/members/{uid}` | ✓ | owner | ✗ | ✗ |
| `GET /api/audit-events` | ✓ | ✗ | ✓ | ✗ |

## Organization Scoping

Role-based access control works in conjunction with organization scoping. All queries are filtered to the authenticated user's organization — a user can never access resources belonging to a different organization, regardless of their role.

## Implementation

RBAC is enforced at the API layer using Spring Security's `@PreAuthorize` annotations with custom SpEL expressions. The authenticated principal carries the user's role, which is checked before any endpoint logic executes.

## Related

- [User Management](/features/users) — Assigning roles to users
- [Authentication](/features/authentication) — How identity is established
