package com.alexandria.agent.render;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the per-format {@link SkillFetchRenderer} beans into a lookup keyed by format string,
 * defaulting to {@code agent_skill} when a requested format is unknown or absent.
 */
@Component
public class SkillFetchRendererRegistry {

    private final Map<String, SkillFetchRenderer> renderers = new HashMap<>();

    public SkillFetchRendererRegistry(List<SkillFetchRenderer> rendererBeans) {
        for (SkillFetchRenderer renderer : rendererBeans) {
            SkillFetchRenderer existing = renderers.putIfAbsent(renderer.format(), renderer);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate fetch renderer registered for format " + renderer.format());
            }
        }
    }

    /**
     * Returns the renderer for the requested format, or the {@code agent_skill} default when the
     * format is null, blank, or unrecognized.
     */
    public SkillFetchRenderer rendererFor(String format) {
        if (format == null || format.isBlank()) {
            return renderers.get(AgentSkillRenderer.FORMAT);
        }
        return renderers.getOrDefault(format, renderers.get(AgentSkillRenderer.FORMAT));
    }
}
