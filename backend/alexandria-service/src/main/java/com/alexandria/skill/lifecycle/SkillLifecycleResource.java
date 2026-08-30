package com.alexandria.skill.lifecycle;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.SkillResponseMapper;
import com.alexandria.skill.dto.SkillResponse;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.lifecycle.dto.DeprecateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for skill lifecycle governance transitions.
 *
 * <p>All endpoints are restricted to Catalog_Admin and Org_Admin. Organization scoping and
 * transition legality are enforced by {@link SkillLifecycleService}.</p>
 */
@RestController
@RequiredArgsConstructor
public class SkillLifecycleResource implements SkillLifecycleApi {

    private final SkillLifecycleService lifecycleService;
    private final SkillResponseMapper responseMapper;

    @Override
    @PreAuthorize("hasAnyRole('Catalog_Admin', 'Org_Admin')")
    public ResponseEntity<SkillResponse> deprecateSkill(UUID skillId, DeprecateRequest request) {
        AlexandriaUserDetails user = currentUser();
        String reason = request == null ? null : request.reason();
        SkillEntity skill = lifecycleService.deprecate(skillId, reason, user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    @Override
    @PreAuthorize("hasAnyRole('Catalog_Admin', 'Org_Admin')")
    public ResponseEntity<SkillResponse> blockSkill(UUID skillId) {
        AlexandriaUserDetails user = currentUser();
        SkillEntity skill = lifecycleService.block(skillId, user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    @Override
    @PreAuthorize("hasAnyRole('Catalog_Admin', 'Org_Admin')")
    public ResponseEntity<SkillResponse> archiveSkill(UUID skillId) {
        AlexandriaUserDetails user = currentUser();
        SkillEntity skill = lifecycleService.archive(skillId, user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    @Override
    @PreAuthorize("hasAnyRole('Catalog_Admin', 'Org_Admin')")
    public ResponseEntity<SkillResponse> restoreSkill(UUID skillId) {
        AlexandriaUserDetails user = currentUser();
        SkillEntity skill = lifecycleService.restore(skillId, user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    private AlexandriaUserDetails currentUser() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
