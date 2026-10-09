package com.alexandria.config;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * Surfaces the resident Skill Catalog configuration on the public {@code /actuator/info} endpoint so
 * a deployment (and the benchmark harness) can verify and record whether the catalog is enabled and
 * how it is tuned, rather than merely trusting the operator (R4.5). Startup logging
 * ({@link CatalogStartupLogger}) already announces this; this adds a machine-readable read path,
 * mirroring {@link SearchModeInfoContributor}.
 *
 * <p>Reported under the {@code catalog} key:</p>
 * <pre>
 * GET /actuator/info
 * {
 *   "catalog": {
 *     "enabled": false,
 *     "maxEntries": 500,
 *     "lifecycleScope": "all"
 *   },
 *   ...
 * }
 * </pre>
 *
 * <p>The values are non-sensitive attribution metadata (no skill names, no org data, no secrets), so
 * exposing them on the unauthenticated info endpoint respects the existing security posture (R7.3).</p>
 */
@Component
@RequiredArgsConstructor
public class CatalogInfoContributor implements InfoContributor {

    private final CatalogProperties catalogProperties;

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("catalog", Map.of(
                "enabled", catalogProperties.enabled(),
                "maxEntries", catalogProperties.maxEntries(),
                "lifecycleScope", catalogProperties.lifecycleScope()
        ));
    }
}
