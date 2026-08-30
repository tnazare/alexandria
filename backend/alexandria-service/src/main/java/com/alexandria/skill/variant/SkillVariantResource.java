package com.alexandria.skill.variant;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillVariantEntity;
import com.alexandria.skill.variant.dto.CreateVariantRequest;
import com.alexandria.skill.variant.dto.VariantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for skill variant management endpoints.
 *
 * <p>All operations mutate a skill version and therefore require edit
 * authority on the parent skill, enforced via {@code @skillSecurity.canEdit}.</p>
 */
@RestController
@RequiredArgsConstructor
public class SkillVariantResource implements SkillVariantApi {

    private final SkillVariantService skillVariantService;

    @Override
    @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")
    public ResponseEntity<VariantResponse> createVariant(
            UUID skillId, int versionNumber, CreateVariantRequest request) {
        AlexandriaUserDetails user = currentUser();
        SkillVariantEntity variant = skillVariantService.create(
                skillId, versionNumber, request.tool(), request.instructions(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(variant));
    }

    @Override
    @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")
    public ResponseEntity<VariantResponse> updateVariant(
            UUID skillId, int versionNumber, String tool, CreateVariantRequest request) {
        AlexandriaUserDetails user = currentUser();
        SkillVariantEntity variant = skillVariantService.update(
                skillId, versionNumber, tool, request.instructions(), user);
        return ResponseEntity.ok(toResponse(variant));
    }

    @Override
    @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")
    public ResponseEntity<Void> deleteVariant(UUID skillId, int versionNumber, String tool) {
        AlexandriaUserDetails user = currentUser();
        skillVariantService.delete(skillId, versionNumber, tool, user);
        return ResponseEntity.noContent().build();
    }

    // --- helpers ---

    private static VariantResponse toResponse(SkillVariantEntity variant) {
        return new VariantResponse(
                variant.getId(),
                variant.getTool(),
                variant.getInstructions(),
                variant.getCreatedAt(),
                variant.getUpdatedAt()
        );
    }

    private AlexandriaUserDetails currentUser() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
