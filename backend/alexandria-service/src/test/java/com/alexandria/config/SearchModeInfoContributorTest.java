package com.alexandria.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

/**
 * Unit tests for {@link SearchModeInfoContributor} — the read path the benchmark harness queries to
 * verify and record the active retrieval mode (R7.1 server side, R1.4).
 */
class SearchModeInfoContributorTest {

    @Test
    void contributesActiveModeProviderAndDimUnderSearchKey() {
        SearchProperties props = new SearchProperties("semantic", 512, "http", null);
        SearchModeInfoContributor contributor = new SearchModeInfoContributor(props);

        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        Info info = builder.build();

        @SuppressWarnings("unchecked")
        Map<String, Object> search = (Map<String, Object>) info.getDetails().get("search");
        assertThat(search).isNotNull();
        assertThat(search.get("mode")).isEqualTo("semantic");
        assertThat(search.get("embeddingProvider")).isEqualTo("http");
        assertThat(search.get("embeddingDim")).isEqualTo(512);
    }

    @Test
    void reportsDefaultFtsModeWhenUnset() {
        // R1.1 default: an unset mode is reported as fts, so a default-config run is still attributable.
        SearchModeInfoContributor contributor =
                new SearchModeInfoContributor(new SearchProperties(null, null, null, null));

        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        Info info = builder.build();

        @SuppressWarnings("unchecked")
        Map<String, Object> search = (Map<String, Object>) info.getDetails().get("search");
        assertThat(search.get("mode")).isEqualTo("fts");
        assertThat(search.get("embeddingProvider")).isEqualTo("local");
        assertThat(search.get("embeddingDim")).isEqualTo(256);
    }
}
