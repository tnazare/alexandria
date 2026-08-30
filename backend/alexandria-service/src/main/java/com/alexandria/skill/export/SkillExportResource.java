package com.alexandria.skill.export;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * REST controller implementing {@link SkillExportApi}.
 *
 * <p>Requires only authentication; visibility is enforced by {@link SkillExportService} via the
 * skill load path (hidden skills 404). {@code format} and {@code source} are parsed through the
 * enums' case-insensitive {@code fromWire}, so an unknown value surfaces as an
 * {@code IllegalArgumentException} → 400 through the global handler. Blocked skills → 409.</p>
 */
@RestController
@RequiredArgsConstructor
public class SkillExportResource implements SkillExportApi {

    private static final MediaType TEXT_PLAIN_UTF8 =
            new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final SkillExportService exportService;

    @Override
    public ResponseEntity<String> exportSkill(UUID skillId, String format, Integer version,
                                              String tool, String source) {
        ExportFormat exportFormat = ExportFormat.fromWire(format);
        ExportSource exportSource = ExportSource.fromWire(source);
        AlexandriaUserDetails user = currentUser();

        String artifact = exportService.export(skillId, exportFormat, version, tool, exportSource, user);

        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF8)
                .body(artifact);
    }

    private AlexandriaUserDetails currentUser() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
