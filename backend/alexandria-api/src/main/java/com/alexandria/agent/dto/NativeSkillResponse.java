package com.alexandria.agent.dto;

/**
 * The alexandria_native fetch response: Alexandria's structured JSON representation of a fetched
 * skill version. The agent_skill format returns rendered SKILL.md text instead of this record.
 *
 * @param ref        embedded identity (always present, in every format)
 * @param governance lifecycle/risk/visibility snapshot, including any deprecation warning
 * @param resolution reference-mode resolution and commit policy
 * @param content    the structured content block
 * @param variant    the selected variant or canonical fallback
 */
public record NativeSkillResponse(
        SkillRef ref,
        Governance governance,
        Resolution resolution,
        NativeContent content,
        NativeVariant variant
) {
}
