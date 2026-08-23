# Users API

## Get Current User

Retrieve the authenticated user's profile.

```
GET /api/me
```

**Required Role:** Any authenticated user

**Response:** `200 OK`

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "displayName": "Jane Smith",
  "role": "Skill_Consumer",
  "organizationId": "660e8400-e29b-41d4-a716-446655440000"
}
```

---

## List Users

Retrieve a paginated list of all users in the organization.

```
GET /api/users?page=0&size=20
```

**Required Role:** `Org_Admin`

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
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "email": "user@example.com",
      "displayName": "Jane Smith",
      "role": "Skill_Consumer",
      "enabled": true,
      "organizationId": "660e8400-e29b-41d4-a716-446655440000",
      "createdAt": "2024-01-10T08:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

**Error Responses:**

| Status | Condition |
|--------|-----------|
| 401 | Not authenticated |
| 403 | Role is not `Org_Admin` |

---

## Change User Role

Update a user's organization-level role.

```
PATCH /api/users/{userId}/role
```

**Required Role:** `Org_Admin`

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `userId` | UUID | The user's ID |

**Request Body:**

```json
{
  "role": "Catalog_Admin"
}
```

**Valid Role Values:**

`Org_Admin`, `Catalog_Admin`, `Reviewer`, `Team_Owner`, `Skill_Author`, `Skill_Consumer`, `Read_Only_Auditor`

**Response:** `200 OK`

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "displayName": "Jane Smith",
  "role": "Catalog_Admin",
  "enabled": true,
  "organizationId": "660e8400-e29b-41d4-a716-446655440000",
  "createdAt": "2024-01-10T08:00:00Z"
}
```

**Error Responses:**

| Status | Condition |
|--------|-----------|
| 400 | Invalid role value |
| 401 | Not authenticated |
| 403 | Role is not `Org_Admin` |
| 404 | User not found |

**Audit Event:** Records `user.role_changed` with previous and new role in metadata.
