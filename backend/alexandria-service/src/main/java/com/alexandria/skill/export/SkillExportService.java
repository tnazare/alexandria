package com.alexandria.skill.export;

import com.alexandria.auth.AlexandriaUserDetails;

import java.util.UUID;

/**
 * Renders a skill version into a target {@link ExportFormat} and records a usage event.
 *
 * <p>Visibility is enforced through the existing {@code SkillService} load path (hidden skills
 * return 404); {@code Blocked} skills are denied (409). Variant resolution falls back to the
 * canonical instructions when no matching variant exists.</p>
 */
public interface SkillExportService {

    /**
     * Renders the target skill version and records a usage event on success.
     *
     * @param skillId       the skill's UUID
     * @param format        the target export format
     * @param versionNumber optional version number; null renders the current version
     * @param tool          optional variant tool identifier (validated against the supported set)
     * @param source        how the artifact is consumed (copy/download); drives the event type
     * @param user          the authenticated user
     * @return the rendered artifact
     */
    String export(UUID skillId, ExportFormat format, Integer versionNumber,
                  String tool, ExportSource source, AlexandriaUserDetails user);
}
