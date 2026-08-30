package com.alexandria.skill.export;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * REST API contract for rendering (exporting) a skill into a tool-specific format.
 *
 * <p>Available to any authenticated organization user; visibility is enforced by the service
 * (hidden skills return 404). The rendered artifact is returned as {@code text/plain} so the
 * client can copy it verbatim or save it to a file.</p>
 */
@RequestMapping("/api/skills/{skillId}/export")
public interface SkillExportApi {

    /**
     * Renders the target skill version into the requested format.
     *
     * @param skillId the skill's UUID
     * @param format  the target {@link ExportFormat} wire value (required)
     * @param version optional version number; defaults to the skill's current version
     * @param tool    optional variant tool identifier for variant resolution
     * @param source  optional {@code copy} or {@code download} (default {@code download}),
     *                controlling the recorded usage event type
     * @return the rendered artifact as plain text
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> exportSkill(
            @PathVariable UUID skillId,
            @RequestParam String format,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String source
    );
}
