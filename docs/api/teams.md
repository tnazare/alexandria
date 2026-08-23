# Teams API

## Create Team

Create a new team within the organization.

```
POST /api/teams
```

**Required Role:** `Org_Admin` or `Catalog_Admin`

**Request Body:**

```json
{
  "name": "Platform Engineering",
  "description": "Core platform team responsible for shared infrastructure"
}
```

**Validation Rules:**

| Field | Rules |
|-------|-------|
| `name` | Required, 2–100 characters, pattern: `[a-zA-Z0-9 \-_]` |
| `description` | Optional |

**Response:** `201 Created`

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440000",
  "name": "Platform Engineering",
  "slug": "platform-engineering",
  "description": "Core platform team responsible for shared infrastructure",
  "memberCount": 0,
  "createdAt": "2024-01-15T10:00:00Z"
}
```

**Error Responses:**

| Status | Condition |
|--------|-----------|
| 400 | Invalid team name (validation error) |
| 401 | Not authenticated |
| 403 | Insufficient role |
| 409 | Team slug already exists in this organization |

**Audit Event:** Records `team.created`.

---

## List Teams

Retrieve a paginated list of all teams in the organization.

```
GET /api/teams?page=0&size=20
```

**Required Role:** Any authenticated user

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | integer | `0` | Page number (zero-based) |
| `size` | integer | `20` | Page size |

**Response:** `200 OK`

```json
{
  "content": [
    {
      "id": "770e8400-e29b-41d4-a716-446655440000",
      "name": "Platform Engineering",
      "slug": "platform-engineering",
      "description": "Core platform team",
      "memberCount": 5,
      "createdAt": "2024-01-15T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

## Get Team Details

Retrieve a team with its full member list.

```
GET /api/teams/{teamId}
```

**Required Role:** `Org_Admin`

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `teamId` | UUID | The team's ID |

**Response:** `200 OK`

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440000",
  "name": "Platform Engineering",
  "slug": "platform-engineering",
  "description": "Core platform team",
  "members": [
    {
      "userId": "550e8400-e29b-41d4-a716-446655440000",
      "email": "jane@example.com",
      "displayName": "Jane Smith",
      "teamRole": "owner",
      "joinedAt": "2024-01-15T10:00:00Z"
    },
    {
      "userId": "880e8400-e29b-41d4-a716-446655440000",
      "email": "bob@example.com",
      "displayName": "Bob Jones",
      "teamRole": "member",
      "joinedAt": "2024-01-16T09:00:00Z"
    }
  ],
  "createdAt": "2024-01-15T10:00:00Z"
}
```

**Error Responses:**

| Status | Condition |
|--------|-----------|
| 401 | Not authenticated |
| 403 | Insufficient role |
| 404 | Team not found |

---

## Add Team Member

Add a user to a team.

```
POST /api/teams/{teamId}/members
```

**Required Role:** `Org_Admin` or team owner

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `teamId` | UUID | The team's ID |

**Request Body:**

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "teamRole": "member"
}
```

**Valid Team Roles:** `owner`, `member`

**Response:** `201 Created`

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "email": "jane@example.com",
  "displayName": "Jane Smith",
  "teamRole": "member",
  "joinedAt": "2024-01-16T09:00:00Z"
}
```

**Error Responses:**

| Status | Condition |
|--------|-----------|
| 400 | Invalid team role value |
| 401 | Not authenticated |
| 403 | Not Org_Admin or team owner |
| 404 | Team or user not found |

**Audit Event:** Records `team_member.added`.

---

## Remove Team Member

Remove a user from a team.

```
DELETE /api/teams/{teamId}/members/{userId}
```

**Required Role:** `Org_Admin` or team owner

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `teamId` | UUID | The team's ID |
| `userId` | UUID | The user to remove |

**Response:** `204 No Content`

**Error Responses:**

| Status | Condition |
|--------|-----------|
| 401 | Not authenticated |
| 403 | Not Org_Admin or team owner |
| 404 | Team or member not found |
| 409 | Cannot remove the last owner of a team |

**Audit Event:** Records `team_member.removed`.
