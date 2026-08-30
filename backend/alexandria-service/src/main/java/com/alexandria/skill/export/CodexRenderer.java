package com.alexandria.skill.export;

import org.springframework.stereotype.Component;

import static com.alexandria.skill.export.RenderSupport.orEmpty;
import static com.alexandria.skill.export.RenderSupport.present;
import static com.alexandria.skill.export.RenderSupport.section;

/**
 * Renders a prompt-style document for Codex: a one-line role/intent header derived from the
 * name and description, the instructions, then examples if present.
 */
@Component
public class CodexRenderer implements SkillExportRenderer {

    @Override
    public ExportFormat format() {
        return ExportFormat.CODEX;
    }

    @Override
    public String render(ExportRenderModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Codex prompt: ").append(orEmpty(model.name())).append("\n\n");
        if (present(model.description())) {
            sb.append("Intent: ").append(model.description().strip()).append("\n\n");
        }
        sb.append("## Instructions\n\n");
        sb.append(orEmpty(model.instructions())).append("\n\n");
        section(sb, "Examples", model.examples());
        return sb.toString().stripTrailing() + "\n";
    }
}
