package com.alexandria.skill.export;

import org.springframework.stereotype.Component;

import static com.alexandria.skill.export.RenderSupport.orEmpty;
import static com.alexandria.skill.export.RenderSupport.present;

/**
 * Renders Cursor rule content suitable for a {@code .cursor/rules} file: a description line
 * followed by the instructions as the rule body.
 */
@Component
public class CursorRuleRenderer implements SkillExportRenderer {

    @Override
    public ExportFormat format() {
        return ExportFormat.CURSOR_RULE;
    }

    @Override
    public String render(ExportRenderModel model) {
        StringBuilder sb = new StringBuilder();
        String description = present(model.description())
                ? model.description().strip()
                : orEmpty(model.name());
        sb.append("# Cursor Rule: ").append(orEmpty(model.name())).append("\n\n");
        sb.append("Description: ").append(description).append("\n\n");
        if (present(model.applicability())) {
            sb.append("Applies when: ").append(model.applicability().strip()).append("\n\n");
        }
        sb.append("Rule:\n\n");
        sb.append(orEmpty(model.instructions())).append("\n");
        return sb.toString().stripTrailing() + "\n";
    }
}
