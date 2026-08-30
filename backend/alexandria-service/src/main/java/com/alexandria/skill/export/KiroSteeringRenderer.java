package com.alexandria.skill.export;

import org.springframework.stereotype.Component;

import static com.alexandria.skill.export.RenderSupport.orEmpty;
import static com.alexandria.skill.export.RenderSupport.present;
import static com.alexandria.skill.export.RenderSupport.section;

/**
 * Renders a Kiro steering Markdown fragment (front-matter-free) suitable for a
 * {@code .kiro/steering} file: the instructions plus a "When to use" note.
 */
@Component
public class KiroSteeringRenderer implements SkillExportRenderer {

    @Override
    public ExportFormat format() {
        return ExportFormat.KIRO_STEERING;
    }

    @Override
    public String render(ExportRenderModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(orEmpty(model.name())).append("\n\n");
        if (present(model.description())) {
            sb.append(model.description().strip()).append("\n\n");
        }
        sb.append("## Guidance\n\n");
        sb.append(orEmpty(model.instructions())).append("\n\n");
        section(sb, "When to use", model.whenToUse());
        section(sb, "Limitations", model.limitations());
        return sb.toString().stripTrailing() + "\n";
    }
}
