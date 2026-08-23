# Audit Events API

## List Audit Events

Retrieve a paginated list of audit events with optional filters. Results are always ordered by timestamp (newest first).

```
GET /api/audit-events?page=0&size=20
```

**Required Role:** `Org_Admin` or `Read_Only_Auditor`

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | integer | `0` | Page number (zero-based) |
| `size` | integer | `20` | Page size |
| `target_type` | string | — | Filter by entity type (e.g., `user`, `team`, `team_member`) |
| `target_id` | UUID | — | Filter by specific entity ID |
| `actor_user_id` | UUID | — | Filter by who performed the action |
| `action` | string | — | Filter by action type (e.g., `user.role_changed`) |
| `start_date` | ISO 8601 | — | Include events on or after this date |
| `end_date` | ISO 8601 | — | Include events on or before this date |

All filters combine with AND logic — adding a filter can only reduce the result set.

**Response:** `200 OK`

```json
{
  "content": [
    {
      "id": "990e8400-e29b-41d4-a716-446655440000",
      "actorUserId": "550e8400-e29b-41d4-a716-446655440000",
      "actorEmail": "admin@example.com",
      "action": "user.role_changed",
      "targetType": "user",
      "targetId": "660e8400-e29b-41d4-a716-446655440000",
      "metadata": {
        "previousRole": "Skill_Consumer",
        "newRole": "Catalog_Admin"
      },
      "createdAt": "2024-01-15T14:30:00Z"
    },
    {
      "id": "aa0e8400-e29b-41d4-a716-446655440000",
      "actorUserId": null,
      "actorEmail": null,
      "action": "user.auto_provisioned",
      "targetType": "user",
      "targetId": "550e8400-e29b-41d4-a716-446655440000",
      "metadata": {
        "email": "user@example.com",
        "assignedRole": "Skill_Consumer"
      },
      "createdAt": "2024-01-10T08:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1
}
```

**Error Responses:**

| Status | Condition |
|--------|-----------|
| 401 | Not authenticated |
| 403 | Role is not `Org_Admin` or `Read_Only_Auditor` |

## Available Actions

| Action | Description |
|--------|-------------|
| `user.auto_provisioned` | New user created on first login |
| `user.auto_provision_failed` | User creation failed |
| `user.role_changed` | User role was updated |
| `team.created` | New team was created |
| `team_member.added` | User added to a team |
| `team_member.removed` | User removed from a team |

## Available Target Types

| Target Type | Description |
|-------------|-------------|
| `user` | User entity |
| `team` | Team entity |
| `team_member` | Team membership entity |

## Example: Filter by Action and Date Range

```
GET /api/audit-events?action=user.role_changed&start_date=2024-01-01T00:00:00Z&end_date=2024-01-31T23:59:59Z
```

Returns all role change events that occurred in January 2024.
