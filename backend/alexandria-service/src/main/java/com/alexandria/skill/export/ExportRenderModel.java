package com.alexandria.skill.export;

/**
 * Immutable, self-contained input to a {@link SkillExportRenderer}.
 *
 * <p>Assembled by the export service from the skill, the resolved target version, and the
 * resolved instructions (variant or canonical). Renderers are pure functions of this model —
 * they read nothing else — so rendering is deterministic and unit-testable without a database.</p>
 *
 * @param name          the skill's display name
 * @param slug          the skill's slug (used by the client for download filenames)
 * @param description   the skill's description (may be null/blank)
 * @param instructions  the resolved instructions (variant or canonical; may be empty)
 * @param applicability when the skill applies (may be null/blank)
 * @param whenToUse     guidance on when to use the skill (may be null/blank)
 * @param limitations   known limitations (may be null/blank)
 * @param examples      usage examples (may be null/blank)
 * @param tool          the requested variant tool identifier, or null
 * @param riskLevel     the skill's risk level, or null
 */
public record ExportRenderModel(
        String name,
        String slug,
        String description,
        String instructions,
        String applicability,
        String whenToUse,
        String limitations,
        String examples,
        String tool,
        String riskLevel
) {
}
