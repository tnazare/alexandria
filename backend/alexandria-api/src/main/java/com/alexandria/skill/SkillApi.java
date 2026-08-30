package com.alexandria.skill;

import com.alexandria.common.dto.PageResponse;
import com.alexandria.skill.dto.ChangeVisibilityRequest;
import com.alexandria.skill.dto.CreateSkillRequest;
import com.alexandria.skill.dto.SkillResponse;
import com.alexandria.skill.dto.SkillSummaryResponse;
import com.alexandria.skill.dto.SkillVersionResponse;
import com.alexandria.skill.dto.UpdateSkillRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * REST API contract for skill management.
 */
@RequestMapping("/api/skills")
public interface SkillApi {

    @PostMapping
    ResponseEntity<SkillResponse> createSkill(@Valid @RequestBody CreateSkillRequest request);

    @GetMapping
    ResponseEntity<PageResponse<SkillSummaryResponse>> listSkills(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String slug,
            @RequestParam(defaultValue = "false") boolean includeArchived
    );

    @GetMapping("/{skillId}")
    ResponseEntity<SkillResponse> getSkill(@PathVariable UUID skillId);

    @PutMapping("/{skillId}")
    ResponseEntity<SkillResponse> updateSkill(
            @PathVariable UUID skillId,
            @Valid @RequestBody UpdateSkillRequest request
    );

    @DeleteMapping("/{skillId}")
    ResponseEntity<Void> deleteSkill(@PathVariable UUID skillId);

    @PostMapping("/{skillId}/publish")
    ResponseEntity<SkillResponse> publishSkill(@PathVariable UUID skillId);

    @PatchMapping("/{skillId}/visibility")
    ResponseEntity<SkillResponse> changeVisibility(
            @PathVariable UUID skillId,
            @Valid @RequestBody ChangeVisibilityRequest request
    );

    @GetMapping("/{skillId}/versions")
    ResponseEntity<PageResponse<SkillVersionResponse>> listVersions(
            @PathVariable UUID skillId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    );

    @GetMapping("/{skillId}/versions/{versionNumber}")
    ResponseEntity<SkillVersionResponse> getVersion(
            @PathVariable UUID skillId,
            @PathVariable int versionNumber
    );
}
