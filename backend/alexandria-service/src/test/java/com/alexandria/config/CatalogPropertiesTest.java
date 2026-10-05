package com.alexandria.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link CatalogProperties} — the resident Skill Catalog feature flag.
 *
 * <p>Covers R4.1 (off by default), R4.3/R5.1 (tuning defaults), and R6.1 (unknown lifecycle scope
 * fails fast rather than silently falling back).</p>
 */
class CatalogPropertiesTest {

    @Test
    void unsetDefaultsToDisabledWithSafeDefaults() {
        // R4.1: all-null config is disabled with documented defaults.
        CatalogProperties props = new CatalogProperties(null, null, null, null, null);

        assertThat(props.enabled()).isFalse();
        assertThat(props.maxEntries()).isEqualTo(CatalogProperties.DEFAULT_MAX_ENTRIES);
        assertThat(props.includeGovernance()).isFalse();
        assertThat(props.lifecycleScope()).isEqualTo("all");
        assertThat(props.includeArchivedDefault()).isFalse();
        assertThat(props.approvedOnly()).isFalse();
    }

    @Test
    void enabledTrueIsHonored() {
        assertThat(new CatalogProperties(true, null, null, null, null).enabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "approved_only"})
    void eachValidScopeIsAccepted(String scope) {
        assertThat(new CatalogProperties(true, null, null, scope, null).lifecycleScope())
                .isEqualTo(scope);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "  Approved_Only "})
    void validScopeIsNormalizedCaseAndWhitespaceInsensitively(String scope) {
        assertThat(new CatalogProperties(true, null, null, scope, null).lifecycleScope())
                .isEqualTo(scope.trim().toLowerCase());
    }

    @Test
    void approvedOnlyScopeFlagsApprovedOnly() {
        assertThat(new CatalogProperties(true, null, null, "approved_only", null).approvedOnly())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "published", "draft", "none", "0"})
    void unknownScopeFailsFast(String scope) {
        // R6.1: an unrecognized lifecycle scope throws (Spring context fails to start).
        assertThatThrownBy(() -> new CatalogProperties(true, null, null, scope, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alexandria.catalog.lifecycle-scope")
                .hasMessageContaining(scope);
    }

    @Test
    void nonPositiveMaxEntriesFallsBackToDefault() {
        assertThat(new CatalogProperties(true, 0, null, null, null).maxEntries())
                .isEqualTo(CatalogProperties.DEFAULT_MAX_ENTRIES);
        assertThat(new CatalogProperties(true, -10, null, null, null).maxEntries())
                .isEqualTo(CatalogProperties.DEFAULT_MAX_ENTRIES);
    }

    @Test
    void customTuningIsPreserved() {
        CatalogProperties props = new CatalogProperties(true, 50, true, "approved_only", true);

        assertThat(props.maxEntries()).isEqualTo(50);
        assertThat(props.includeGovernance()).isTrue();
        assertThat(props.lifecycleScope()).isEqualTo("approved_only");
        assertThat(props.includeArchivedDefault()).isTrue();
    }
}
