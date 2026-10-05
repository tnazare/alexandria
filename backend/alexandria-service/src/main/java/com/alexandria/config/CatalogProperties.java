package com.alexandria.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the agent-facing resident Skill Catalog (an MCP resource). All values are
 * environment-variable driven so a deployer can enable and tune the catalog without source changes.
 *
 * <p>The catalog productizes the benchmark-validated "resident catalog" mechanism: a compact
 * {@code id: name — description} line per ACL-visible skill, loaded into the agent's context so skill
 * selection is resident (no tool call to skip), while bodies are still pulled on demand via
 * {@code fetch_skill}. See the {@code skill-catalog} spec and
 * {@code experiments/results/run-20261002-095819.md}.</p>
 *
 * <p>Follows the existing {@link SearchProperties} record pattern and is registered in
 * {@code SecurityConfig}'s {@code @EnableConfigurationProperties}. The feature is <strong>off by
 * default</strong> ({@code enabled=false}), so existing deployments and the current "tools-only, do
 * not preload catalog" MCP stance are unchanged until a deployer opts in (R4.1, R4.2).</p>
 *
 * @param enabled                master gate (env {@code ALEXANDRIA_CATALOG_ENABLED}; default
 *                               {@code false}). When false, the MCP endpoint advertises tools only and
 *                               {@code resources/*} returns method-not-found — exactly today's behavior.
 * @param maxEntries             payload cap on catalog lines (env {@code ALEXANDRIA_CATALOG_MAX_ENTRIES};
 *                               default 500). The resident catalog's token cost grows ~linearly in N,
 *                               so this is the operator's context-budget lever (R5).
 * @param includeGovernance      annotate each line with lifecycle/risk (env
 *                               {@code ALEXANDRIA_CATALOG_INCLUDE_GOVERNANCE}; default {@code false}).
 * @param lifecycleScope         narrows the listing within the caller's ACL: {@code all} |
 *                               {@code approved_only} (env {@code ALEXANDRIA_CATALOG_LIFECYCLE_SCOPE};
 *                               default {@code all}). An unknown value fails fast at startup (R6.1).
 * @param includeArchivedDefault whether archived skills are listed by default (env
 *                               {@code ALEXANDRIA_CATALOG_INCLUDE_ARCHIVED}; default {@code false}).
 */
@ConfigurationProperties(prefix = "alexandria.catalog")
public record CatalogProperties(
        Boolean enabled,
        Integer maxEntries,
        Boolean includeGovernance,
        String lifecycleScope,
        Boolean includeArchivedDefault
) {

    /** Valid lifecycle scopes. An unknown scope is rejected at startup (R6.1). */
    public static final Set<String> VALID_SCOPES = Set.of("all", "approved_only");

    /** Default payload cap; documented in the spec (R5.1). */
    public static final int DEFAULT_MAX_ENTRIES = 500;

    public CatalogProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (maxEntries == null || maxEntries < 1) {
            maxEntries = DEFAULT_MAX_ENTRIES;
        }
        if (includeGovernance == null) {
            includeGovernance = false;
        }
        if (lifecycleScope == null || lifecycleScope.isBlank()) {
            lifecycleScope = "all";
        }
        lifecycleScope = lifecycleScope.trim().toLowerCase();
        if (!VALID_SCOPES.contains(lifecycleScope)) {
            throw new IllegalArgumentException(
                    "Invalid alexandria.catalog.lifecycle-scope '" + lifecycleScope + "'. Must be one of "
                            + VALID_SCOPES + " (env ALEXANDRIA_CATALOG_LIFECYCLE_SCOPE).");
        }
        if (includeArchivedDefault == null) {
            includeArchivedDefault = false;
        }
    }

    /** True when the configured scope restricts the catalog to approved lifecycle states (R6). */
    public boolean approvedOnly() {
        return "approved_only".equals(lifecycleScope);
    }
}
