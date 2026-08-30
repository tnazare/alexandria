package com.alexandria.skill.lifecycle.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for deprecating a skill. The reason is optional.
 */
public record DeprecateRequest(
        @Size(max = 1000) String reason
) {
}
