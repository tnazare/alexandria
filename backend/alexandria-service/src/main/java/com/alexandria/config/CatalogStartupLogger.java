package com.alexandria.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Logs the resident Skill Catalog configuration at startup so a deployment (and the benchmark
 * harness) can attribute behavior to catalog on/off and its tuning (R4.5). Validation of the scope
 * itself happens earlier, in {@link CatalogProperties}'s compact constructor (fail-fast on an unknown
 * {@code lifecycle-scope}, R6.1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogStartupLogger {

    private final CatalogProperties catalogProperties;

    @PostConstruct
    void logCatalogConfig() {
        if (Boolean.TRUE.equals(catalogProperties.enabled())) {
            log.info("Alexandria skill catalog: ENABLED (maxEntries={}, lifecycleScope={}, "
                            + "includeGovernance={}, includeArchivedDefault={})",
                    catalogProperties.maxEntries(),
                    catalogProperties.lifecycleScope(),
                    catalogProperties.includeGovernance(),
                    catalogProperties.includeArchivedDefault());
        } else {
            log.info("Alexandria skill catalog: disabled (MCP advertises tools only)");
        }
    }
}
