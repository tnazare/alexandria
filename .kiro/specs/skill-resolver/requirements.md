# Requirements Document

## Introduction

This specification covers the **Skill Resolver** — an agent-facing retrieval layer that lets AI
coding agents (Claude Code, GitHub Copilot, Cursor, Codex, and any MCP- or hook-capable harness)
query Alexandria mid-task for the single most relevant, governed, correctly-versioned skill,
injected into the agent's working context **without resetting that context**.

Today an AI coding agent has two bad options: load many skills into its context up front (context
bloat, degraded tool-selection accuracy past ~30–50 items, and no governance), or reason over an
entire catalog itself. Neither respects Alexandria's governance (ACL, lifecycle, risk, versioning),
and both get worse as the catalog grows. The Skill Resolver replaces both with a governed retrieval
primitive: the agent describes its task, Alexandria returns ranked candidates (cheap), and the agent
fetches the full instructions for the one skill it chooses (governance-gated). The efficiency thesis
is concrete — the whole catalog is never preloaded, retrieval runs server-side, and only the one
resolved skill enters context on a relevant turn, beating LLM-only retrieval on both tokens and
governance correctness, with the advantage growing as the catalog grows.

This spec is **retrieval-only** (resolve + fetch). Orchestration and autonomous execution
(agent-to-agent delegation, multi-skill composition) are explicitly out of scope, but the wire
contract is deliberately shaped so orchestration can be added later **without a breaking change**.

The resolver builds directly on the existing Phase 1 registry: the `skills` /
`skill_versions` / `skill_variants` model, the two-layer ACL (the visibility-enforced
`findVisibleSkills` query at the data layer plus `enforceVisibility` / `SkillSecurity` at the
application layer), the OIDC/JWT security stack, and the PostgreSQL `tsvector` full-text search.
It ships first on Phase 1 full-text search and improves transparently when Phase 2 semantic
search (pgvector) lands behind the same contract.

## Glossary

- **Resolver**: The agent-facing retrieval subsystem exposing two operations, `resolve` and `fetch`.
- **Resolve**: The cheap operation. Given a task query plus optional context, returns ranked skill
  **candidates** (identity, summary, governance badges, match reasons) but **no** full instructions.
- **Fetch**: The operation that returns the full, usable instruction content for one chosen skill
  version, in a requested output format, subject to governance gates.
- **Candidate**: One ranked result in a resolve response. Carries embedded identity but not the
  full skill body.
- **Agent_Skill_Format**: The output shape following the Agent Skills open standard — a `SKILL.md`
  package: YAML frontmatter (the six portable fields `name`, `description`, `license`,
  `compatibility`, `metadata`, `allowed-tools`) followed by a Markdown body. Governance data rides
  in the `metadata` map.
- **Native_Format**: Alexandria's structured JSON representation of a fetched skill version (the
  internal model: governance block + content block + variant), returned when explicitly requested.
- **Reference_Mode**: How a resolved skill is pinned over time — `pinned` (a specific
  `skill_version_id`), `floating_latest_approved` (latest approved version of the skill at
  request time), or `floating_latest_published` (latest published version at request time).
- **Embedded_Identity**: The `skill_id`, `skill_version_id`, and `source_instance` carried in every
  resolve candidate, every fetch response, and every exported artifact, so that a copied/committed
  skill can always be traced back and later revalidated.
- **Envelope**: The forward-compatible response wrapper carrying `schema_version`, `capabilities`,
  and a list of results, where each result has a `kind` discriminator, an `invocation` block
  (null in this spec), and a `relationships` array.
- **Capabilities**: An envelope-level map advertising what the instance supports (`resolve` and
  `fetch` true; `invoke` and `compose` false in this spec).
- **Pull_Mode**: Delivery where the agent's model **chooses** to call the resolver as a tool
  (via MCP). Model-driven, probabilistic firing.
- **Push_Mode**: Delivery where the agent harness **deterministically** calls the resolver at a
  lifecycle point (via a hook) and injects the result. Model-independent firing.
- **MCP_Adapter**: A Model Context Protocol server exposing `resolve_skill` and `fetch_skill` tools
  over the resolver core. The cross-vendor pull-mode surface.
- **Hook_Adapter**: An HTTP endpoint purpose-built to be called by agent lifecycle hooks
  (Copilot `userPromptSubmitted`/`preToolUse`, Claude Code `UserPromptSubmit`/`PreToolUse`),
  returning compact injectable context. The push-mode surface.
- **Service_Token**: A scoped, non-interactive credential identifying a headless/CI agent or a
  sandboxed cloud-agent, as an alternative to a user OIDC token.
- **Agent_Identity**: The authenticated principal (user via OIDC, or service via Service_Token)
  whose organization and ACL scope govern every resolve/fetch call.
- **Governance_Gate**: A lifecycle-driven access rule applied at fetch time: `Blocked` denied,
  `Deprecated` returned with a warning, `Archived` excluded from resolve by default.
- **Match_Reason**: A short, human-readable string explaining why a candidate was returned
  (e.g. "Framework matches NestJS", "Approved by Security Guild").

## Requirements

### Requirement 1: Resolve Endpoint (Ranked Candidates)

**User Story:** As an AI coding agent, I want to submit a task description and receive a short
ranked list of governed skill candidates, so that I can identify the right skill without loading
the entire catalog into my context.

#### Acceptance Criteria

1. THE Resolver SHALL expose `POST /api/agent/v1/skills/resolve` accepting a request with a
   required free-text `query`, an optional `context` object (languages, frameworks, preferred tool,
   active file path), an optional `filters` object (approval/lifecycle states, risk levels, tools),
   and an optional `limit` (default 5, clamped to a maximum of 25).
2. WHEN a resolve request is received, THE Resolver SHALL execute retrieval over the existing
   PostgreSQL full-text `search_vector` using the same relevance ordering as the registry search
   (`ts_rank` DESC), returning at most `limit` candidates.
3. THE Resolver SHALL return each candidate with: `kind` = `"skill"`, embedded identity
   (`skill_id`, `skill_version_id`, `source_instance`), `name`, `summary`, a numeric relevance
   `score`, a `governance` block (`lifecycle_status`, `risk_level`, `visibility`), a list of
   `match_reasons`, and a `delivery` block pointing to the fetch operation and listing available
   formats.
4. THE Resolver SHALL NOT include full canonical instructions, variant instructions, examples, or
   limitations in a resolve response — those are returned only by fetch.
5. WHEN a resolve query matches no visible skills, THE Resolver SHALL return HTTP 200 with an empty
   `results` array, not a 4xx or 5xx error.
6. THE Resolver SHALL trim the `query` and, IF it is blank after trimming, SHALL return HTTP 400
   with a validation error, because a resolve call must express an intent.
7. THE relevance ordering SHALL be deterministic: for identical requests against identical data,
   the ordering of candidates SHALL be stable, breaking `score` ties by `updated_at DESC` then by
   `skill_id` so no nondeterminism leaks into agent behavior.

### Requirement 2: Governance and ACL Enforcement in Resolve

**User Story:** As a security-conscious organization, I want resolve results to respect the same
ACL, visibility, and lifecycle rules as the human-facing catalog, so that an agent can never
surface a skill its operator is not allowed to see.

#### Acceptance Criteria

1. WHEN a resolve request is executed, THE Resolver SHALL enforce visibility using the same
   query-layer rules as `findVisibleSkills`: `organization` skills to any org member, `team`
   skills to owner-team members, `private` skills to their creator, and `restricted` skills to the
   creator or explicitly granted users, with `Org_Admin` bypass — all scoped to the Agent_Identity's
   `organization_id`.
2. THE Resolver SHALL exclude `Blocked` skills from all resolve results.
3. THE Resolver SHALL exclude `Archived` skills from resolve results by default, unless a
   lifecycle-state filter explicitly requests `Archived`.
4. THE Resolver SHALL NOT leak the name, summary, metadata, or existence of any skill the
   Agent_Identity is not permitted to see — a resolve response for an agent SHALL contain the same
   or a subset of the skills the same identity would see through the human catalog list.
5. WHEN the request supplies `filters` (approval/lifecycle states, risk levels, tools), THE
   Resolver SHALL apply them as additional AND predicates on top of the mandatory visibility and
   lifecycle enforcement, never as a way to widen access.
6. THE organization scope SHALL be applied at the query layer (not only in application code) so
   that cross-organization results are impossible even in the presence of an application-layer bug.

### Requirement 3: Fetch Endpoint and Output Formats

**User Story:** As an AI coding agent, I want to fetch the full instructions for the one skill I
chose, in a format my harness understands, so that I can apply the governed workflow directly.

#### Acceptance Criteria

1. THE Resolver SHALL expose `GET /api/agent/v1/skills/{skillVersionId}/instructions` accepting an
   optional `tool` query parameter (preferred variant) and an optional `format` query parameter.
2. THE `format` parameter SHALL accept `agent_skill` (default) and `alexandria_native`. WHEN
   `format` is absent, THE Resolver SHALL default to `agent_skill`.
3. WHEN `format=agent_skill`, THE Resolver SHALL render a `SKILL.md` package: YAML frontmatter
   containing only the six portable Agent Skills fields (`name`, `description`, `license`,
   `compatibility`, `metadata`, `allowed-tools`), followed by a Markdown body composed from the
   skill version's content (when-to-use, instructions, limitations, examples). Alexandria
   governance and identity data (`skill_id`, `skill_version_id`, `source_instance`,
   `lifecycle_status`, `risk_level`, `visibility`, `owner_team`, `version_number`) SHALL be placed
   in the frontmatter `metadata` map, never invented as new top-level frontmatter keys.
4. WHEN `format=alexandria_native`, THE Resolver SHALL return structured JSON containing the
   embedded identity, a `governance` block, a `content` block (canonical instructions, when-to-use,
   applicability, limitations, examples), and the selected `variant`.
5. WHEN a `tool` is supplied and a matching Skill_Variant exists for that version, THE Resolver
   SHALL use the variant's tool-specific instructions; WHEN no matching variant exists, THE Resolver
   SHALL fall back to the version's canonical instructions.
6. THE fetch response SHALL always carry the Embedded_Identity regardless of format, so that the
   returned artifact is traceable and revalidatable.
7. THE rendered Markdown/body content SHALL be sanitized so that fetched content cannot inject
   unsafe HTML or script into a consumer that renders it.

### Requirement 4: Governance Gates on Fetch

**User Story:** As a Catalog_Admin, I want fetch to enforce lifecycle governance, so that agents
cannot pull blocked skills and are warned about deprecated ones.

#### Acceptance Criteria

1. WHEN a fetch targets a skill version whose skill is `Blocked`, THE Resolver SHALL deny the
   request with HTTP 403 and SHALL NOT return any instruction content.
2. WHEN a fetch targets a skill version whose skill is `Deprecated`, THE Resolver SHALL return the
   content with HTTP 200 and SHALL include a machine-readable deprecation warning (in the native
   `governance` block and in the Agent_Skill_Format `metadata`).
3. WHEN a fetch is requested by an Agent_Identity that is not permitted to see the target skill,
   THE Resolver SHALL respond as if the skill does not exist (HTTP 404), mirroring the registry's
   anti-enumeration behavior, rather than revealing existence via 403.
4. THE Resolver SHALL re-check ACL and visibility at fetch time independently of resolve, so that a
   stale or forged skill version reference cannot bypass access control.
5. WHEN a fetch succeeds, THE Resolver SHALL record a usage event (`view`) via the existing usage
   event service, capturing skill, skill version, Agent_Identity, event source (e.g. `mcp`,
   `hook`), and timestamp, without failing the request if usage recording fails.

### Requirement 5: Reference Modes and Version Resolution

**User Story:** As a developer whose repository commits a resolved skill, I want to choose whether
I pin an exact version or always pull the latest approved one, so that I balance reproducibility
against freshness.

#### Acceptance Criteria

1. THE resolve response SHALL include a `resolution` block per result carrying a `mode` of
   `pinned`, `floating_latest_approved`, or `floating_latest_published`, a `resolved_version`
   (the concrete version number/identity resolved for this request), and a `revalidate_hint`
   (a stable reference usable by a future governance-revalidation endpoint).
2. WHEN a caller requests `floating_latest_approved` (the default for IDE/CLI callers), THE
   Resolver SHALL resolve the reference to the latest `Company_Approved` version if present,
   otherwise the latest `Team_Approved` version, at request time.
3. WHEN a caller requests `floating_latest_published`, THE Resolver SHALL resolve the reference to
   the latest `Published`-or-higher version at request time.
4. WHEN a caller requests `pinned`, THE Resolver SHALL resolve to the exact `skill_version_id`
   supplied and SHALL NOT substitute a different version.
5. THE default Reference_Mode WHEN unspecified SHALL be `floating_latest_approved`.
6. IF a floating mode resolves to no eligible version (e.g. the skill has no approved version),
   THE Resolver SHALL exclude that skill from resolve results rather than returning an unapproved
   version implicitly.

### Requirement 6: Embedded Identity in All Outputs

**User Story:** As an operator, I want every skill artifact an agent obtains to carry its
Alexandria identity, so that a committed or copied skill can be traced back and later revalidated
even after it goes stale.

#### Acceptance Criteria

1. THE Resolver SHALL embed `skill_id`, `skill_version_id`, and `source_instance` in every resolve
   candidate and every fetch response, in both output formats.
2. WHEN rendering Agent_Skill_Format, THE Resolver SHALL place the Embedded_Identity in the
   frontmatter `metadata` map.
3. WHEN the fetched content is intended to be written into a repository file, THE Resolver SHALL
   provide a delimited export marker carrying the identity and resolved version (e.g.
   `<!-- alexandria:start skill=<slug> version=<n> id=<skill_version_id> -->` … `<!-- alexandria:end -->`),
   so that future update/revalidation detection is possible.
4. THE `source_instance` SHALL be derived from the deploying organization's configured instance
   base URL (environment variable), never hardcoded.
5. THE governance-revalidation endpoint that consumes the `revalidate_hint` is explicitly OUT OF
   SCOPE for this spec; the identity SHALL nonetheless be embedded now so that revalidation can be
   added later without changing already-emitted artifacts.

### Requirement 7: Admin Commit Policy for High-Risk Skills

**User Story:** As a Catalog_Admin, I want to control how high-risk and restricted skills may be
taken out of the registry by agents, so that the most dangerous skills remain revocable.

#### Acceptance Criteria

1. THE Resolver SHALL support an organization-level admin policy governing agent handling of skills
   by `risk_level`, configurable through the admin surface and/or environment configuration.
2. WHEN a skill's `risk_level` is `high` or `restricted` and the admin policy is set to
   "pin-and-revalidate", THE Resolver SHALL force the Reference_Mode for that skill to `pinned`
   regardless of the caller's requested mode, and SHALL mark the result as requiring revalidation.
3. WHEN a skill's `risk_level` is `high` or `restricted` and the admin policy is set to
   "block-commit", THE Resolver SHALL flag in the fetch response that the artifact must not be
   committed to a repository (a machine-readable `commit_allowed: false` signal in native and in
   `metadata` for Agent_Skill_Format).
4. WHEN no admin policy is configured, THE default SHALL be permissive for `low`/`medium` risk
   (honor the caller's requested mode) and "pin-and-revalidate" for `high`/`restricted` risk.
5. THE admin commit policy SHALL be advisory metadata on the response; enforcement of "do not
   commit" on the client side is the responsibility of the consuming adapter/agent and is noted as
   such.

### Requirement 8: Forward-Compatible Response Envelope

**User Story:** As a platform maintainer, I want the resolve/fetch wire contract to leave room for
future orchestration, so that adding agent-to-agent delegation or multi-skill composition later
does not break existing agent integrations.

#### Acceptance Criteria

1. THE resolve response SHALL be wrapped in an envelope carrying `schema_version` and a
   `capabilities` map with the keys `resolve`, `fetch`, `invoke`, and `compose`.
2. THE `capabilities` map SHALL report `resolve: true`, `fetch: true`, `invoke: false`, and
   `compose: false` for this spec.
3. EACH result SHALL carry a `kind` discriminator whose value is `"skill"` in this spec, reserving
   space for future values such as `"agent"` or `"workflow"`.
4. EACH result SHALL carry an `invocation` field whose value is `null` in this spec, reserved for a
   future orchestration descriptor.
5. EACH result SHALL carry a `relationships` array (empty in this spec) reserved for future skill
   relationships (e.g. `recommended_with`, `supersedes`).
6. THE presence of the reserved fields (`invocation`, `relationships`, and the `invoke`/`compose`
   capabilities) SHALL NOT change any behavior in this spec — they are inert placeholders.

### Requirement 9: Agent Authentication (User OIDC and Service Tokens)

**User Story:** As a developer using an IDE agent and as a platform team running headless agents,
I want both interactive and non-interactive authentication paths, so that agents in every
environment can call the resolver with a correctly-scoped identity.

#### Acceptance Criteria

1. THE Resolver SHALL accept the existing OIDC/JWT user tokens for interactive callers, resolving
   the same `AlexandriaUserDetails` principal (user id, organization, role) used across the rest of
   the API.
2. THE Resolver SHALL additionally accept a scoped Service_Token for non-interactive callers
   (headless/CI agents, sandboxed cloud-agents), resolving a principal with an organization and an
   ACL scope, without requiring an interactive OIDC flow.
3. WHEN either credential is presented, THE Resolver SHALL enforce ACL and organization scoping
   using that identity at both the query layer and the application layer, identically to the
   human-facing API.
4. IF neither a valid user token nor a valid Service_Token is presented, THE Resolver SHALL deny
   the request with HTTP 401.
5. A Service_Token SHALL be revocable and SHALL carry an explicit organization association, so that
   a leaked token cannot be used against another organization's data.
6. THE Resolver SHALL NOT allow a Service_Token to escalate beyond the read/resolve/fetch scope
   defined for agent access; it SHALL NOT grant authoring, review, approval, or admin actions.

### Requirement 10: MCP Adapter (Pull Mode)

**User Story:** As a developer using Claude Code, Claude Desktop, or Copilot/VS Code, I want
Alexandria available as a standard MCP server, so that my agent can discover and call the resolver
mid-task without any custom wiring.

#### Acceptance Criteria

1. THE MCP_Adapter SHALL expose two tools, `resolve_skill` and `fetch_skill`, backed by the
   resolver core (Requirements 1–8), over the Model Context Protocol.
2. THE `resolve_skill` tool SHALL accept the same logical inputs as the resolve endpoint (query,
   optional context, optional filters, optional limit) and SHALL return ranked candidates without
   full instructions.
3. THE `fetch_skill` tool SHALL accept a chosen skill version identity plus optional tool and
   format, and SHALL return the fetched skill subject to the governance gates.
4. THE tool descriptions SHALL be detailed and keyword-rich (what the tool does, when to use it,
   what it returns) so that the agent selects them accurately, and the two tools SHALL be
   consolidated and namespaced (e.g. under an Alexandria prefix) rather than fragmented.
5. THE MCP_Adapter SHALL pass the caller's Agent_Identity (user token or Service_Token) through to
   the resolver so that ACL is enforced per identity.
6. THE MCP_Adapter SHALL be packaged to run against a deployed Alexandria instance (configurable
   instance URL and credential), consistent with the self-hosted deployment model.
7. THE MCP tool definitions SHALL be structured so that harnesses supporting deferred/tool-search
   loading do not preload skill catalog content into context; only the two tool definitions are
   present up front.

### Requirement 11: Hooks Adapter (Push Mode)

**User Story:** As a platform team, I want the right governed skill deterministically offered to
the agent at the start of a relevant task, so that discovery does not depend on the model choosing
to call a tool — while never slowing or blocking the developer's inner loop.

#### Acceptance Criteria

1. THE Hook_Adapter SHALL expose an HTTP endpoint that accepts agent lifecycle hook payloads and
   returns compact injectable context containing a resolved skill in Agent_Skill_Format.
2. THE Hook_Adapter SHALL support both the Copilot hook payload shape (e.g. `userPromptSubmitted`,
   `preToolUse`) and the Claude Code hook payload shape (e.g. `UserPromptSubmit`, `PreToolUse`),
   including their respective camelCase/PascalCase and field-naming conventions.
3. THE injectable context returned by the Hook_Adapter SHALL be size-capped (a configurable maximum,
   defaulting to a small budget suitable for prompt injection) so that push-mode injection cannot
   bloat the agent's context.
4. THE Hook_Adapter SHALL be fail-open: IF resolution errors, times out, or the instance is
   unreachable, THE hook response SHALL allow the agent turn to proceed unmodified and SHALL NOT
   block or delay the developer's prompt.
5. THE Hook_Adapter SHALL apply a client-side timeout strictly shorter than the harness hook timeout
   (which for Claude Code prompt hooks defaults to 30 seconds), so that the adapter never causes a
   fail-closed prompt block due to the harness timing out.
6. THE Hook_Adapter SHALL cache resolution results within a session/prompt scope so that repeated
   or near-identical prompts do not trigger repeated round trips.
7. THE Hook_Adapter SHALL apply a relevance gate so that trivial or clearly non-task prompts do not
   trigger a resolve round trip.
8. THE Hook_Adapter SHALL support a governance-enforcement mode on tool-use hooks (`preToolUse` /
   `PreToolUse`) that can deny or annotate a tool call associated with a `Blocked` or unsafe skill.
9. THE default push behavior SHALL be admin-configurable, offering at least: `sessionStart`
   seeding of available skill categories plus `UserPromptSubmit`/`userPromptSubmitted` injection of
   the resolved skill; the specific default trigger SHALL be confirmed at implementation time and
   SHALL be overridable by the deploying organization.

### Requirement 12: Claude-Native Tool-Search Backend (Fast Follow)

**User Story:** As a developer building a Claude agent with the Agent SDK over thousands of skills,
I want Alexandria to back Claude's native tool-search, so that skills are deferred and discovered
on demand rather than preloaded.

#### Acceptance Criteria

1. THE Resolver SHALL provide an adapter that returns Claude `tool_reference`-style discovery
   results from a resolve query, so that skills-as-tools can be deferred and loaded on demand by an
   Agent-SDK-built Claude agent.
2. THE tool-search adapter SHALL enforce the same ACL, governance gates, and organization scoping
   as the resolve/fetch core.
3. THE tool-search adapter SHALL be implemented as a fast follow AFTER the MCP_Adapter and
   Hook_Adapter, and its absence SHALL NOT block the MVP.

### Requirement 13: Observability, Audit, and Cross-Cutting Concerns

**User Story:** As an operator, I want resolver activity observable and auditable, so that I can
monitor latency, adoption, and governance decisions.

#### Acceptance Criteria

1. THE Resolver SHALL emit OpenTelemetry spans for resolve and fetch operations capturing latency
   and result counts, consistent with the platform's existing observability approach.
2. THE Resolver SHALL record usage events for fetch (and, where meaningful, resolve) via the
   existing usage event service, so that agent-driven consumption feeds analytics and ranking.
3. THE Resolver SHALL record audit events for governance-relevant resolver decisions (e.g. a
   `Blocked` fetch denial) via the existing audit service, without failing the request if audit
   recording fails.
4. ALL resolver queries SHALL apply organization scoping at the query layer, consistent with the
   platform's "org scoping enforced at query layer AND application layer" principle.
5. THE Resolver SHALL be configured entirely through environment variables (instance URL, service
   token settings, hook size/timeout budgets, admin policy defaults) with no source modification
   required for deployment.

### Requirement 14: Performance and Reliability (Non-Functional)

**User Story:** As a developer, I want resolver calls to be fast enough to sit inside my coding
loop, so that agent-driven discovery feels instant and never gets in the way.

#### Acceptance Criteria

1. FOR a catalog of up to 10,000 skills within a single organization, a resolve request with a
   free-text query and at most 3 filter dimensions SHALL complete server-side in under 500ms at the
   95th percentile on standard RDS PostgreSQL instance sizes, reusing the existing GIN and B-tree
   indexes.
2. A fetch request for a single skill version SHALL complete server-side in under 300ms at the 95th
   percentile.
3. THE resolve `limit` SHALL be clamped to a maximum of 25 candidates per request to bound response
   size and latency.
4. THE Resolver SHALL degrade gracefully: WHEN an optional capability (e.g. Phase 2 semantic
   search) is not configured, resolve SHALL fall back to full-text search rather than error.

## Non-Goals

The following are explicitly out of scope for this spec:

- **Orchestration and autonomous execution** — agent-to-agent delegation (A2A), multi-skill
  composition, and autonomous skill execution are deferred to later phases. The envelope reserves
  space for them (`kind`, `invocation`, `capabilities.invoke/compose`) but implements none.
- **Governance-revalidation endpoint** — the endpoint that consumes `revalidate_hint` to re-check
  whether a committed skill is still current/safe is deferred. Only the Embedded_Identity that
  makes it possible is implemented now.
- **Phase 2 semantic search (pgvector) internals** — hybrid embedding-based retrieval is a separate
  effort that plugs in behind the unchanged resolve contract; this spec ships on Phase 1 full-text
  search and defines the seam, not the embedding pipeline.
- **New IDE/CLI end-user UIs** — the VS Code extension and CLI surfaces are covered by their own
  Phase 2 specs; this spec delivers the backend resolver and the adapter contracts they consume.
- **Client-side enforcement of "do not commit"** — the admin commit policy is advisory metadata on
  the response; making a specific IDE refuse to write the file is the adapter/agent's concern.
- **Cross-organization resolution** — the system is single-organization; cross-org retrieval is
  never relevant.
- **Writing/authoring through agents** — the resolver is read-only (resolve + fetch). Creating,
  editing, versioning, reviewing, or approving skills via the agent surface is out of scope.
