package com.alexandria.skill;

import java.util.List;
import java.util.UUID;

/**
 * Parameters for partial skill updates.
 * All fields are nullable — only non-null fields are applied.
 */
public record UpdateParams(
        String name,
        String description,
        String canonicalInstructions,
        String applicability,
        String limitations,
        String whenToUse,
        String examples,
        String changelog,
        List<UUID> tagIds,
        List<String> supportedTools,
        String riskLevel
) {}
