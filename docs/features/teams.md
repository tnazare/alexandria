# Team Management

Teams allow organizations to group users for collaborative skill ownership and governance workflows.

## Team Structure

Each team has:

| Field | Description |
|-------|-------------|
| `name` | Human-readable name (2–100 characters, alphanumeric with spaces, hyphens, and underscores) |
| `slug` | URL-safe identifier auto-generated from the name |
| `description` | Optional description of the team's purpose |
| `memberCount` | Number of users in the team |

## Slug Generation

Team slugs are automatically generated from the name:

- Converted to lowercase
- Spaces and underscores replaced with hyphens
- Non-alphanumeric characters (except hyphens) removed
- Consecutive hyphens collapsed
- Leading and trailing hyphens trimmed

Slugs are unique within an organization. Attempting to create a team with a duplicate slug results in a 409 Conflict error.

## Team Roles

Members within a team have one of two roles:

| Role | Capabilities |
|------|-------------|
| `owner` | Add/remove members, manage team settings |
| `member` | Participate in team activities |

Every team must have at least one owner. Removing the last owner is prevented with a 409 Conflict error.

## Permissions

| Operation | Required Role |
|-----------|--------------|
| Create team | `Org_Admin` or `Catalog_Admin` |
| List teams | Any authenticated user |
| View team details | `Org_Admin` |
| Add member | `Org_Admin` or team owner |
| Remove member | `Org_Admin` or team owner |

## Audit Trail

All team operations generate audit events:

- `team.created` — When a new team is created
- `team_member.added` — When a member is added
- `team_member.removed` — When a member is removed

## Related

- [Teams API](/api/teams) — API endpoints for team management
- [Audit Logging](/features/audit) — Audit event details
