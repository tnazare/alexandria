package com.alexandria.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.alexandria.config.CatalogProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CatalogRenderer}: line format (matches the benchmark catalog_line), header,
 * truncation note, governance annotation on/off, and determinism.
 *
 * <p>Validates: skill-catalog R3.3, R3.5, R5.2</p>
 */
class CatalogRendererTest {

    private static final UUID VID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static CatalogRenderer renderer(boolean includeGovernance) {
        return new CatalogRenderer(
                new CatalogProperties(true, 500, includeGovernance, "all", false));
    }

    private static CatalogEntry entry(String name, String desc) {
        return new CatalogEntry(UUID.randomUUID(), VID, name, desc, "Published", "low", "organization");
    }

    @Test
    void lineMatchesBenchmarkFormatWithEmDash() {
        // Benchmark catalog_line: "{id}: {name} — {description}" (em dash, U+2014).
        String line = renderer(false).line(entry("PDF Tools", "Work with PDFs"));
        assertThat(line).isEqualTo(VID + ": PDF Tools \u2014 Work with PDFs");
    }

    @Test
    void lineUsesVersionIdAsTheFetchId() {
        String line = renderer(false).line(entry("A", "b"));
        assertThat(line).startsWith(VID.toString() + ": ");
    }

    @Test
    void governanceAnnotationAppendedOnlyWhenEnabled() {
        assertThat(renderer(false).line(entry("A", "b"))).doesNotContain("[");
        assertThat(renderer(true).line(entry("A", "b"))).endsWith(" [Published/low]");
    }

    @Test
    void governanceAnnotationUsesDashForMissingFields() {
        CatalogEntry e = new CatalogEntry(UUID.randomUUID(), VID, "A", "b", "Published", null, "team");
        assertThat(renderer(true).line(e)).endsWith(" [Published/-]");
    }

    @Test
    void renderIncludesHeaderAndFetchInstruction() {
        String out = renderer(false).render(
                new CatalogResult(List.of(entry("A", "b")), false, 1));
        assertThat(out).contains("# Skill Catalog");
        assertThat(out).contains("fetch_skill");
        assertThat(out).contains("id: name \u2014 description");
        assertThat(out).contains(VID + ": A \u2014 b");
    }

    @Test
    void renderAppendsTruncationNoteWhenTruncated() {
        String notTruncated = renderer(false).render(
                new CatalogResult(List.of(entry("A", "b")), false, 1));
        assertThat(notTruncated).doesNotContain("truncated");

        String truncated = renderer(false).render(
                new CatalogResult(List.of(entry("A", "b")), true, 1));
        assertThat(truncated).contains("truncated");
    }

    @Test
    void renderIsDeterministic() {
        CatalogResult result = new CatalogResult(
                List.of(entry("A", "a"), entry("B", "b")), false, 2);
        CatalogRenderer r = renderer(true);
        assertThat(r.render(result)).isEqualTo(r.render(result));
    }

    @Test
    void handlesNullNameAndDescriptionGracefully() {
        CatalogEntry e = new CatalogEntry(UUID.randomUUID(), VID, null, null, "Published", "low", "org");
        assertThat(renderer(false).line(e)).isEqualTo(VID + ":  \u2014 ");
    }
}
