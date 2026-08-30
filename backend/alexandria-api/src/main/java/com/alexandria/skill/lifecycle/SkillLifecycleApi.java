package com.alexandria.skill.lifecycle;

import com.alexandria.skill.dto.SkillResponse;
import com.alexandria.skill.lifecycle.dto.DeprecateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

/**
 * REST API contract for skill lifecycle governance transitions.
 *
 * <p>All transitions are restricted to Catalog_Admin and Org_Admin, are skill-level (they do
 * not create a new version), and return the updated skill.</p>
 */
@RequestMapping("/api/skills/{skillId}")
public interface SkillLifecycleApi {

    @PostMapping("/deprecate")
    ResponseEntity<SkillResponse> deprecateSkill(
            @PathVariable UUID skillId,
            @Valid @RequestBody(required = false) DeprecateRequest request
    );

    @PostMapping("/block")
    ResponseEntity<SkillResponse> blockSkill(@PathVariable UUID skillId);

    @PostMapping("/archive")
    ResponseEntity<SkillResponse> archiveSkill(@PathVariable UUID skillId);

    @PostMapping("/restore")
    ResponseEntity<SkillResponse> restoreSkill(@PathVariable UUID skillId);
}
