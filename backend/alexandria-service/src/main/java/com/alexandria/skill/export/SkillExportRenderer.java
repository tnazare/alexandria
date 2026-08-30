package com.alexandria.skill.export;

/**
 * Strategy that renders a skill into one {@link ExportFormat}.
 *
 * <p>Implementations are pure and deterministic: {@link #render} depends only on its argument
 * and contains no timestamps, randomness, or environment state. One {@code @Component}
 * implementation exists per format; the {@link SkillExportRendererRegistry} selects among them.</p>
 */
public interface SkillExportRenderer {

    /** The format this renderer produces. */
    ExportFormat format();

    /** Renders the model into a non-empty artifact for this format. */
    String render(ExportRenderModel model);
}
