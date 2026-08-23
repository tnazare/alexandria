---
inclusion: fileMatch
fileMatchPattern: '*Resource.java'
---

# API Design Patterns

## REST Conventions

- Resources are nouns, plural: `/api/skills`, `/api/organizations`
- Nested resources for ownership: `/api/skills/{skillId}/versions`
- Actions as sub-resources: `/api/skills/{skillId}/versions/{versionId}/publish`
- Use standard HTTP methods: GET (read), POST (create/action), PATCH (partial update), DELETE
- Use standard status codes: 200, 201, 204, 400, 401, 403, 404, 409, 422, 500

## Request/Response Models

- Use Java records for all DTOs
- Request records are validated with Jakarta Bean Validation (`@NotNull`, `@NotBlank`, `@Size`)
- Response records include only what the client needs — no entity leakage
- Use pagination for list endpoints: `page`, `size`, `sort` parameters → `Page<T>` response

## Error Responses

Consistent error body:

```json
{
  "error": "SKILL_NOT_FOUND",
  "message": "Skill with id {id} not found",
  "timestamp": "2026-01-15T10:30:00Z"
}
```

## Security

- All endpoints require authentication (except health/info)
- Authentication via OIDC (configurable identity provider)
- Use `@PreAuthorize` for authorization checks
- Organization scoping enforced before data access — never trust path params alone
- Sanitize all user-provided content (especially Markdown) before storage and rendering
