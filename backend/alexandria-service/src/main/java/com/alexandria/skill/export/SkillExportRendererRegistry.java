package com.alexandria.skill.export;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the per-format {@link SkillExportRenderer} beans into a lookup keyed by
 * {@link ExportFormat}, and selects the renderer for a requested format.
 *
 * <p>Constructed from the injected renderer list; a duplicate registration for a format or a
 * request for an unmapped format is a programming error and fails fast.</p>
 */
@Component
public class SkillExportRendererRegistry {

    private final Map<ExportFormat, SkillExportRenderer> renderers = new EnumMap<>(ExportFormat.class);

    public SkillExportRendererRegistry(List<SkillExportRenderer> rendererBeans) {
        for (SkillExportRenderer renderer : rendererBeans) {
            SkillExportRenderer existing = renderers.putIfAbsent(renderer.format(), renderer);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate renderer registered for format " + renderer.format());
            }
        }
    }

    /**
     * Returns the renderer for the given format.
     *
     * @throws IllegalStateException if no renderer is registered for the format
     */
    public SkillExportRenderer rendererFor(ExportFormat format) {
        SkillExportRenderer renderer = renderers.get(format);
        if (renderer == null) {
            throw new IllegalStateException("No renderer registered for format " + format);
        }
        return renderer;
    }
}
