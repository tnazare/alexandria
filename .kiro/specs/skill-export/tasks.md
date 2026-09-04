# Implementation Plan: Skill Export

## Overview

This plan implements Milestone 2b — Skill Export — bottom-up: persistence (usage events),
then the API contract (export enum + interface), the renderers, the service orchestration,
the REST controller, and finally the frontend wiring. Property tests validate the 8
correctness properties from the design; integration tests exercise the full HTTP round-trip
per format plus the error paths. Tasks marked `*` are optional (tests) and can be deferred
for a faster path to a working endpoint.

## Tasks

- [x] 1. Persistence — usage events
  - [x] 1.1 Create V5 Flyway migration for `usage_events`
    - Create `backend/alexandria-entities/src/main/resources/db/migration/V5__usage_events.sql`
    - Table `usage_events` with columns per design; `event_type` CHECK in ('view','copy','export')
    - FKs: organization_id → organizations (RESTRICT), skill_id → skills (CASCADE),
      skill_version_id → skill_versions (SET NULL), user_id → users (SET NULL)
    - Indexes on organization_id, skill_id, and (organization_id, event_type, created_at)
    - _Requirements: 5.1, 5.4, 5.5_

  - [x] 1.2 Create UsageEventEntity
    - Create `com.alexandria.usage.entity.UsageEventEntity` in `alexandria-entities`
    - Fields: id (UUID PK), organizationId, skillId, skillVersionId, userId, eventType,
      source, exportFormat, createdAt (via `@PrePersist`)
    - Does not extend BaseEntity (no updated_at)
    - _Requirements: 5.1, 5.5_

  - [x] 1.3 Create UsageEventRepository
    - Create `com.alexandria.usage.UsageEventRepository extends JpaRepository<UsageEventEntity, UUID>`
    - Add `countBySkillIdAndEventType`, `findByOrganizationId` (for tests/analytics)
    - _Requirements: 5.1_

- [x] 2. API contract — export enum and interface
  - [x] 2.1 Create ExportFormat and ExportSource enums in alexandria-api
    - Create `com.alexandria.skill.export.ExportFormat` with 6 values, each carrying its
      wire value and download filename pattern; add `fromWire(String)` (case-insensitive,
      throws `IllegalArgumentException` listing supported formats on miss)
    - Create `com.alexandria.skill.export.ExportSource` (COPY, DOWNLOAD; `fromWire` default DOWNLOAD)
    - _Requirements: 1.2, 3.3, 1.5_

  - [x] 2.2 Create SkillExportApi interface
    - Create `com.alexandria.skill.export.SkillExportApi` with
      `@RequestMapping("/api/skills/{skillId}/export")`
    - `GET` method: `@PathVariable skillId`, `@RequestParam format`, optional
      `version`, `tool`, `source`; produces `text/plain`; returns `ResponseEntity<String>`
    - _Requirements: 3.1, 3.2_

- [x] 3. Renderers
  - [x] 3.1 Create ExportRenderModel and SkillExportRenderer strategy interface
    - Create `com.alexandria.skill.export.ExportRenderModel` record (name, slug, description,
      instructions, applicability, whenToUse, limitations, examples, tool, riskLevel) in `alexandria-service`
    - Create `SkillExportRenderer` interface: `ExportFormat format()`, `String render(ExportRenderModel)`
    - Create a small `MarkdownFence` helper that picks a backtick run longer than any run in the content
    - _Requirements: 1.6, 2.6_

  - [x] 3.2 Implement the six renderers
    - `GenericMarkdownRenderer`, `AgentsMdRenderer`, `CursorRuleRenderer`, `KiroSteeringRenderer`,
      `GithubCopilotRenderer`, `CodexRenderer` — each `@Component`, deterministic, using the
      content rules in the design; fence-guard any delimited regions
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 1.6, 1.7_

  - [x] 3.3 Create SkillExportRendererRegistry
    - `@Component` that injects `List<SkillExportRenderer>` and builds a `Map<ExportFormat, Renderer>`;
      `rendererFor(format)` throws if a format is unmapped (guards against a missing renderer)
    - _Requirements: 1.1_

  - [x]* 3.4 Write property tests for renderers (Properties 1, 2, 3, 4, 8, 9)
    - **Property 1: Format coverage** — every ExportFormat has a renderer producing non-empty output
    - **Property 2: Determinism** — same model → identical output
    - **Property 3: Content inclusion** — output contains name + resolved instructions
    - **Property 4: Variant resolution** — model carrying variant instructions renders them
    - **Property 8: Fence safety** — content with long backtick runs cannot terminate a fence early
    - **Property 9: Empty-instructions safety** — empty instructions still yield a well-formed artifact with the name
    - jqwik, pure (no Spring). **Validates: Requirements 1.2, 1.7, 1.8, 1.9, 2.6**

- [x] 4. Checkpoint — entity + renderer layer compiles, migration applies, renderer tests pass

- [x] 5. Service layer
  - [x] 5.1 Create SkillBlockedException and map it in GlobalExceptionHandler
    - Create `com.alexandria.skill.SkillBlockedException extends RuntimeException`
    - Add a handler in the existing `GlobalExceptionHandler` → 409 with `ErrorResponse`
    - _Requirements: 4.1, 4.3_

  - [x] 5.2 Create UsageEventService and UsageEventServiceImpl
    - Interface `record(orgId, actorUserId, skillId, skillVersionId, eventType, source, exportFormat)`
    - Impl persists a `UsageEventEntity`; fire-and-forget (catch + ERROR log, never throw)
    - _Requirements: 5.1, 5.2_

  - [x] 5.3 Create SkillExportService and SkillExportServiceImpl
    - `export(skillId, format, versionNumber, tool, source, user)`:
      validate `tool` against the supported set first (unknown → 400, before DB work),
      load skill via `SkillService.getById` (visibility → 404), block check (→ 409),
      resolve target version (given or current), resolve instructions via
      `SkillVariantService.resolve` with canonical fallback (empty instructions still render
      per Req 1.8), build model, render via registry, fire usage event on success with the
      resolved version id, return artifact
    - No `filenameFor` method — download filename is derived on the frontend (Req 3.5)
    - Inject `SkillService`, `SkillVariantService`, `SkillVersionRepository`,
      `SkillExportRendererRegistry`, `UsageEventService`
    - _Requirements: 1.1, 1.3, 1.4, 1.6, 1.8, 3.6, 4.1, 4.2, 5.1, 5.3_

  - [x]* 5.4 Write service tests (Properties 5, 6, 10) + variant/version wiring
    - **Property 5: Blocked denial** — blocked skill export throws for any format/version/tool and records no usage event
    - **Property 6: Usage event on success only** — success records exactly one matching event (with the resolved version id and source→event_type mapping); rejection records none
    - **Property 10: Invalid-tool rejection** — an unsupported `tool` yields 400 and records no usage event
    - Verify variant resolution and version selection wiring with Mockito
    - **Validates: Requirements 4.1, 4.3, 5.1, 5.3, 1.4, 1.6**

- [x] 6. REST layer
  - [x] 6.1 Create SkillExportResource controller
    - Implements `SkillExportApi`; authenticated users only (visibility enforced in service)
    - Parse `format`/`source` via enum `fromWire`; delegate to `SkillExportService`;
      return `text/plain` body
    - _Requirements: 3.1, 3.2, 3.3, 1.5_

  - [x]* 6.2 Write integration tests for the export endpoint (Property 7)
    - `@SpringBootTest` + Testcontainers + JWT-bypass post-processor (as in skill-crud)
    - Round-trip each of the 6 formats → 200 text/plain (charset UTF-8) with expected content
    - 400 (unknown format), 400 (unknown tool), 404 (hidden/missing skill or version), 409 (blocked)
    - Usage event rows written on copy (event_type=copy) and download (event_type=export); none written on rejection
    - **Property 7: Visibility parity** — export denied exactly when getById is denied (404, no leakage)
    - **Validates: Requirements 3.1, 3.6, 3.7, 4.1, 5.1, 5.3**

- [x] 7. Checkpoint — `./mvnw verify` green (backend)

- [x] 8. Frontend
  - [x] 8.1 Update the export API client
    - Update `frontend/src/api/skills.ts` `exportSkill` to call
      `GET /api/skills/{id}/export` with `format`, and optional `source`, `tool`, `version`;
      return the text body; keep/adjust the `ExportFormat` union to the 6 supported wire values
    - _Requirements: 3.1, 6.5_

  - [x] 8.2 Wire ExportActions on the browse SkillDetailPage
    - Copy buttons → `source=copy`, write artifact to clipboard, show confirmation
    - Download buttons → `source=download`, trigger download with the format's filename
    - Disable/hide controls and show a Blocked indicator when `lifecycleStatus === 'Blocked'`
    - Show a deprecation warning when `Deprecated`
    - Show a user-readable error on failure
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [x]* 8.3 Write frontend tests
    - Copy writes to clipboard + shows confirmation (mock `navigator.clipboard`)
    - Download triggers a file (mock URL/anchor)
    - Blocked skill disables export controls
    - Error response surfaces a message
    - _Requirements: 6.2, 6.4_

- [x] 9. Final checkpoint — backend `./mvnw verify` and frontend `npm run build && npm run test` green

## Notes

- Reuses `SkillService.getById`/`getVersion` for visibility and `SkillVariantService.resolve`
  for variant fallback — export must not re-implement access control.
- Renderers are pure and deterministic; no timestamps or randomness in output.
- Usage events follow the audit fire-and-forget pattern; export never fails because of them.
- `event_type` supports `view` at the schema level for the later usage-tracking milestone,
  but only `copy`/`export` are written here.
- Builds on V4; adds only V5. No changes to existing skill/variant/taxonomy APIs.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["1.3", "2.2", "3.1"] },
    { "id": 3, "tasks": ["3.2"] },
    { "id": 4, "tasks": ["3.3", "3.4"] },
    { "id": 5, "tasks": ["5.1", "5.2"] },
    { "id": 6, "tasks": ["5.3"] },
    { "id": 7, "tasks": ["5.4", "6.1"] },
    { "id": 8, "tasks": ["6.2"] },
    { "id": 9, "tasks": ["8.1"] },
    { "id": 10, "tasks": ["8.2"] },
    { "id": 11, "tasks": ["8.3"] }
  ]
}
```
