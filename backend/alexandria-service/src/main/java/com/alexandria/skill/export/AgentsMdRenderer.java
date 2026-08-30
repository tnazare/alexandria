package com.alexandria.skill.export;

import org.springframework.stereotype.Component;

import static com.alexandria.skill.export.RenderSupport.orEmpty;
import static com.alexandria.skill.export.RenderSupport.present;
import static com.alexandria.skill.export.RenderSupport.section;

/**
 * Renders a skill as an {@code AGENTS.md}-style fragment: the skill framed as agent guidance,
 * with a short "Applies when / Limitations" preamble ahead of the instructions.
 */
@Component
public class AgentsMdRenderer implements SkillExportRenderer {

    @Override
    public ExportFormat format() {
        return ExportFormat.AGENTS_MD;
    }

    @Override
    public String render(ExportRenderModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(orEmpty(model.name())).append("\n\n");
        if (present(model.description())) {
            sb.append(model.description().strip()).append("\n\n");
        }
        if (present(model.applicability())) {
            sb.append("Applies when: ").append(model.applicability().strip()).append("\n\n");
        }
        if (present(model.limitations())) {
            sb.append("Limitations: ").append(model.limitations().strip()).append("\n\n");
        }
        sb.append("## Guidance\n\n");
        sb.append(orEmpty(model.instructions())).append("\n\n");
        section(sb, "Examples", model.examples());
        return sb.toString().stripTrailing() + "\n";
    }
}
