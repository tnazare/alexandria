# Audit Logging

Alexandria records audit events for all governance operations, providing a complete trail of administrative actions within an organization.

## Audited Operations

| Action | Target Type | Description |
|--------|-------------|-------------|
| `user.auto_provisioned` | `user` | New user created on first login |
| `user.auto_provision_failed` | `user` | User provisioning failed |
| `user.role_changed` | `user` | User's role was updated |
| `team.created` | `team` | New team was created |
| `team_member.added` | `team_member` | User added to a team |
| `team_member.removed` | `team_member` | User removed from a team |

## Event Structure

Each audit event captures:

| Field | Description |
|-------|-------------|
| `id` | Unique event identifier (UUID) |
| `actorUserId` | User who performed the action |
| `actorEmail` | Email of the actor |
| `action` | Operation type (see table above) |
| `targetType` | Type of entity affected |
| `targetId` | ID of the affected entity |
| `metadata` | Operation-specific details (JSON) |
| `createdAt` | Timestamp of the event |

## Metadata Examples

**Role change:**
```json
{
  "previousRole": "Skill_Consumer",
  "newRole": "Catalog_Admin"
}
```

**Team member added:**
```json
{
  "teamRole": "member",
  "memberEmail": "dev@example.com"
}
```

## Querying Audit Events

Audit events can be filtered by:

- **Action** — Filter by operation type
- **Target type** — Filter by entity type
- **Target ID** — Filter to a specific entity
- **Actor** — Filter by who performed the action
- **Date range** — Filter by time window

All filters combine with AND logic. Results are always ordered by timestamp (newest first).

## Access Control

Only users with `Org_Admin` or `Read_Only_Auditor` roles can view audit events.

## Resilience

Audit logging uses a fire-and-forget pattern. If recording an audit event fails (e.g., database error), the triggering operation still completes successfully. Failures are logged at ERROR level for operational monitoring.

## Related

- [Audit Events API](/api/audit) — API endpoint reference
- [RBAC](/features/rbac) — Who can access audit logs
