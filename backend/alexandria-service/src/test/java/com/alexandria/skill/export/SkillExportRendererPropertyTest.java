package com.alexandria.skill.export;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the skill export renderers (pure, no Spring).
 *
 * <ul>
 *   <li><b>Property 1: Format coverage</b> — a renderer exists for every {@link ExportFormat}
 *       and produces non-empty output.</li>
 *   <li><b>Property 2: Determinism</b> — rendering the same model twice is byte-identical.</li>
 *   <li><b>Property 3: Content inclusion</b> — output contains the skill name and the resolved
 *       instructions.</li>
 *   <li><b>Property 4: Variant resolution</b> — whatever instructions the model carries (variant
 *       or canonical) appear verbatim in the output.</li>
 *   <li><b>Property 8: Fence safety</b> — {@link MarkdownFence} picks a fence longer than any
 *       backtick run in the content.</li>
 *   <li><b>Property 9: Empty-instructions safety</b> — an empty-instruction model still yields a
 *       well-formed, non-empty artifact containing the name.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 1.2, 1.7, 1.8, 1.9, 2.6</b></p>
 */
@Tag("skill-export")
class SkillExportRendererPropertyTest {

    /** All renderers, built directly (no Spring) — mirrors what the registry would collect. */
    private static final List<SkillExportRenderer> RENDERERS = List.of(
            new GenericMarkdownRenderer(),
            new AgentsMdRenderer(),
            new CursorRuleRenderer(),
            new KiroSteeringRenderer(),
            new GithubCopilotRenderer(),
            new CodexRenderer()
    );

    private static SkillExportRenderer rendererFor(ExportFormat format) {
        return RENDERERS.stream()
                .filter(r -> r.format() == format)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No renderer for format " + format));
    }

    // Property 1: every format has a renderer producing non-empty output.
    @Property(tries = 200)
    void everyFormatRendersNonEmpty(@ForAll("formats") ExportFormat format,
                                    @ForAll("models") ExportRenderModel model) {
        String out = rendererFor(format).render(model);
        assertThat(out).isNotBlank();
    }

    @Property(tries = 1)
    void aRendererExistsForEveryFormat() {
        for (ExportFormat format : ExportFormat.values()) {
            assertThat(rendererFor(format)).as("renderer for %s", format).isNotNull();
        }
    }

    // Property 2: determinism.
    @Property(tries = 200)
    void renderingIsDeterministic(@ForAll("formats") ExportFormat format,
                                  @ForAll("models") ExportRenderModel model) {
        SkillExportRenderer renderer = rendererFor(format);
        assertThat(renderer.render(model)).isEqualTo(renderer.render(model));
    }

    // Property 3 + 4: name and resolved instructions are present when non-blank.
    @Property(tries = 300)
    void outputContainsNameAndInstructions(@ForAll("formats") ExportFormat format,
                                           @ForAll("nonBlankModels") ExportRenderModel model) {
        String out = rendererFor(format).render(model);
        assertThat(out).contains(model.name().strip());
        assertThat(out).contains(model.instructions().strip());
    }

    // Property 9: empty instructions still yield a well-formed artifact with the name.
    @Property(tries = 200)
    void emptyInstructionsStillWellFormed(@ForAll("formats") ExportFormat format,
                                          @ForAll("nonBlankNames") String name) {
        ExportRenderModel model = new ExportRenderModel(
                name, "slug", null, "", null, null, null, null, null, null);
        String out = rendererFor(format).render(model);
        assertThat(out).isNotBlank();
        assertThat(out).contains(name.strip());
    }

    // Property 8: fence safety.
    @Property(tries = 300)
    void fenceIsLongerThanAnyBacktickRun(@ForAll("backtickyContent") String content) {
        String fenced = MarkdownFence.fence(content);
        int longestInContent = MarkdownFence.longestBacktickRun(content);
        int fenceLen = MarkdownFence.longestBacktickRun(fenced.substring(0, fenced.indexOf('\n')));
        assertThat(fenceLen).isGreaterThan(longestInContent);
        assertThat(fenceLen).isGreaterThanOrEqualTo(3);
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<ExportFormat> formats() {
        return Arbitraries.of(ExportFormat.values());
    }

    @Provide
    Arbitrary<String> nonBlankNames() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(40)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<String> optionalText() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(120)
        );
    }

    @Provide
    Arbitrary<ExportRenderModel> models() {
        return Combinators.combine(
                nonBlankNames(),
                optionalText(),
                optionalText(),
                optionalText(),
                optionalText(),
                optionalText(),
                optionalText()
        ).as((name, description, instructions, applicability, whenToUse, limitations, examples) ->
                new ExportRenderModel(name, "slug", description, instructions,
                        applicability, whenToUse, limitations, examples, null, "low"));
    }

    @Provide
    Arbitrary<ExportRenderModel> nonBlankModels() {
        Arbitrary<String> instructions = Arbitraries.strings().alpha().numeric()
                .ofMinLength(3).ofMaxLength(200).filter(s -> !s.isBlank());
        return Combinators.combine(nonBlankNames(), instructions).as((name, instr) ->
                new ExportRenderModel(name, "slug", "desc", instr,
                        "applies", "when", "limits", "examples", "cursor", "high"));
    }

    @Provide
    Arbitrary<String> backtickyContent() {
        // Content mixing text with backtick runs of varying length, including long runs.
        return Arbitraries.strings()
                .withChars('`', 'a', 'b', 'c', '\n', ' ')
                .ofMinLength(0).ofMaxLength(60);
    }
}
