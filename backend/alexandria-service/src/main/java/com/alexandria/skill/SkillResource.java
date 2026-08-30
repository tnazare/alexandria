package com.alexandria.skill;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.common.dto.PageResponse;
import com.alexandria.skill.dto.ChangeVisibilityRequest;
import com.alexandria.skill.dto.CreateSkillRequest;
import com.alexandria.skill.dto.SkillResponse;
import com.alexandria.skill.dto.SkillSummaryResponse;
import com.alexandria.skill.dto.SkillVersionResponse;
import com.alexandria.skill.dto.UpdateSkillRequest;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for skill management endpoints.
 *
 * <p>Authorization is enforced via the {@code skillSecurity} SpEL bean:
 * {@code canEdit} for mutating operations and {@code canView} for reads of a
 * specific skill. The list endpoint applies visibility filtering at the
 * service/query layer instead.</p>
 */
@RestController
@RequiredArgsConstructor
public class SkillResource implements SkillApi {

    private final SkillService skillService;
    private final SkillResponseMapper responseMapper;

    @Override
    public ResponseEntity<SkillResponse> createSkill(CreateSkillRequest request) {
        AlexandriaUserDetails user = currentUser();
        SkillEntity skill = skillService.create(
                request.name(), request.description(), request.ownerTeamId(),
                request.canonicalInstructions(), user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseMapper.toSkillResponse(skill));
    }

    @Override
    public ResponseEntity<PageResponse<SkillSummaryResponse>> listSkills(
            int page, int size, String status, String visibility, UUID teamId, String search, String slug,
            boolean includeArchived) {
        AlexandriaUserDetails user = currentUser();

        // Direct slug lookup returns a single-item page when found.
        if (slug != null && !slug.isBlank()) {
            SkillEntity skill = skillService.getBySlug(slug, user);
            SkillSummaryResponse summary = responseMapper.toSummaryResponse(skill);
            return ResponseEntity.ok(new PageResponse<>(java.util.List.of(summary), 0, 1, 1, 1));
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<SkillEntity> skills = skillService.list(status, teamId, blankToNull(search), includeArchived, user, pageable);
        Page<SkillSummaryResponse> mapped = skills.map(responseMapper::toSummaryResponse);
        return ResponseEntity.ok(toPageResponse(mapped));
    }

    @Override
    @PreAuthorize("@skillSecurity.canView(#skillId, authentication)")
    public ResponseEntity<SkillResponse> getSkill(UUID skillId) {
        AlexandriaUserDetails user = currentUser();
        SkillEntity skill = skillService.getById(skillId, user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    @Override
    @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")
    public ResponseEntity<SkillResponse> updateSkill(UUID skillId, UpdateSkillRequest request) {
        AlexandriaUserDetails user = currentUser();
        UpdateParams params = new UpdateParams(
                request.name(),
                request.description(),
                request.canonicalInstructions(),
                request.applicability(),
                request.limitations(),
                request.whenToUse(),
                request.examples(),
                request.changelog(),
                request.tagIds(),
                request.supportedTools(),
                request.riskLevel()
        );
        SkillEntity skill = skillService.update(skillId, params, user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    @Override
    @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")
    public ResponseEntity<Void> deleteSkill(UUID skillId) {
        AlexandriaUserDetails user = currentUser();
        skillService.delete(skillId, user);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")
    public ResponseEntity<SkillResponse> publishSkill(UUID skillId) {
        AlexandriaUserDetails user = currentUser();
        SkillEntity skill = skillService.publish(skillId, user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    @Override
    @PreAuthorize("@skillSecurity.canEdit(#skillId, authentication)")
    public ResponseEntity<SkillResponse> changeVisibility(UUID skillId, ChangeVisibilityRequest request) {
        AlexandriaUserDetails user = currentUser();
        SkillEntity skill = skillService.changeVisibility(skillId, request.visibility(), user);
        return ResponseEntity.ok(responseMapper.toSkillResponse(skill));
    }

    @Override
    @PreAuthorize("@skillSecurity.canView(#skillId, authentication)")
    public ResponseEntity<PageResponse<SkillVersionResponse>> listVersions(UUID skillId, int page, int size) {
        AlexandriaUserDetails user = currentUser();
        Pageable pageable = PageRequest.of(page, size);
        Page<SkillVersionEntity> versions = skillService.listVersions(skillId, user, pageable);
        Page<SkillVersionResponse> mapped = versions.map(responseMapper::toVersionResponse);
        return ResponseEntity.ok(toPageResponse(mapped));
    }

    @Override
    @PreAuthorize("@skillSecurity.canView(#skillId, authentication)")
    public ResponseEntity<SkillVersionResponse> getVersion(UUID skillId, int versionNumber) {
        AlexandriaUserDetails user = currentUser();
        SkillVersionEntity version = skillService.getVersion(skillId, versionNumber, user);
        return ResponseEntity.ok(responseMapper.toVersionResponse(version));
    }

    // --- helpers ---

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private AlexandriaUserDetails currentUser() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
