package com.alexandria.agent.render;

import org.springframework.stereotype.Component;

/**
 * Renders a fetched skill version as an Agent Skills {@code SKILL.md} package.
 *
 * <p>The YAML frontmatter contains ONLY the six portable Agent Skills spec fields
 * ({@code name}, {@code description}, {@code license}, {@code compatibility}, {@code metadata},
 * {@code allowed-tools}). All Alexandria-specific governance and identity data lives inside the
 * {@code metadata} map (Req 3.3, 6.2), never as new top-level keys. The Markdown body is wrapped in
 * {@code alexandria:start}/{@code alexandria:end} markers carrying identity + version so committed
 * copies stay traceable (Req 6.3), and body content is sanitized (Req 3.7).</p>
 */
@Component
public class AgentSkillRenderer implements SkillFetchRenderer {

    public static final String FORMAT = "agent_skill";
    private static final String MEDIA_TYPE = "text/markdown";

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public FetchResult render(FetchContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(yaml(ctx.name())).append('\n');
        sb.append("description: ").append(yaml(descriptionFor(ctx))).append('\n');
        sb.append("license: ").append(yaml("See source instance")).append('\n');
        sb.append("compatibility: ").append(yaml(compatibilityFor(ctx))).append('\n');
        sb.append("allowed-tools: []\n");
        sb.append("metadata:\n");
        appendMeta(sb, "alexandria_skill_id", str(ctx.ref().skillId()));
        appendMeta(sb, "alexandria_skill_version_id", str(ctx.ref().skillVersionId()));
        appendMeta(sb, "source_instance", ctx.ref().sourceInstance());
        appendMeta(sb, "version", String.valueOf(ctx.versionNumber()));
        appendMeta(sb, "lifecycle_status", ctx.governance().lifecycleStatus());
        appendMeta(sb, "risk_level", ctx.governance().riskLevel());
        appendMeta(sb, "visibility", ctx.governance().visibility());
        appendMeta(sb, "owner_team", ctx.governance().ownerTeam());
        appendMeta(sb, "reference_mode", ctx.resolution().mode());
        appendMeta(sb, "commit_allowed", String.valueOf(ctx.resolution().commitAllowed()));
        appendMeta(sb, "requires_revalidation", String.valueOf(ctx.resolution().requiresRevalidation()));
        if (ctx.governance().deprecated()) {
            appendMeta(sb, "deprecated", "true");
            appendMeta(sb, "deprecation_warning",
                    "This skill is deprecated"
                            + (ctx.governance().deprecationReason() != null
                                ? ": " + ctx.governance().deprecationReason() : ""));
        }
        sb.append("---\n\n");

        sb.append("<!-- alexandria:start skill=").append(ctx.slug())
                .append(" version=").append(ctx.versionNumber())
                .append(" id=").append(ctx.ref().skillVersionId())
                .append(" -->\n");

        sb.append("# ").append(ctx.name()).append("\n\n");
        if (ctx.governance().deprecated()) {
            sb.append("> **Deprecated.** ")
                    .append(ctx.governance().deprecationReason() != null
                            ? ctx.governance().deprecationReason()
                            : "This skill is deprecated and may be removed.")
                    .append("\n\n");
        }
        section(sb, "When to use", ctx.whenToUse());
        section(sb, "Instructions", ctx.selectedInstructions());
        section(sb, "Applicability", ctx.applicability());
        section(sb, "Limitations", ctx.limitations());
        section(sb, "Examples", ctx.examples());

        sb.append("<!-- alexandria:end -->\n");

        return new FetchResult(FORMAT, MEDIA_TYPE, sb.toString());
    }

    private static String descriptionFor(FetchContext ctx) {
        String base = ctx.description() == null ? "" : ctx.description();
        return base;
    }

    private static String compatibilityFor(FetchContext ctx) {
        if (ctx.supportedTools() == null || ctx.supportedTools().isEmpty()) {
            return "generic";
        }
        return String.join(", ", ctx.supportedTools());
    }

    private static void section(StringBuilder sb, String heading, String content) {
        String sanitized = ContentSanitizer.sanitize(content);
        if (sanitized == null || sanitized.isBlank()) {
            return;
        }
        sb.append("## ").append(heading).append("\n\n");
        sb.append(sanitized.strip()).append("\n\n");
    }

    private static void appendMeta(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        sb.append("  ").append(key).append(": ").append(yaml(value)).append('\n');
    }

    /** Minimal YAML scalar quoting: always double-quote and escape backslashes/quotes/newlines. */
    private static String yaml(String value) {
        String v = value == null ? "" : value;
        v = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        return "\"" + v + "\"";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
