package com.alexandria.skill.dto;

import java.util.UUID;

/**
 * Response DTO for a tag associated with a skill.
 */
public record TagResponse(UUID id, String type, String value) {
}
