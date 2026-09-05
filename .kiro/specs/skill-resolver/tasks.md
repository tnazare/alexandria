# Implementation Plan: Skill Resolver

## Overview

This plan implements the Skill Resolver in dependency order: the resolver **core** (contract,
ACL-scoped candidate query, resolve, fetch, formats, reference modes, identity, risk policy) first,
then **agent authentication** (service tokens), then the **adapters** (MCP pull, hooks push), then
the Claude-native **tool-search** backend as a fast follow.

Each numbered subtask is a self-contained commit. Tasks marked `*` are tests/optional hardening that
can be deferred for a faster path to a demoable increment, but should land before the milestone
closes. Every task cites the requirements it satisfies.

Scope is the **backend** (`alexandria-api`, `alexandria-entities`, `alexandria-service`) plus the
packaged MCP server. Frontend catalog, VS Code extension, and CLI surfaces are covered by their own
specs and are out of scope here.

Build/verify: `./mvnw test` (unit), `./mvnw verify` (integration, needs Docker for Testcontainers),
`./mvnw flyway:migrate` (migrations). Run from `backend/`.

## Tasks

- [x] 1. Resolver contract + read-only resolve over full-text search
  - [x] 1.1 Add resolve/fetch DTOs and the forward-compatible envelope
    - Create Java records under `com.alexandria.agent.dto`: `ResolveRequest`, `ResolveContext`,
      `ResolveFilters`, `ResolveResponse`, `Capabilities`, `ResolveResult`, `SkillRef`,
      `Governance`, `Resolution`, `Delivery`, plus fetch records `NativeSkillResponse`,
      `NativeContent`, `NativeVariant`. Match the existing record-DTO style (e.g.
      `SkillSummaryResponse`).
    - `ResolveResult` MUST carry `kind` (String, `"skill"`), `invocation` (Object, always null),
      and `relationships` (List, always empty). `Capabilities` MUST carry
      `resolve/fetch/invoke/compose`.
    - *Files:* `backend/alexandria-api/src/main/java/com/alexandria/agent/dto/*.java`
    - *Requirements: 1.1, 1.3, 8.1, 8.3, 8.4, 8.5*

  - [x] 1.2 Add the `AgentResolverApi` REST contract interface
    - Create `com.alexandria.agent.AgentResolverApi` with `@RequestMapping("/api/agent/v1/skills")`,
      a `@PostMapping("/resolve")` returning `ResponseEntity<ResolveResponse>` with
      `@Valid @RequestBody ResolveRequest`, and a `@GetMapping("/{skillVersionId}/instructions")`
      returning `ResponseEntity<?>` with `tool` and `format` (`defaultValue = "agent_skill"`)
      request params.
    - *File:* `backend/alexandria-api/src/main/java/com/alexandria/agent/AgentResolverApi.java`
    - *Requirements: 1.1, 3.1, 3.2*

  - [x] 1.3 Add the ACL-scoped candidate projection query
    - Add `SkillResolverRepository` (in `alexandria-entities`, `com.alexandria.skill`) with
      `findVisibleCandidates(orgId, userId, isOrgAdmin, includeArchived, query, limit)` returning a
      Spring Data interface projection `CandidateProjection` (skill_id, skill_version_id, name,
      description, slug, lifecycle_status, risk_level, visibility, owner_team_id,
      deprecation_reason, score).
    - The native SQL MUST reuse the exact visibility disjunction from
      `SkillRepository.findVisibleSkills` (organization/team/private/restricted + `isOrgAdmin`
      bypass), exclude `Blocked`, exclude `Archived` unless `includeArchived`, apply
      `search_vector @@ plainto_tsquery('english', :query)`, and order by
      `ts_rank(...) DESC, updated_at DESC, s.id` with `LIMIT :limit`. It MUST NOT select any
      instruction/example/limitation body columns.
    - *File:* `backend/alexandria-entities/src/main/java/com/alexandria/skill/SkillResolverRepository.java`
    - *Requirements: 1.2, 1.4, 1.7, 2.1, 2.2, 2.3, 2.6*

  - [x] 1.4 Add `ResolverService.resolve` and a minimal `ResolverServiceImpl`
    - Create `com.alexandria.agent.ResolverService` interface with
      `ResolveResponse resolve(ResolveRequest request, AlexandriaUserDetails identity)`.
    - Implement in `ResolverServiceImpl` (`@Service @Transactional(readOnly=true)`): trim `query`
      and throw `IllegalArgumentException` (→ 400) if blank; clamp `limit` to `[1, 25]` (default 5);
      compute `isOrgAdmin = identity.role() == Role.Org_Admin`; call `findVisibleCandidates`; map
      each projection to a `ResolveResult` with `kind="skill"`, embedded `SkillRef`, `Governance`,
      derived `matchReasons` (from context/query overlap), `Delivery` (fetch href + formats),
      `invocation=null`, `relationships=[]`. Assemble the envelope with
      `capabilities(resolve=true, fetch=true, invoke=false, compose=false)` and `schemaVersion="1.0"`.
    - Reference mode / `Resolution` block is added in Task 3; for now emit a placeholder
      `Resolution` with the requested-or-default mode and the current version.
    - *File:* `backend/alexandria-service/src/main/java/com/alexandria/agent/ResolverService.java`,
      `.../agent/ResolverServiceImpl.java`
    - *Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 2.5, 8.1, 8.2, 8.6, 14.3*

  - [x] 1.5 Add `AgentResolverResource` wiring `resolve` (fetch stubbed until Task 2)
    - Create `@RestController` `AgentResolverResource implements AgentResolverApi`. Resolve the
      current identity with the same `currentUser()` pattern as `SkillResource` (read
      `AlexandriaAuthentication` from `SecurityContextHolder`). Delegate `resolve` to
      `ResolverService`. Return 400 on blank query via the global exception handler.
    - Allow `/api/agent/**` through security as authenticated (OIDC for now; service tokens land in
      Task 4).
    - *File:* `backend/alexandria-service/src/main/java/com/alexandria/agent/AgentResolverResource.java`
    - *Requirements: 1.1, 1.5, 1.6*

  - [x]* 1.6 Unit tests for resolve envelope + determinism
    - jqwik/Mockito tests (no Spring): mock `SkillResolverRepository`; assert every response has
      `capabilities.invoke=false`/`compose=false`, every result `kind="skill"`, `invocation=null`,
      `relationships=[]` (Property 10); assert candidates carry no instruction bodies (Property 3);
      assert `limit` clamps to 25 and blank query → 400; assert stable ordering/tiebreak (Property 2).
    - *Requirements: 1.4, 1.6, 1.7, 8.6, 14.3*

  - [x]* 1.7 Repository integration test for ACL parity + exclusions
    - `@SpringBootTest` + Testcontainers: seed skills across all four visibility levels + access
      grants; assert `findVisibleCandidates` returns exactly the same skill set as
      `findVisibleSkills` for the same identity (Property 1); assert Blocked never returned and
      Archived excluded unless requested; assert deterministic order.
    - *Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 1.7*

- [x] 2. Fetch with Agent Skills + native formats and governance gates
  - [x] 2.1 Add `loadVersionForFetch` and the fetch service method
    - Add `loadVersionForFetch(skillVersionId, orgId)` to `SkillResolverRepository` returning the
      `skill_versions` row joined to its `skills` row (org-scoped), exposing governance fields.
    - Add `FetchResult fetch(UUID skillVersionId, String tool, String format,
      AlexandriaUserDetails identity)` to `ResolverService`; implement the gate ladder: org-scoped
      load → visibility re-check mirroring `enforceVisibility` (404 if not visible) → `Blocked`
      (403) → select variant by `tool` else canonical fallback → render → return. Deprecated adds a
      warning (does not block).
    - *Files:* `SkillResolverRepository.java`, `ResolverServiceImpl.java`
    - *Requirements: 3.5, 4.1, 4.2, 4.3, 4.4*

  - [x] 2.2 Add the renderer strategy: `agent_skill` (default) + `alexandria_native`
    - Create `com.alexandria.agent.render.SkillFetchRenderer` interface (`format()`, `render(ctx)`).
      Implement `AgentSkillRenderer` (SKILL.md: only the six portable frontmatter fields, Alexandria
      governance + identity inside `metadata`, Markdown body from when-to-use/instructions/
      limitations/examples, wrapped in `<!-- alexandria:start ... -->` markers, sanitized) and
      `NativeRenderer` (`NativeSkillResponse`). Resolve renderers from a `Map<String,
      SkillFetchRenderer>` keyed by `format()`, defaulting to `agent_skill`.
    - Include a machine-readable deprecation warning in both formats when the skill is Deprecated.
    - *Files:* `backend/alexandria-service/src/main/java/com/alexandria/agent/render/*.java`
    - *Requirements: 3.2, 3.3, 3.4, 3.5, 3.7, 4.2, 6.2, 6.3*

  - [x] 2.3 Wire fetch through `AgentResolverResource` with content negotiation + usage event
    - Implement `fetch` in the resource: `agent_skill` → `text/markdown`, `alexandria_native` →
      JSON. On success, record a `view` usage event via `UsageEventService.record(orgId, actorId,
      skillId, skillVersionId, "view", source, null)` with source `"rest"`; never fail the request
      if recording fails. On Blocked denial, record an audit event
      (`skill.resolver_fetch_denied`) via `AuditService`.
    - *File:* `AgentResolverResource.java`, `ResolverServiceImpl.java`
    - *Requirements: 4.1, 4.5, 13.2, 13.3*

  - [x]* 2.4 Unit + integration tests for fetch gates and formats
    - Unit (jqwik/Mockito): gate ladder matrix {not-visible→404, Blocked→403, Deprecated→200+warning,
      OK→200} (Property 4); variant fallback with/without a matching variant (Property 5);
      frontmatter compliance — parse rendered SKILL.md, assert only the six portable keys at top
      level and identity/governance under `metadata` (Property 7); identity present in both formats
      + markers present (Property 6); sanitization neutralizes script/HTML.
    - Integration: fetch same version as `agent_skill` and `alexandria_native`; Blocked skill 403;
      Deprecated warns; not-visible 404; usage event written.
    - *Requirements: 3.3, 3.5, 3.7, 4.1, 4.2, 4.3, 4.4, 4.5, 6.1, 6.2*

- [x] 3. Reference modes + embedded identity + admin commit policy
  - [x] 3.1 Add version-resolution queries for floating modes
    - Add `findLatestApprovedVersionId(skillId)` (latest Company_Approved else Team_Approved) and
      `findLatestPublishedVersionId(skillId)` (latest Published-or-higher) to
      `SkillResolverRepository`, deriving approval state from lifecycle status + version publish
      timestamps. Finalize the exact projection against the lifecycle model.
    - *File:* `SkillResolverRepository.java`
    - *Requirements: 5.1, 5.2, 5.3, 5.4, 5.6*

  - [x] 3.2 Add `resolver_policies` + `service_tokens` migration and entities
    - Create `V8__skill_resolver.sql` (after V7) with the `service_tokens` and `resolver_policies`
      tables exactly as specified in the design (org-scoped, CHECK constraints, unique indexes).
      Add `ServiceTokenEntity` and `ResolverPolicyEntity` (both extend `BaseEntity`) and their
      repositories `ServiceTokenRepository`, `ResolverPolicyRepository`.
    - *Files:*
      `backend/alexandria-entities/src/main/resources/db/migration/V8__skill_resolver.sql`,
      `.../agent/entity/ServiceTokenEntity.java`, `.../agent/entity/ResolverPolicyEntity.java`,
      `.../agent/ServiceTokenRepository.java`, `.../agent/ResolverPolicyRepository.java`
    - *Requirements: 7.1, 9.5*

  - [x] 3.3 Add `ResolverPolicy` component and wire the `Resolution` block
    - Create `com.alexandria.config.ResolverPolicy` (`@Component`, mirroring `SkillSecurity`): given
      risk level, requested reference mode, and the org's `ResolverPolicyEntity` (or code default —
      permissive low/medium, pin-and-revalidate high/restricted), compute the effective `Resolution`
      (force `pinned` + `requiresRevalidation=true` under pin_and_revalidate; `commitAllowed=false`
      under block_commit; resolve floating modes via 3.1; exclude skills with no eligible approved
      version). Default reference mode `floating_latest_approved`.
    - Replace the placeholder `Resolution` in `resolve` (Task 1.4) and populate it in `fetch`.
      Ensure `source_instance` comes from config (`ALEXANDRIA_INSTANCE_URL`), never hardcoded.
    - *Files:* `backend/alexandria-service/src/main/java/com/alexandria/config/ResolverPolicy.java`,
      `ResolverServiceImpl.java`
    - *Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 6.4, 7.2, 7.3, 7.4, 7.5*

  - [x]* 3.4 Tests for reference-mode resolution and risk policy
    - Integration: seed a skill with Draft/Published/Team_Approved/Company_Approved versions; assert
      each mode resolves to the correct version and that a skill with no approved version is excluded
      under floating_latest_approved (Property 8).
    - Unit (jqwik): risk matrix {low,medium,high,restricted} × {permissive, pin_and_revalidate,
      block_commit, unconfigured} asserting effective mode + commit flags (Property 9); identity
      present with `source_instance` from config.
    - *Requirements: 5.1–5.6, 6.4, 7.2, 7.3, 7.4*

- [x] 4. Agent authentication (user OIDC + service tokens)
  - [x] 4.1 Add `ServiceTokenAuthFilter` and register it in `SecurityConfig`
    - Create `com.alexandria.config.ServiceTokenAuthFilter` (`OncePerRequestFilter`): if the
      `Authorization: Bearer` value matches the service-token format (e.g. `alx_` prefix), SHA-256
      hash it, look up by `token_hash`, reject if `revoked_at`/`expires_at` fail, update
      `last_used_at`, and set an `AlexandriaAuthentication` carrying an `AlexandriaUserDetails` with
      a read-only agent role and the token's `organizationId`. Otherwise pass through untouched so
      the JWT resource server + `UserProvisioningFilter` handle OIDC.
    - Register before/around the bearer-token filter so JWTs still work. Do not weaken existing OIDC.
    - *Files:* `backend/alexandria-service/src/main/java/com/alexandria/config/ServiceTokenAuthFilter.java`,
      `.../config/SecurityConfig.java`
    - *Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6*

  - [x]* 4.2 Security tests for scoping, revocation, and non-leakage
    - `@SpringBootTest` + spring-security-test: service token for org A cannot resolve/fetch org B
      skills (Property 11); revoked/expired tokens → 401; agent principal cannot reach
      authoring/review endpoints; resolve as non-member returns no other team's private/team skills;
      fetch of a non-visible skill → 404.
    - *Requirements: 9.2, 9.4, 9.5, 9.6, 2.4, 4.3*

- [x] 5. Checkpoint — core + auth compiles, migrations apply, tests green
  - Run `./mvnw verify`; confirm BUILD SUCCESS and that V8 applies cleanly after V7 in a fresh
    Testcontainers boot. Confirm resolve + fetch work end-to-end via MockMvc under both a user JWT
    and a service token. Validation step; no new commit unless fixes are required.
  - *Requirements: (all of 1–4, 13.4)*

- [x] 6. MCP server adapter (pull mode) — MVP integration
  - [x] 6.1 Implement the MCP server with `resolve_skill` + `fetch_skill`
    - Under `com.alexandria.agent.mcp`, expose two MCP tools backed by `ResolverService`. Inputs for
      `resolve_skill` mirror `ResolveRequest`; `fetch_skill` takes `skillVersionId` + optional
      `tool`/`format`. Write detailed, keyword-rich tool descriptions. Pass the caller credential
      through to produce the correct `AlexandriaUserDetails`. Advertise only the two tool definitions
      up front (no catalog preload).
    - *Files:* `backend/alexandria-service/src/main/java/com/alexandria/agent/mcp/*.java`
    - *Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.7*

  - [x] 6.2 Package the MCP server for a deployed instance
    - Make the MCP server runnable against a configurable Alexandria instance URL + credential
      (env-var driven), consistent with the self-hosted model. Document the config.
    - *Requirements: 10.6, 13.5*

  - [x]* 6.3 MCP round-trip integration test + manual harness validation
    - NOTE: automated round-trip integration test done (McpEndpointIntegrationTest). Manual
      validation in a live Claude Code / Copilot session is pending a running instance +
      those clients (cannot be automated here).
    - Integration: `resolve_skill` then `fetch_skill` end-to-end with identity passthrough; assert
      governance gates observed (Blocked denied, not-visible 404) and no bodies in resolve.
    - Manual: validate in Claude Code and Copilot/VS Code that the agent resolves then fetches a
      governed skill without the catalog being preloaded.
    - *Requirements: 10.1, 10.2, 10.3, 10.5, 10.7*

- [x] 7. Hooks push adapter (deterministic injection)
  - [x] 7.0 Confirm the default trigger behavior with stakeholders
    - DECIDED: default = UserPromptSubmit/userPromptSubmitted injection (admin-configurable via
      alexandria.resolver.hook-inject-on-prompt); sessionStart seeding available as the lighter
      alternative when disabled.
    - Confirm the admin-configurable default (recommended: `sessionStart` seeding + `UserPromptSubmit`
      injection) before building. Record the decision in the adapter config.
    - *Requirements: 11.9*

  - [x] 7.1 Implement the hook endpoint with payload parsing for both harnesses
    - Under `com.alexandria.agent.hook`, expose the hook HTTP endpoint. Parse Copilot
      (`userPromptSubmitted`, VS Code snake_case shape) and Claude Code (`UserPromptSubmit`
      PascalCase) payloads into a common `HookInvocation`. Return the harness-appropriate injection
      field (`additionalContext` / `modifiedTransformedPrompt`).
    - *Files:* `backend/alexandria-service/src/main/java/com/alexandria/agent/hook/*.java`
    - *Requirements: 11.1, 11.2*

  - [x] 7.2 Add fail-open, timeout, session cache, relevance gate, and size cap
    - Relevance gate skips trivial/non-task prompts. Session cache keyed by `(sessionId, promptHash)`.
      On miss, call `ResolverService.resolve` with a client timeout well under the harness limit
      (`RESOLVER_HOOK_TIMEOUT_MS`, default 5000 < Claude Code's 30s), take the top candidate, fetch
      `agent_skill`, and size-cap the injection (`RESOLVER_HOOK_MAX_INJECT_BYTES`, default 8192).
      Any error/timeout/unreachable → empty decision (fail-open).
    - *Requirements: 11.3, 11.4, 11.5, 11.6, 11.7, 13.5*

  - [x] 7.3 Add `preToolUse`/`PreToolUse` governance enforcement
    - NOTE: hook point implemented; defaults to no-decision (allow) since this build has no
      signal tying an arbitrary tool call to a specific skill. Deny wiring is ready for when
      such a signal exists.
    - Support a mode on tool-use hooks that denies or annotates a tool call associated with a
      `Blocked` or unsafe skill.
    - *Requirements: 11.8*

  - [x]* 7.4 Tests for fail-open, cap, cache, and governance-deny
    - Unit/integration: simulate timeout/unreachable → empty decision; success → injection within
      byte cap (Property 12); relevance gate skips trivial prompts; session cache prevents duplicate
      resolves; preToolUse denies a Blocked-skill tool call.
    - *Requirements: 11.3, 11.4, 11.5, 11.6, 11.7, 11.8*

- [x] 8. Claude-native tool_reference backend (fast follow)
  - [x] 8.1 Implement the tool-search adapter
    - Under `com.alexandria.agent.toolsearch`, return Claude `tool_reference`-style discovery results
      from a resolve query, enforcing the same ACL/governance/org scoping as the core.
    - *Files:* `backend/alexandria-service/src/main/java/com/alexandria/agent/toolsearch/*.java`
    - *Requirements: 12.1, 12.2*

  - [x]* 8.2 Integration test for deferred-load discovery
    - Assert an SDK-style discovery query returns references only for ACL-visible, non-Blocked
      skills, and that fetch of a discovered reference obeys the gates.
    - *Requirements: 12.1, 12.2*

- [ ] 9. Cross-cutting hardening — observability + config
  - [ ] 9.1 Add OpenTelemetry spans and finalize env-var config
    - Add spans for resolve/fetch (latency + result counts) consistent with the platform approach.
      Surface all resolver config via a `@ConfigurationProperties` bean (instance URL, max limit,
      hook timeout/byte caps, risk policy defaults) — no source edits needed to deploy.
    - *Requirements: 13.1, 13.5*

  - [ ]* 9.2 Non-functional latency smoke (tagged)
    - Tagged integration test: resolve p95 < 500ms and fetch p95 < 300ms on a seeded catalog reusing
      existing indexes; assert graceful fallback to FTS when semantic search is not configured.
    - *Requirements: 14.1, 14.2, 14.4*

- [ ] 10. Final checkpoint — `./mvnw verify` green, adapters validated
  - Run the full backend verify. Confirm resolve/fetch, both auth paths, the MCP round trip, and the
    hook fail-open path all pass. Fix any failures introduced by this milestone.
  - *Requirements: (all)*

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3"] },
    { "id": 2, "tasks": ["1.4"] },
    { "id": 3, "tasks": ["1.5", "1.6", "1.7"] },
    { "id": 4, "tasks": ["2.1"] },
    { "id": 5, "tasks": ["2.2"] },
    { "id": 6, "tasks": ["2.3", "2.4"] },
    { "id": 7, "tasks": ["3.1", "3.2"] },
    { "id": 8, "tasks": ["3.3"] },
    { "id": 9, "tasks": ["3.4", "4.1"] },
    { "id": 10, "tasks": ["4.2", "5"] },
    { "id": 11, "tasks": ["6.1"] },
    { "id": 12, "tasks": ["6.2", "6.3"] },
    { "id": 13, "tasks": ["7.0", "7.1"] },
    { "id": 14, "tasks": ["7.2", "7.3"] },
    { "id": 15, "tasks": ["7.4", "8.1"] },
    { "id": 16, "tasks": ["8.2", "9.1"] },
    { "id": 17, "tasks": ["9.2", "10"] }
  ]
}
```

## Notes

- **MVP cut line:** Tasks 1–6 deliver the MVP (resolver core + auth + MCP pull adapter), proving the
  thesis end to end in Claude Code and Copilot. Tasks 7–8 (hooks push, tool_reference) are the next
  increments and can ship after the MVP is validated. Task 9 hardening folds in throughout but is
  listed separately so it is not forgotten.

- **ACL parity is duplicated SQL, guarded by a test:** `findVisibleCandidates` (1.3) intentionally
  copies the visibility disjunction from `findVisibleSkills` rather than sharing SQL, because the
  projection and LIMIT semantics differ. Task 1.7 asserts the two return the same skill set for the
  same identity so the copies cannot drift silently. If they drift, that test fails.

- **`isOrgAdmin` stays narrow:** `ResolverServiceImpl` computes
  `isOrgAdmin = identity.role() == Role.Org_Admin` (not `.isAdmin()`), matching
  `SkillServiceImpl.list`. Only Org_Admins bypass visibility for resolve; Catalog_Admins do not.

- **Anti-enumeration on fetch:** a not-visible skill returns **404**, not 403 (mirroring
  `enforceVisibility`). Blocked-but-visible returns **403**. This asymmetry is deliberate
  (Requirement 4.1/4.3).

- **Service-token principal reuse:** `ServiceTokenAuthFilter` produces the same
  `AlexandriaUserDetails` type as the OIDC path, so every downstream ACL check
  (`findVisibleCandidates`, visibility re-check, `ResolverPolicy`) works unchanged. The agent role
  sits at or below `Skill_Consumer` so authoring/review/approve is structurally impossible.

- **Reserved envelope fields are inert:** `kind` is always `"skill"`, `invocation` always null,
  `relationships` always empty, `capabilities.invoke/compose` always false. They exist only so a
  future orchestration spec can populate them without a breaking wire change. No code branches on
  them in this spec.

- **`view` usage type:** fetch records the `view` usage event type, which `UsageEventService`
  already reserves (only `copy`/`export` are written today). This is the first writer of `view`.

- **Filters mirror `findVisibleSkills` composition:** the `ResolveFilters` lifecycle/risk/tool
  predicates are added to `findVisibleCandidates` the same way `findVisibleSkills` composes optional
  `:param IS NULL OR ...` clauses. They are AND-only and never widen access (Requirement 2.5).

- **V8 migration ordering:** `V8__skill_resolver.sql` runs after V7 (`rebuild_search_vectors`). It
  only creates new tables (no data backfill), so it is safe on both empty and populated databases.

- **Hook timeout must beat the harness:** `RESOLVER_HOOK_TIMEOUT_MS` (default 5000) must stay well
  under Claude Code's 30s prompt-hook timeout so the adapter never causes a fail-closed prompt
  block. The adapter itself always fails open.
