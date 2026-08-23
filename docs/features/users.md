# User Management

Alexandria manages users through automatic provisioning and administrative controls. Users are scoped to a single organization and assigned roles that govern their access.

## User Lifecycle

1. **Auto-Provisioned** — Created on first OIDC login with the default `Skill_Consumer` role
2. **Active** — User can authenticate and access resources per their assigned role
3. **Disabled** — Administrators can disable users to revoke access without deleting their data

## User Properties

| Field | Description |
|-------|-------------|
| `id` | Unique identifier (UUID) |
| `email` | Email address from OIDC claims |
| `displayName` | Human-readable name |
| `role` | Organization-level role (one of seven defined roles) |
| `enabled` | Whether the user can access the system |
| `organizationId` | Organization the user belongs to |
| `createdAt` | Timestamp of first provisioning |

## Administrative Operations

Administrators (`Org_Admin` role) can:

- **List all users** — Paginated view of all users in the organization
- **Change user roles** — Assign any of the seven available roles
- **View user profiles** — See current role, status, and activity

## Current User Profile

Any authenticated user can retrieve their own profile via `GET /api/me`, which returns their ID, email, display name, role, and organization ID.

## Organization Scoping

All user queries are scoped to the authenticated user's organization. Users from one organization cannot see or interact with users from another organization.

## Related

- [Roles and Permissions](/features/rbac) — Detailed role descriptions
- [Users API](/api/users) — API endpoints for user management
