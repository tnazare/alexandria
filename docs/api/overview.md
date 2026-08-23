# API Overview

The Alexandria REST API follows consistent conventions across all endpoints.

## Base URL

All API endpoints are prefixed with `/api`. Health endpoints use the Spring Actuator prefix `/actuator`.

```
https://your-alexandria-instance.example.com/api
```

## Authentication

All `/api` endpoints require a valid JWT Bearer token (except health endpoints).

Include the token in the `Authorization` header:

```
Authorization: Bearer <access_token>
```

Tokens are obtained via the OIDC authorization code flow with PKCE. See [Authentication](/features/authentication) for details.

### Error Responses

| Status | Meaning |
|--------|---------|
| 401 | Missing or invalid/expired token |
| 403 | Valid token but insufficient role |

## Pagination

List endpoints support cursor-based pagination via query parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | integer | `0` | Zero-based page number |
| `size` | integer | `20` | Items per page (max 100) |

### Paginated Response Format

```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3
}
```

## Error Format

All error responses use a consistent structure:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable description",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `UNAUTHORIZED` | 401 | Authentication required or token invalid |
| `FORBIDDEN` | 403 | Insufficient role or invalid email claim |
| `NOT_FOUND` | 404 | Requested resource does not exist |
| `VALIDATION_ERROR` | 400 | Request body validation failed |
| `CONFLICT` | 409 | Operation conflicts with current state (e.g., duplicate slug) |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

## Content Type

All request and response bodies use `application/json`.

## Organization Scoping

All data queries are automatically scoped to the authenticated user's organization. You cannot access resources belonging to a different organization.

## Rate Limiting

There is no built-in rate limiting. Deploy a reverse proxy (e.g., AWS ALB, nginx) to add rate limiting if needed.
