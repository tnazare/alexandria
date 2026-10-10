package com.alexandria.agent.catalog;

import org.springframework.stereotype.Component;

/**
 * Renders a {@link CatalogResult} into the resident Skill Catalog text an MCP client loads into the
 * agent's context. The per-skill line format — {@code "{id}: {name} — {description}"} (em dash) —
 * matches the benchmark's {@code experiments/harness/measure_tokens.catalog_line}, so the product
 * catalog and the validated experiment catalog are byte-for-byte the same shape.
 *
 * <p>The {@code id} is the skill's {@code skillVersionId}, so the agent can call
 * {@code fetch_skill(skillVersionId)} directly from a line with no extra lookup (R3.5). A short header
 * instructs the agent to pick an id and fetch it; a truncation note is appended when the listing was
 * capped (R5.2). Output is deterministic for a given result (R1.4) and rendered as Markdown.</p>
 */
@Component
public class CatalogRenderer {

    /** Em dash separating name and description, matching the benchmark catalog_line format. */
    private static final String SEP = " \u2014 ";

    private final boolean includeGovernance;

    public CatalogRenderer(com.alexandria.config.CatalogProperties catalogProperties) {
        this.includeGovernance = Boolean.TRUE.equals(catalogProperties.includeGovernance());
    }

    /** Renders the catalog as Markdown text. */
    public String render(CatalogResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill Catalog\n\n");
        sb.append("These are reusable governed skills, one per line as ");
        sb.append("`slug: name \u2014 description`. Pick the slug that best fits the task and call the ");
        sb.append("`fetch_skill` tool with that slug to get the full instructions. ");
        sb.append("If none applies, do not fetch.\n\n");

        for (CatalogEntry e : result.entries()) {
            sb.append(line(e)).append('\n');
        }

        if (result.truncated()) {
            sb.append('\n');
            sb.append("_Catalog truncated to ").append(result.totalReturned());
            sb.append(" entries; more skills are visible than shown. Narrow your task or use ");
            sb.append("resolve_skill for a ranked search._\n");
        }
        return sb.toString();
    }

    /** One catalog line: {@code slug: name — description}, optionally with a governance annotation.
     *
     * <p>The id is the skill's <strong>slug</strong>, not its {@code skillVersionId} UUID: a slug is
     * shorter and semantically useful, which cuts the resident token cost (~13 tokens/skill saved vs
     * a UUID) so the catalog's per-line overhead approaches native's name+description floor. The agent
     * passes the slug to {@code fetch_skill} ({@code skillSlug}), which resolves it to the current
     * version server-side.</p>
     */
    String line(CatalogEntry e) {
        String name = e.name() == null ? "" : e.name();
        String description = e.description() == null ? "" : e.description();
        StringBuilder line = new StringBuilder()
                .append(e.slug()).append(": ").append(name).append(SEP).append(description);
        if (includeGovernance) {
            line.append(" [").append(nullToDash(e.lifecycleStatus()))
                    .append('/').append(nullToDash(e.riskLevel())).append(']');
        }
        return line.toString();
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
