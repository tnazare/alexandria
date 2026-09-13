# Implementation Plan: MCP Client Readiness

## Overview

This plan implements two independent pieces in dependency order: MCP **transport compliance** so
real clients connect, and a **service-token minting** admin API so deployers stop hand-writing SQL.
It closes with per-client setup docs (Claude Code, Kiro, Copilot) and a green `./mvnw verify`.

Each numbered subtask is a self-contained commit. Tasks marked `*` are tests that can trail the
implementation slightly but must land before the milestone closes. Every task cites the
requirements it satisfies.

Scope is the **backend** (`alexandria-api`, `alexandria-entities`, `alexandria-service`) plus a
docs page. No schema change: the `service_tokens` table already carries every needed column.

Build/verify: `./mvnw test` (unit), `./mvnw verify` (integration, needs Docker). After changing
`alexandria-api`/`alexandria-entities`, run `./mvnw -pl alexandria-api,alexandria-entities install
-DskipTests` before service-only test runs so the service module sees fresh jars.

## Tasks

- [x] 1. Shared token hashing helper (no behavior change)
  - [x] 1.1 Extract `ServiceTokenHasher` and refactor `ServiceTokenAuthFilter` to use it
    - Create `com.alexandria.config.ServiceTokenHasher` with a static `sha256Hex(String)` (SHA-256
      via `MessageDigest` -> `HexFormat`), identical to the filter's current inline hashing.
    - Replace the inline `sha256(...)` in `ServiceTokenAuthFilter` with a call to the helper. No
      behavior change; keep `TOKEN_PREFIX = "alx_"` where it is.
    - *Files:* `backend/alexandria-service/src/main/java/com/alexandria/config/ServiceTokenHasher.java`,
      `.../config/ServiceTokenAuthFilter.java`
    - *Requirements: 6.2 (hashing parity), 9.2*

  - [x]* 1.2 Unit test for hashing parity
    - Assert `ServiceTokenHasher.sha256Hex(secret)` is stable and matches a known SHA-256 hex vector,
      and that a value hashed by the helper equals what the filter would look up for the same secret.
    - *Requirements: 6.2*

- [x] 2. MCP transport compliance — `McpResource`
  - [x] 2.1 Relax content negotiation and classify notification vs request
    - Remove `consumes`/`produces` from the `@PostMapping`. Change the handler to return
      `ResponseEntity<?>`: if the JSON-RPC message has no `id` (or a null `id`), return
      `202 Accepted` with an empty body; otherwise call `toolService.handle(...)` and return
      `200 OK` with `Content-Type: application/json` and the JSON-RPC result.
    - Keep the current-identity resolution (`AlexandriaAuthentication` read) unchanged.
    - *File:* `backend/alexandria-service/src/main/java/com/alexandria/agent/mcp/McpResource.java`
    - *Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3*

  - [x] 2.2 Add `GET`/`DELETE` -> `405`
    - Add `@GetMapping` and `@DeleteMapping` handlers on the same path returning
      `405 Method Not Allowed` (no SSE stream offered; no session teardown).
    - *File:* `McpResource.java`
    - *Requirements: 3.1, 3.2*

  - [x]* 2.3 Transport integration tests (extend `McpEndpointIntegrationTest`)
    - Add cases: notification (`notifications/initialized`, no `id`) -> `202` empty body;
      request (`initialize`/`tools/list`/`tools/call`) still returns a JSON-RPC result (regression);
      `Accept: application/json, text/event-stream` -> `200 application/json`;
      `Content-Type: application/json; charset=utf-8` not `415`; `GET`/`DELETE` -> `405`;
      unauthenticated -> `401`; malformed body -> `400`.
    - *File:* `backend/alexandria-service/src/test/java/com/alexandria/integration/McpEndpointIntegrationTest.java`
    - *Requirements: 1.1-1.4, 2.1-2.4, 3.1-3.4, 5.1, 5.3, 9.1*

- [x] 3. Service-token repository queries + DTOs + REST contract
  - [x] 3.1 Add org-scoped queries to `ServiceTokenRepository`
    - Add `findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId)` and
      `findByIdAndOrganizationId(UUID id, UUID organizationId)`. Leave `findByTokenHash` as-is.
    - *File:* `backend/alexandria-entities/src/main/java/com/alexandria/agent/ServiceTokenRepository.java`
    - *Requirements: 7.1, 7.4*

  - [x] 3.2 Add token DTOs and the `ServiceTokenAdminApi` contract
    - Under `com.alexandria.agent.admin.dto`: `CreateServiceTokenRequest(@NotBlank name, Instant
      expiresAt)`, `CreateServiceTokenResponse(id, name, tokenPrefix, scope, expiresAt, token)`
      (the only response carrying the secret), `ServiceTokenResponse(id, name, tokenPrefix, scope,
      createdBy, createdAt, expiresAt, revokedAt, lastUsedAt)` (metadata only).
    - `com.alexandria.agent.admin.ServiceTokenAdminApi` with `@RequestMapping("/api/agent/v1/service-tokens")`,
      `POST` create (`@Valid @RequestBody`), `GET` list, `DELETE /{tokenId}` revoke.
    - *Files:* `backend/alexandria-api/src/main/java/com/alexandria/agent/admin/dto/*.java`,
      `.../agent/admin/ServiceTokenAdminApi.java`
    - *Requirements: 6.2, 6.3, 6.6, 7.1*

- [ ] 4. Service-token minting service + resource
  - [ ] 4.1 Add `ServiceTokenNotFoundException` and map it to 404
    - Create the exception in `com.alexandria.agent.admin` (or reuse a common not-found) and add a
      `@ExceptionHandler` in `GlobalExceptionHandler` returning `404`.
    - *Files:* `.../agent/admin/ServiceTokenNotFoundException.java`,
      `backend/alexandria-service/src/main/java/com/alexandria/common/exception/GlobalExceptionHandler.java`
    - *Requirements: 7.5*

  - [ ] 4.2 Implement `ServiceTokenAdminService` / `Impl`
    - `create(name, expiresAt, admin)`: generate `secret = "alx_" + base64url(SecureRandom 32B)`,
      `hash = ServiceTokenHasher.sha256Hex(secret)`, `prefix = secret[0..12]`; persist a
      `ServiceTokenEntity` (admin org, name, hash, prefix, `scope="agent_read"`, `createdBy`,
      `expiresAt`); record `service_token.created` audit; return `CreateServiceTokenResponse` with
      the raw secret (once). `list(admin)`: org-scoped, map to `ServiceTokenResponse` (no
      secret/hash). `revoke(tokenId, admin)`: org-scoped load -> 404 if absent; set `revokedAt` if
      null (idempotent); record `service_token.revoked` audit.
    - *Files:* `.../agent/admin/ServiceTokenAdminService.java`, `.../agent/admin/ServiceTokenAdminServiceImpl.java`
    - *Requirements: 6.2, 6.3, 6.4, 6.5, 6.6, 7.2, 7.3, 7.6*

  - [ ] 4.3 Implement `ServiceTokenResource`
    - `@RestController implements ServiceTokenAdminApi`; each method `@PreAuthorize("hasRole('Org_Admin')")`;
      resolve current user via the same SecurityContext pattern as `SkillResource`; create -> 201,
      list -> 200, revoke -> 204.
    - *File:* `backend/alexandria-service/src/main/java/com/alexandria/agent/admin/ServiceTokenResource.java`
    - *Requirements: 6.1, 6.5, 7.1, 7.2, 8.4*

  - [ ]* 4.4 Unit tests for the service (Mockito)
    - create: entity has admin org, `agent_read` scope, hash == `ServiceTokenHasher.sha256Hex(secret)`,
      secret starts `alx_`, 12-char prefix, audit recorded, response carries raw secret. revoke:
      sets `revokedAt`; idempotent on already-revoked; 404 on missing/other-org.
    - *Requirements: 6.2, 6.3, 6.4, 7.3, 7.5, 7.6*

  - [ ]* 4.5 Security + end-to-end integration test (`ServiceTokenAdminIntegrationTest`)
    - Org_Admin create -> 201 with `token` starting `alx_`; immediately use that token as a bearer on
      `POST /api/agent/v1/skills/resolve` -> 200 (end-to-end mint->authenticate). List returns the
      token by prefix with no `token`/`tokenHash`. Revoke -> 204; same token then resolve -> 401.
      Revoke unknown id -> 404; revoke twice -> 204 (idempotent). Cross-org list/revoke -> 404.
      Non-admin (Skill_Consumer) create/list/revoke -> 403.
    - *File:* `backend/alexandria-service/src/test/java/com/alexandria/integration/ServiceTokenAdminIntegrationTest.java`
    - *Requirements: 6.1, 6.2, 6.3, 7.1-7.6, 8.4, 9.1*

- [ ] 5. Checkpoint — `./mvnw verify` green
  - Run `./mvnw verify`; confirm BUILD SUCCESS and that the existing MCP, resolver, hooks, and
    tool-search suites still pass alongside the new transport and token tests. Validation step; no
    commit unless fixes are required.
  - *Requirements: 9.1, 9.2, 9.3, 9.4*

- [ ] 6. Client setup documentation
  - [ ] 6.1 Write the MCP client setup guide
    - Add `docs/mcp-clients-setup.md` (or extend the manual-test doc) with: how to mint a service
      token via `POST /api/agent/v1/service-tokens` as Org_Admin; and per-client config for
      Claude Code (`.mcp.json`, `type:"http"`, `url`, `headers.Authorization`),
      Copilot/VS Code (`.vscode/mcp.json`, `servers`, `type:"http"`), and Kiro
      (`.kiro/settings/mcp.json`, `mcpServers`, `url`, `headers`). Use `https://<host>/api/agent/v1/mcp`.
    - *File:* `docs/mcp-clients-setup.md`
    - *Requirements: 8.1, 8.2, 8.3, 8.5*

- [ ] 7. Final checkpoint — verify + docs consistent
  - Re-run `./mvnw verify`; confirm green. Sanity-check the doc's curl and config snippets against
    the implemented endpoints (paths, response field names). Fix any drift.
  - *Requirements: (all)*

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1", "3.1"] },
    { "id": 2, "tasks": ["2.2", "3.2"] },
    { "id": 3, "tasks": ["2.3", "4.1"] },
    { "id": 4, "tasks": ["4.2"] },
    { "id": 5, "tasks": ["4.3"] },
    { "id": 6, "tasks": ["4.4", "4.5"] },
    { "id": 7, "tasks": ["5"] },
    { "id": 8, "tasks": ["6.1"] },
    { "id": 9, "tasks": ["7"] }
  ]
}
```

## Notes

- **No schema change.** The `service_tokens` table (V8) already has `name`, `token_hash`,
  `token_prefix`, `scope`, `created_by`, `expires_at`, `revoked_at`, `last_used_at`. Do NOT add a
  Flyway migration (Requirement 9.3).

- **Hashing parity is the linchpin.** The minted secret must hash (SHA-256 hex) to exactly what
  `ServiceTokenAuthFilter` looks up. Task 1 extracts the shared `ServiceTokenHasher` first so the
  service and filter can never drift; Task 4.5 proves it end to end (mint then authenticate).

- **Notification vs request = presence of `id`.** Per JSON-RPC, a message with a `method` and no
  `id` is a notification -> `202` empty. A message with an `id` is a request -> `200` result. `ping`
  carries an `id`, so it stays a normal request through `McpToolService.handle`.

- **Jackson 3.** The MCP endpoint uses `tools.jackson` (`JsonNode`), matching the resolver. Read
  `id` via `request.has("id")` / `request.get("id").isNull()`.

- **Return `application/json` explicitly.** Set `.contentType(APPLICATION_JSON)` on the request
  response so the body type is fixed regardless of the client's `Accept`, which the spec's client
  MUST accept. This is why no SSE is needed.

- **Admin path lives under `/api/agent/v1`.** `service-tokens` governs agent credentials, so it is
  grouped with the agent endpoints even though it is Org_Admin-only.

- **Test infra.** Reuse the resolver's Testcontainers setup (single shared container +
  `spring.datasource.hikari.maximum-pool-size` cap) so the added `@SpringBootTest` context doesn't
  reintroduce the connection-exhaustion failure. Each integration test keeps its own nested
  `@TestConfiguration JwtDecoderTestConfig` per the established convention.

- **MVP cut line.** Tasks 1-5 make clients able to connect with a properly minted token. Task 6
  (docs) is required for the deliverable but doesn't affect the build.
