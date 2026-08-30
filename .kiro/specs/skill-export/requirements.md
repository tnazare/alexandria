# Requirements Document

## Introduction

This specification covers Milestone 2b of the Alexandria platform — **Skill Export**.
It lets a Skill_Consumer render a published skill's content into a target format
suited to their AI coding tool, either by copying it to the clipboard or downloading
it as a file. It also introduces the foundation for **usage event tracking** by
recording copy and export events, which later milestones use for ranking and analytics.

This milestone builds directly on the completed `skill-crud` work (skills, versions,
variants, lifecycle, visibility) and the `alexandria-first-step` foundation (auth,
RBAC, audit). The skill browser frontend already renders export/copy buttons that
call a not-yet-implemented backend endpoint; this milestone implements that endpoint
and wires the buttons to it.

It maps to **Requirement 13 (Skill Export)** and the export/copy portions of
**Requirement 17 (Usage Event Tracking)** from the `governed-skill-registry` parent spec.

## Glossary

- **Export_Format**: A target output format for rendering a skill. One of:
  `generic_markdown`, `github_copilot`, `codex`, `agents_md`, `cursor_rule`, `kiro_steering`.
- **Renderer**: A component that transforms a Skill_Version's content (canonical
  instructions or a tool-specific variant) into a specific Export_Format string.
- **Rendered_Artifact**: The string output produced by a Renderer for a given skill,
  version, and format.
- **Copy_Action**: An export where the Rendered_Artifact is placed on the user's clipboard.
- **Download_Action**: An export where the Rendered_Artifact is returned as a downloadable file.
- **Usage_Event**: An immutable record that a user viewed, copied, or exported a skill,
  capturing skill, version, user, event type, source, format, and timestamp.
- **Blocked_Skill**: A skill whose Lifecycle_Status is `Blocked`; export and copy are denied.
- **Variant_Resolution**: Selecting a Skill_Variant's instructions for the requested tool
  when one exists, otherwise falling back to the canonical instructions.

## Requirements

### Requirement 1: Export Rendering

**User Story:** As a Skill_Consumer, I want to render a skill in a format compatible with my AI coding tool, so that I can use the skill in my daily workflow.

#### Acceptance Criteria

1. WHEN a user requests an export of a skill for a supported Export_Format, THE Registry SHALL render the skill's current published Skill_Version content into that format and return the Rendered_Artifact.
2. THE Registry SHALL support the following Export_Formats: `generic_markdown`, `github_copilot`, `codex`, `agents_md`, `cursor_rule`, `kiro_steering`.
3. WHEN an export is requested for a specific version number, THE Registry SHALL render that version's content rather than the current version.
4. WHEN an export is requested with a `tool` parameter that has a matching Skill_Variant on the target version, THE Registry SHALL use the variant's instructions; otherwise THE Registry SHALL use the canonical instructions (Variant_Resolution).
5. IF an unsupported Export_Format is requested, THEN THE Registry SHALL reject the request with HTTP 400 and an error message listing the supported formats.
6. THE Registry SHALL include the skill's relevant metadata in each Rendered_Artifact according to that format's conventions (at minimum: skill name and canonical/variant instructions; and where the format supports it: description, applicability, when-to-use, limitations, examples).
7. THE Registry SHALL produce deterministic output — rendering the same skill, version, format, and tool SHALL always produce the same Rendered_Artifact.

### Requirement 2: Format Conventions

**User Story:** As a Skill_Consumer, I want each export format to follow the conventions of its target tool, so that the output drops cleanly into that tool.

#### Acceptance Criteria

1. WHEN rendering `generic_markdown`, THE Registry SHALL produce a Markdown document with the skill name as a top-level heading followed by clearly labeled sections for instructions and available metadata.
2. WHEN rendering `agents_md`, THE Registry SHALL produce an `AGENTS.md`-style fragment with the skill expressed as agent guidance.
3. WHEN rendering `cursor_rule`, THE Registry SHALL produce Cursor rule content suitable for a `.cursor/rules` file.
4. WHEN rendering `kiro_steering`, THE Registry SHALL produce a Kiro steering fragment suitable for a `.kiro/steering` Markdown file.
5. WHEN rendering `github_copilot` or `codex`, THE Registry SHALL produce a prompt-style document appropriate to that tool.
6. THE Registry SHALL sanitize rendered output so that skill content cannot break the structure of the target format (e.g. escaping or fencing content that would otherwise be interpreted as format control characters).

### Requirement 3: Export API and Copy/Download Actions

**User Story:** As a Skill_Consumer, I want to copy or download an export from the skill detail page, so that I can quickly reuse a skill.

#### Acceptance Criteria

1. WHEN a user sends a GET request to the skill export endpoint with a valid skill ID and format, THE Registry SHALL return the Rendered_Artifact as plain text with a `200` status and a content type appropriate to text output.
2. THE Registry SHALL accept an optional `version` query parameter (defaulting to the current version) and an optional `tool` query parameter for Variant_Resolution.
3. THE Registry SHALL accept a `source` indicator distinguishing a Copy_Action from a Download_Action so the resulting Usage_Event records how the export was consumed.
4. WHEN a user triggers a Copy_Action in the frontend, THE Skill_Browser_Frontend SHALL place the Rendered_Artifact on the clipboard and show a success confirmation.
5. WHEN a user triggers a Download_Action in the frontend, THE Skill_Browser_Frontend SHALL trigger a file download with a filename appropriate to the format (e.g. `AGENTS.md`, `.cursorrules`, `<slug>.md`).
6. THE export endpoint SHALL enforce the same visibility rules as skill retrieval, returning `404` when the user is not permitted to see the skill (no metadata leakage).
7. IF the requested skill or version does not exist, THEN THE Registry SHALL return HTTP 404.

### Requirement 4: Blocked Skill Enforcement

**User Story:** As a Catalog_Admin, I want blocked skills to be non-exportable, so that unsafe skills cannot be reused.

#### Acceptance Criteria

1. IF a skill's Lifecycle_Status is `Blocked`, THEN THE Registry SHALL deny export and copy requests for that skill with HTTP 409 and an error message indicating the skill is blocked.
2. WHILE a skill's Lifecycle_Status is `Deprecated`, THE Registry SHALL allow export and copy but SHALL indicate the deprecated status so the frontend can warn the user.
3. THE Registry SHALL apply blocked enforcement regardless of the requested format, version, or tool.

### Requirement 5: Usage Event Capture

**User Story:** As a Catalog_Admin, I want copy and export actions recorded as usage events, so that adoption can be measured and later used for ranking.

#### Acceptance Criteria

1. WHEN an export or copy request succeeds, THE Registry SHALL record a Usage_Event capturing: organization_id, skill_id, skill_version_id, user_id, event_type (`copy` or `export`), source, export_format, and a created_at timestamp.
2. THE Registry SHALL record Usage_Events using a fire-and-forget pattern — a failure to record a Usage_Event SHALL be logged at ERROR level and SHALL NOT fail the export request.
3. THE Registry SHALL NOT record a Usage_Event when an export request is rejected (blocked, not found, unauthorized, or invalid format).
4. THE Registry SHALL scope Usage_Events to the acting user's organization.
5. THE Registry SHALL store Usage_Events as append-only records that are not modified after creation.

### Requirement 6: Frontend Export Integration

**User Story:** As a Skill_Consumer, I want working export and copy controls on the skill detail page, so that I can act on a skill without leaving the catalog.

#### Acceptance Criteria

1. THE Skill_Browser_Frontend SHALL display copy and export controls on the skill detail page for each supported Export_Format.
2. WHEN the skill is `Blocked`, THE Skill_Browser_Frontend SHALL disable or hide export/copy controls and indicate the skill is blocked.
3. WHEN the skill is `Deprecated`, THE Skill_Browser_Frontend SHALL show a deprecation warning while still allowing export/copy.
4. WHEN a copy or export request fails, THE Skill_Browser_Frontend SHALL display a user-readable error message.
5. THE Skill_Browser_Frontend SHALL send the correct `source` (copy vs download), `format`, and optional `tool`/`version` parameters when calling the export endpoint.
