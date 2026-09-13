# Requirements Document

## Introduction

This specification covers **MCP Client Readiness** — the follow-on work needed to connect real MCP
clients (Claude Code, AWS Kiro, and GitHub Copilot / VS Code) to the Alexandria Skill Resolver's
MCP endpoint. The Skill Resolver spec delivered a working JSON-RPC MCP endpoint
(`POST /api/agent/v1/mcp`) with `initialize` / `tools/list` / `tools/call` for `resolve_skill` and
`fetch_skill`, verified by an automated round-trip test. However, two gaps stand between "works in
curl" and "a real MCP client connects":

1. **Transport compliance.** The endpoint is a plain request/response JSON POST with strict
   content-type negotiation (`consumes`/`produces = application/json`) and it returns a JSON-RPC
   result for notifications. The MCP Streamable HTTP transport requires the endpoint to accept the
   client's `Accept: application/json, text/event-stream` request, to return **HTTP 202 with no
   body** for JSON-RPC notifications and responses, and to answer a `GET` with either an SSE stream
   or `405 Method Not Allowed`. A strict client can refuse the handshake against the current
   endpoint.

2. **Service-token provisioning.** All three clients authenticate to a remote MCP server with a
   static bearer header, so the correct credential is a long-lived Alexandria service token
   (`alx_...`), not an hourly OIDC user token. Today a service token can only be created by writing
   a row directly into the database. There is no API to mint one, which makes real client setup
   impractical for a deployer.

This spec closes both gaps: it makes the MCP endpoint conform to the Streamable HTTP transport for
the request/response (non-streaming) case that a tool server needs, and it adds an admin API to
mint, list, and revoke service tokens with the secret revealed exactly once at creation. Server-Sent
Events streaming and stateful sessions are explicitly **not** required, because the resolver's tools
are synchronous request/response calls and the MCP spec permits a server to answer with a single
`application/json` object.

**Deployment model.** Alexandria is not installed locally on developer machines. It is deployed
inside the customer's VPC (ECS Fargate or Kubernetes behind an ALB) and reached over the network by
MCP clients — Claude Code, Kiro, and Copilot — that connect as remote HTTP MCP servers and send no
browser `Origin`. Transport security is provided by TLS termination at the load balancer, the
mandatory bearer-token authentication already enforced on every request, and organization-scoped
ACL. Consequently the transport-spec mitigations aimed at locally-bound servers (DNS-rebinding
`Origin` validation, localhost binding) do not apply and are not part of this spec.

This work builds directly on the existing implementation: the `McpResource` / `McpToolService`
endpoint, the `ServiceTokenEntity` / `ServiceTokenRepository` and `service_tokens` table, the
`ServiceTokenAuthFilter`, and the established `*Api` interface + `@PreAuthorize` resource convention
used by `UserResource` and `TaxonomyResource`.

## Glossary

- **MCP_Endpoint**: The single HTTP path `POST /api/agent/v1/mcp` that speaks JSON-RPC 2.0 for the
  Model Context Protocol.
- **Streamable_HTTP**: The MCP transport (protocol revision 2025-06-18) over HTTP POST/GET, in which
  the server MAY use SSE but MAY instead answer a request with a single JSON object.
- **JSON_RPC_Request**: A JSON-RPC 2.0 message carrying an `id` and a `method` that expects a result.
- **JSON_RPC_Notification**: A JSON-RPC 2.0 message with a `method` and **no** `id`, expecting no
  result (e.g. `notifications/initialized`).
- **MCP_Client**: An agent harness that connects to the MCP_Endpoint — Claude Code, AWS Kiro, or
  GitHub Copilot / VS Code.
- **Service_Token**: A scoped, non-interactive credential (prefix `alx_`) identifying an agent,
  stored as a SHA-256 hash, org-scoped, revocable, read-only (`agent_read` scope).
- **Token_Secret**: The full opaque secret string of a Service_Token, shown to the caller exactly
  once at creation and never retrievable afterward (only its hash is stored).
- **Token_Prefix**: A short, non-secret leading fragment of a Service_Token retained for display and
  identification in listings.
- **Org_Admin**: The administrative role permitted to mint and manage Service_Tokens.

## Requirements

### Requirement 1: MCP Content Negotiation

**User Story:** As an MCP client, I want to POST a JSON-RPC message with an
`Accept: application/json, text/event-stream` header and receive a valid response, so that my
Streamable HTTP handshake with Alexandria succeeds.

#### Acceptance Criteria

1. WHEN an MCP_Client sends `POST /api/agent/v1/mcp` with `Content-Type: application/json` and an
   `Accept` header listing `application/json` and/or `text/event-stream`, THE MCP_Endpoint SHALL
   accept the request and SHALL NOT reject it with `406 Not Acceptable` or `415 Unsupported Media
   Type`.
2. WHEN the MCP_Endpoint answers a JSON_RPC_Request, THE MCP_Endpoint SHALL respond with
   `Content-Type: application/json` and a single JSON-RPC response object.
3. THE MCP_Endpoint SHALL tolerate a `Content-Type` that includes parameters (e.g.
   `application/json; charset=utf-8`) without rejecting the request.
4. THE MCP_Endpoint SHALL NOT require the client to send `text/event-stream`; a client that sends
   only `application/json` in `Accept` SHALL also be served.
5. THE MCP_Endpoint SHALL preserve all existing JSON-RPC behavior for `initialize`, `tools/list`,
   and `tools/call` (including `resolve_skill` and `fetch_skill`) unchanged from the Skill Resolver
   spec, other than the transport-level concerns in this document.

### Requirement 2: Notification and Response Handling

**User Story:** As an MCP client, I want notifications I send (which expect no reply) to be
acknowledged correctly, so that my client does not treat the acknowledgement as a protocol error.

#### Acceptance Criteria

1. WHEN the MCP_Endpoint receives a JSON_RPC_Notification (a message with no `id`), THE
   MCP_Endpoint SHALL respond with HTTP `202 Accepted` and an empty body.
2. WHEN the MCP_Endpoint receives `notifications/initialized`, THE MCP_Endpoint SHALL treat it as a
   JSON_RPC_Notification and respond with `202 Accepted` and no body (not a JSON-RPC result object).
3. WHEN the MCP_Endpoint receives a JSON_RPC_Request (a message with an `id`), THE MCP_Endpoint
   SHALL respond with `200 OK` and a JSON-RPC response object carrying the same `id`.
4. IF the MCP_Endpoint cannot parse the POST body as a JSON-RPC message, THEN THE MCP_Endpoint
   SHALL respond with `400 Bad Request` and MAY include a JSON-RPC error object with a null `id`.

### Requirement 3: GET and Method Handling

**User Story:** As an MCP client that probes for an SSE stream, I want a clear, spec-compliant
answer, so that I fall back to request/response mode instead of hanging or erroring.

#### Acceptance Criteria

1. WHEN an MCP_Client issues `GET /api/agent/v1/mcp`, THE MCP_Endpoint SHALL respond with
   `405 Method Not Allowed`, indicating it does not offer a server-initiated SSE stream at this
   endpoint.
2. WHEN an MCP_Client issues `DELETE /api/agent/v1/mcp` (session teardown), THE MCP_Endpoint SHALL
   respond with `405 Method Not Allowed`, indicating it does not manage stateful sessions.
3. THE MCP_Endpoint SHALL remain stateless: it SHALL NOT require an `Mcp-Session-Id` header on any
   request, and SHALL NOT reject a request for lacking one.
4. WHERE the MCP_Endpoint chooses not to issue an `Mcp-Session-Id`, THE MCP_Endpoint SHALL still
   function correctly for clients that support the stateless (no-session) mode, which is
   spec-permitted.

### Requirement 4: Protocol Version Handling

**User Story:** As an MCP client, I want the endpoint to interoperate across protocol revisions, so
that my handshake succeeds regardless of the exact revision I advertise.

#### Acceptance Criteria

1. WHEN an MCP_Client includes an `MCP-Protocol-Version` header, THE MCP_Endpoint SHALL accept the
   request; it MAY use the value but SHALL NOT reject a request solely because the header is absent.
2. WHEN an MCP_Client omits the `MCP-Protocol-Version` header, THE MCP_Endpoint SHALL continue to
   process the request (assuming a compatible default), consistent with the transport spec.

> **Deployment note — no DNS-rebinding / Origin validation.** The MCP transport spec's guidance to
> validate the `Origin` header and bind to localhost targets servers running **locally on a
> developer's machine**, where a malicious web page in the user's browser could reach
> `127.0.0.1`. Alexandria is not installed locally: it is deployed inside the customer's VPC
> (ECS Fargate / Kubernetes behind an ALB) and reached by CLI/agent MCP clients that send no
> `Origin` header. The DNS-rebinding threat model therefore does not apply. Transport security for
> this deployment is provided by TLS termination at the load balancer, mandatory bearer-token
> authentication on every request (Requirement 5), and organization-scoped ACL. Origin validation
> is intentionally **not** a requirement of this spec.

### Requirement 5: Authentication Unchanged for the MCP Endpoint

**User Story:** As a security-conscious operator, I want the transport changes to leave the
endpoint's authentication and ACL intact, so that opening it to real clients does not weaken access
control.

#### Acceptance Criteria

1. THE MCP_Endpoint SHALL continue to require authentication (OIDC user token or Service_Token) for
   all JSON-RPC methods except the transport-level acknowledgements defined here; an unauthenticated
   request SHALL receive `401 Unauthorized`.
2. THE MCP_Endpoint SHALL continue to enforce per-identity ACL and governance through the resolver
   core, unchanged.
3. THE transport changes SHALL NOT introduce any unauthenticated data-returning path; `GET` returns
   `405` and notifications return `202` with no body, neither of which exposes skill data.

### Requirement 6: Service-Token Minting API

**User Story:** As an Org_Admin, I want to create a service token through an API and receive its
secret once, so that I can configure MCP clients without writing database rows by hand.

#### Acceptance Criteria

1. THE Registry SHALL expose an authenticated endpoint to create a Service_Token, restricted to the
   `Org_Admin` role; a caller without that role SHALL receive `403 Forbidden`.
2. WHEN an Org_Admin creates a Service_Token with a human-readable name and an optional expiry, THE
   Registry SHALL generate a cryptographically strong Token_Secret with the `alx_` prefix, store
   only its SHA-256 hash plus a non-secret Token_Prefix, associate it with the caller's
   organization, set its scope to `agent_read`, and record the creator.
3. WHEN a Service_Token is created, THE Registry SHALL return the full Token_Secret in the creation
   response exactly once, along with the token's id, name, prefix, scope, and expiry.
4. THE Registry SHALL NOT store the raw Token_Secret and SHALL NOT return it again on any subsequent
   request; only the Token_Prefix and metadata SHALL be retrievable afterward.
5. WHEN a Service_Token is created, THE Registry SHALL record an Audit_Event capturing the actor,
   the token id, and the action, consistent with the platform's audit approach.
6. THE Registry SHALL require a non-blank name for a Service_Token and SHALL reject a create request
   with a blank name with `400 Bad Request`.

### Requirement 7: Service-Token Listing and Revocation

**User Story:** As an Org_Admin, I want to see and revoke the service tokens in my organization, so
that I can manage and retire agent credentials.

#### Acceptance Criteria

1. THE Registry SHALL expose an authenticated, `Org_Admin`-restricted endpoint to list the
   Service_Tokens in the caller's organization, returning for each: id, name, prefix, scope,
   created-at, created-by, expiry, revoked-at, and last-used-at — but never the Token_Secret or its
   hash.
2. THE Registry SHALL expose an authenticated, `Org_Admin`-restricted endpoint to revoke a
   Service_Token by id, setting its revoked-at timestamp.
3. WHEN a Service_Token is revoked, THE Registry SHALL cause subsequent authentication with that
   token to fail (the existing `ServiceTokenAuthFilter` already rejects revoked tokens), and SHALL
   record an Audit_Event for the revocation.
4. WHEN an Org_Admin attempts to list or revoke tokens belonging to a different organization, THE
   Registry SHALL scope the operation to the caller's organization so that cross-organization
   tokens are neither listed nor revocable.
5. IF a revoke request targets a token id that does not exist in the caller's organization, THEN THE
   Registry SHALL respond with `404 Not Found`.
6. THE revoke operation SHALL be idempotent: revoking an already-revoked token SHALL succeed without
   error and leave the original revoked-at timestamp in place.

### Requirement 8: Client Connection Compatibility

**User Story:** As a developer setting up Claude Code, Kiro, or Copilot, I want Alexandria to
connect using each client's standard remote-MCP configuration, so that I can wire it up without
custom glue.

#### Acceptance Criteria

1. THE MCP_Endpoint SHALL be connectable by Claude Code configured with a `type: "http"` server
   entry whose `url` is the MCP_Endpoint and whose `headers.Authorization` carries a
   `Bearer <Token_Secret>`.
2. THE MCP_Endpoint SHALL be connectable by GitHub Copilot / VS Code configured with a `servers`
   entry of `type: "http"`, a `url` of the MCP_Endpoint, and an `Authorization` header.
3. THE MCP_Endpoint SHALL be connectable by AWS Kiro configured with an `mcpServers` entry carrying
   a `url` of the MCP_Endpoint and an `Authorization` header.
4. WHEN any of these clients performs its `initialize` → `tools/list` → `tools/call` sequence
   against the configured endpoint with a valid Service_Token, THE MCP_Endpoint SHALL complete the
   sequence and return governed resolve/fetch results scoped to the token's organization.
5. THE deliverables SHALL include per-client setup documentation showing the exact configuration
   for Claude Code, Kiro, and Copilot, including how to obtain a Service_Token from the minting API.

### Requirement 9: Backward Compatibility and Regression Safety

**User Story:** As a maintainer, I want the transport and token-API changes to leave every existing
capability working, so that this change is safe to ship.

#### Acceptance Criteria

1. THE existing automated MCP round-trip behavior (initialize, tools/list with exactly two tools,
   resolve_skill, fetch_skill, blocked-skill error, auth-required) SHALL continue to pass.
2. THE existing resolve/fetch REST endpoints, hooks adapter, tool-search adapter, and
   `ServiceTokenAuthFilter` behavior SHALL be unaffected by this change.
3. THE existing `service_tokens` table and `ServiceTokenEntity` SHALL be reused; IF any schema
   change is required for token management, THEN it SHALL be delivered as a new Flyway migration
   that runs after the current latest and SHALL be safe on both empty and populated databases.
4. `./mvnw verify` SHALL pass with new unit and integration tests covering transport compliance and
   the token-minting API.

## Non-Goals

The following are explicitly out of scope for this spec:

- **SSE streaming / server-initiated messages.** The resolver's tools are synchronous
  request/response calls; the endpoint answers with a single JSON object, which the transport spec
  permits. No `text/event-stream` responses, no server-push, no resumability.
- **Stateful MCP sessions.** No `Mcp-Session-Id` issuance, session store, or session expiry. The
  endpoint stays stateless.
- **OAuth 2.0 / Dynamic Client Registration for MCP.** Clients authenticate with a static
  Service_Token bearer header; the interactive OAuth discovery flow that some MCP servers implement
  is not added here.
- **A token-management UI.** Minting/listing/revoking is delivered as an API (plus docs);
  a frontend admin screen is a separate effort.
- **Rotating or auto-expiring tokens on a schedule.** Tokens support an optional expiry and manual
  revocation only.
- **Changes to the resolve/fetch contract, governance gates, reference modes, hooks, or tool-search
  behavior.** Those are unchanged from the Skill Resolver spec.
- **The older HTTP+SSE (2024-11-05) dual-endpoint compatibility mode.** Only the single Streamable
  HTTP endpoint (request/response case) is provided.
- **DNS-rebinding / `Origin` validation and localhost binding.** These transport-spec mitigations
  target servers running locally on a developer's machine. Alexandria is deployed in the customer's
  VPC and reached by CLI/agent clients that send no `Origin`, so the threat model does not apply
  (see the deployment note under Requirement 4). Transport security is TLS at the load balancer plus
  mandatory bearer-token auth and org-scoped ACL.
