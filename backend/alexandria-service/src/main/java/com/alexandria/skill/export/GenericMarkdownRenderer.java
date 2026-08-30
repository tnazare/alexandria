package com.alexandria.skill.export;

import org.springframework.stereotype.Component;

import static com.alexandria.skill.export.RenderSupport.orEmpty;
import static com.alexandria.skill.export.RenderSupport.present;
import static com.alexandria.skill.export.RenderSupport.section;

/**
 * Renders a skill as a headed Markdown document. This is the one renderer that surfaces the
 * risk level, as a blockquote note under the title.
 */
@Component
public class GenericMarkdownRenderer implements SkillExportRenderer {

    @Override
    public ExportFormat format() {
        return ExportFormat.GENERIC_MARKDOWN;
    }

    @Override
    public String render(ExportRenderModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(orEmpty(model.name())).append("\n\n");
        if (present(model.description())) {
            sb.append(model.description().strip()).append("\n\n");
        }
        if (present(model.riskLevel())) {
            sb.append("> Risk: ").append(model.riskLevel().strip()).append("\n\n");
        }
        // Instructions section is always emitted, even when empty (Requirement 1.8).
        sb.append("## Instructions\n\n");
        sb.append(orEmpty(model.instructions())).append("\n\n");
        section(sb, "Applicability", model.applicability());
        section(sb, "When to use", model.whenToUse());
        section(sb, "Limitations", model.limitations());
        section(sb, "Examples", model.examples());
        return sb.toString().stripTrailing() + "\n";
    }
}
