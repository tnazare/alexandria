package com.alexandria.config;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * Surfaces the active retrieval configuration on the public {@code /actuator/info} endpoint so the
 * benchmark harness can <em>verify</em> and record the mode a run executed under, rather than merely
 * trusting the operator (R7.1 server side, R1.4). Startup logging ({@link SearchModeStartupLogger})
 * already announces the mode; this adds a machine-readable read path.
 *
 * <p>The {@code /actuator/info} endpoint is already exposed ({@code management.endpoints.web.exposure})
 * and publicly accessible (see {@code SecurityConfig} — {@code /actuator/info} is permitted without
 * authentication). Custom {@link InfoContributor} beans are enabled by default, so no extra
 * configuration is required.</p>
 *
 * <p>Reported under the {@code search} key:</p>
 * <pre>
 * GET /actuator/info
 * {
 *   "search": {
 *     "mode": "fts",
 *     "embeddingProvider": "local",
 *     "embeddingDim": 256
 *   },
 *   ...
 * }
 * </pre>
 *
 * <p>The values are non-sensitive attribution metadata (no skill names, no org data, no secrets), so
 * exposing them on the unauthenticated info endpoint respects the existing security posture.</p>
 */
@Component
@RequiredArgsConstructor
public class SearchModeInfoContributor implements InfoContributor {

    private final SearchProperties searchProperties;

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("search", Map.of(
                "mode", searchProperties.mode(),
                "embeddingProvider", searchProperties.embeddingProvider(),
                "embeddingDim", searchProperties.embeddingDim()
        ));
    }
}
