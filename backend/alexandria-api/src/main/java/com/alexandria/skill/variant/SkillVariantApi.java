package com.alexandria.skill.variant;

import com.alexandria.skill.variant.dto.CreateVariantRequest;
import com.alexandria.skill.variant.dto.VariantResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

/**
 * REST API contract for skill variant management.
 */
@RequestMapping("/api/skills/{skillId}/versions/{versionNumber}/variants")
public interface SkillVariantApi {

    @PostMapping
    ResponseEntity<VariantResponse> createVariant(
            @PathVariable UUID skillId,
            @PathVariable int versionNumber,
            @Valid @RequestBody CreateVariantRequest request
    );

    @PutMapping("/{tool}")
    ResponseEntity<VariantResponse> updateVariant(
            @PathVariable UUID skillId,
            @PathVariable int versionNumber,
            @PathVariable String tool,
            @Valid @RequestBody CreateVariantRequest request
    );

    @DeleteMapping("/{tool}")
    ResponseEntity<Void> deleteVariant(
            @PathVariable UUID skillId,
            @PathVariable int versionNumber,
            @PathVariable String tool
    );
}
