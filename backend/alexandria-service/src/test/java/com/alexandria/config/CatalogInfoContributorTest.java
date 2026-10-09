package com.alexandria.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

/**
 * Unit tests for {@link CatalogInfoContributor} — the read path a deployment/harness queries to
 * attribute behavior to catalog on/off and its tuning (R4.5).
 */
class CatalogInfoContributorTest {

    private static Map<String, Object> contribute(CatalogProperties props) {
        Info.Builder builder = new Info.Builder();
        new CatalogInfoContributor(props).contribute(builder);
        @SuppressWarnings("unchecked")
        Map<String, Object> catalog = (Map<String, Object>) builder.build().getDetails().get("catalog");
        return catalog;
    }

    @Test
    void reportsDisabledDefaultsWhenUnset() {
        Map<String, Object> catalog = contribute(new CatalogProperties(null, null, null, null, null));
        assertThat(catalog).isNotNull();
        assertThat(catalog.get("enabled")).isEqualTo(false);
        assertThat(catalog.get("maxEntries")).isEqualTo(CatalogProperties.DEFAULT_MAX_ENTRIES);
        assertThat(catalog.get("lifecycleScope")).isEqualTo("all");
    }

    @Test
    void reportsEnabledTunedConfig() {
        Map<String, Object> catalog =
                contribute(new CatalogProperties(true, 42, true, "approved_only", true));
        assertThat(catalog.get("enabled")).isEqualTo(true);
        assertThat(catalog.get("maxEntries")).isEqualTo(42);
        assertThat(catalog.get("lifecycleScope")).isEqualTo("approved_only");
    }
}
