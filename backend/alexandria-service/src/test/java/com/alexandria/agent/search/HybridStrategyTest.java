package com.alexandria.agent.search;

import com.alexandria.config.SearchProperties;
import com.alexandria.skill.CandidateProjection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HybridStrategy} fusion. All deterministic and DB-free: the FTS and semantic
 * strategies are stubbed to return synthetic {@link CandidateProjection} lists, so the tests exercise
 * only the fusion math, ordering, tiebreak, trimming, and per-candidate mechanism tracking.
 *
 * <p>Validates:
 * <ul>
 *   <li>R5.1 — hybrid combines the two candidate sets into one ranked list via a documented
 *       deterministic fusion (RRF default, weighted alternative).</li>
 *   <li>R5.2 — fusion parameters (RRF k, weights) are honored from {@link SearchProperties}.</li>
 *   <li>R5.3 — fused result respects {@code limit} and a deterministic tiebreak.</li>
 *   <li>R5.4 — the outcome records which mechanism(s) surfaced each candidate.</li>
 * </ul>
 */
@Tag("skill-resolver")
class HybridStrategyTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    // Fixed skill ids with a known lexicographic order for deterministic tiebreak assertions.
    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID ID_D = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    private static CandidateProjection candidate(UUID skillId, double score) {
        CandidateProjection c = mock(CandidateProjection.class);
        when(c.getSkillId()).thenReturn(skillId);
        when(c.getScore()).thenReturn(score);
        return c;
    }

    /** Builds a HybridStrategy whose FTS/semantic sub-strategies return the given synthetic lists. */
    private HybridStrategy hybridWith(SearchProperties.HybridProps hybridProps,
                                      List<CandidateProjection> ftsList,
                                      List<CandidateProjection> semanticList) {
        SearchProperties props = new SearchProperties("hybrid", null, null, hybridProps);

        // Build the outcomes before stubbing: ofSingleMechanism invokes getSkillId() on the mock
        // candidates, which must not happen inside an in-progress when(...).thenReturn(...).
        SearchOutcome ftsOutcome = SearchOutcome.ofSingleMechanism(ftsList, MatchMechanism.FTS);
        SearchOutcome semanticOutcome =
                SearchOutcome.ofSingleMechanism(semanticList, MatchMechanism.SEMANTIC);

        FtsStrategy fts = mock(FtsStrategy.class);
        when(fts.find(eq(ORG), eq(USER), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(ftsOutcome);

        SemanticStrategy semantic = mock(SemanticStrategy.class);
        when(semantic.find(eq(ORG), eq(USER), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(semanticOutcome);

        return new HybridStrategy(fts, semantic, props);
    }

    private static List<UUID> ids(SearchOutcome outcome) {
        List<UUID> ids = new ArrayList<>();
        outcome.candidates().forEach(c -> ids.add(c.getSkillId()));
        return ids;
    }

    // ---- RRF (default) ----------------------------------------------------------------------

    @Test
    void rrfRanksDocumentPresentInBothListsAboveSingleListDocuments() {
        // FTS:      A(1), B(2), C(3)
        // Semantic: C(1), A(2), D(3)
        // With rrfK=60: A = 1/61 + 1/62; C = 1/63 + 1/61; B = 1/62; D = 1/63.
        // C (ranked high in both) and A (high in both) lead; B and D (single-list) trail.
        List<CandidateProjection> fts = List.of(
                candidate(ID_A, 0.9), candidate(ID_B, 0.8), candidate(ID_C, 0.7));
        List<CandidateProjection> semantic = List.of(
                candidate(ID_C, 0.95), candidate(ID_A, 0.85), candidate(ID_D, 0.6));

        HybridStrategy hybrid = hybridWith(new SearchProperties.HybridProps("rrf", null, null, 60),
                fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        // Compute expected RRF order explicitly.
        // A = 1/61 + 1/62 = 0.016393 + 0.016129 = 0.032522
        // C = 1/63 + 1/61 = 0.015873 + 0.016393 = 0.032266
        // B = 1/62 = 0.016129
        // D = 1/63 = 0.015873
        assertThat(ids(outcome)).containsExactly(ID_A, ID_C, ID_B, ID_D);
    }

    @Test
    void rrfTieBreaksBySkillIdAscendingWhenScoresEqual() {
        // Each doc appears once at the same rank in exactly one list -> equal RRF scores.
        // FTS:      A(1)        Semantic: B(1)
        // Both score 1/(60+1). Tiebreak: skillId ascending -> A before B.
        List<CandidateProjection> fts = List.of(candidate(ID_B, 0.5));
        List<CandidateProjection> semantic = List.of(candidate(ID_A, 0.5));

        HybridStrategy hybrid = hybridWith(new SearchProperties.HybridProps("rrf", null, null, 60),
                fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        assertThat(ids(outcome)).containsExactly(ID_A, ID_B);
    }

    @Test
    void rrfKParameterIsHonored() {
        // A ranked 1 in FTS and 3 in semantic; B ranked 2 in both.
        // FTS:      A(1), B(2)
        // Semantic: C(1), B(2), A(3)
        // Small k amplifies rank differences. Verify the configured k changes the arithmetic by
        // comparing against a hand-computed order for k=1.
        // k=1: A = 1/2 + 1/4 = 0.75 ; B = 1/3 + 1/3 = 0.6667 ; C = 1/2 = 0.5
        List<CandidateProjection> fts = List.of(candidate(ID_A, 0.9), candidate(ID_B, 0.8));
        List<CandidateProjection> semantic = List.of(
                candidate(ID_C, 0.95), candidate(ID_B, 0.7), candidate(ID_A, 0.5));

        HybridStrategy hybrid = hybridWith(new SearchProperties.HybridProps("rrf", null, null, 1),
                fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        assertThat(ids(outcome)).containsExactly(ID_A, ID_B, ID_C);
    }

    // ---- Weighted ---------------------------------------------------------------------------

    @Test
    void weightedBlendUsesMinMaxNormalizedScoresAndWeights() {
        // FTS scores: A=1.0, B=0.0  -> norm A=1, B=0
        // Semantic scores: B=1.0, A=0.0 -> norm B=1, A=0
        // Equal weights 0.5/0.5: A = 0.5*1 + 0.5*0 = 0.5 ; B = 0.5*0 + 0.5*1 = 0.5 -> tie.
        // Tiebreak skillId ascending -> A before B.
        List<CandidateProjection> fts = List.of(candidate(ID_A, 1.0), candidate(ID_B, 0.0));
        List<CandidateProjection> semantic = List.of(candidate(ID_B, 1.0), candidate(ID_A, 0.0));

        HybridStrategy hybrid = hybridWith(
                new SearchProperties.HybridProps("weighted", 0.5, 0.5, null), fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        assertThat(ids(outcome)).containsExactly(ID_A, ID_B);
    }

    @Test
    void weightedBlendRespectsAsymmetricWeights() {
        // FTS:      A=1.0, B=0.0  -> norm A=1, B=0
        // Semantic: B=1.0, A=0.0  -> norm B=1, A=0
        // ftsWeight=0.8, semanticWeight=0.2: A = 0.8 ; B = 0.2 -> A clearly first.
        List<CandidateProjection> fts = List.of(candidate(ID_A, 1.0), candidate(ID_B, 0.0));
        List<CandidateProjection> semantic = List.of(candidate(ID_B, 1.0), candidate(ID_A, 0.0));

        HybridStrategy hybrid = hybridWith(
                new SearchProperties.HybridProps("weighted", 0.8, 0.2, null), fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        assertThat(ids(outcome)).containsExactly(ID_A, ID_B);
    }

    @Test
    void weightedBlendTreatsAllEqualListAsFullyNormalized() {
        // FTS list has zero spread (all 0.5). Each FTS member normalizes to 1.0 so the list still
        // contributes rather than vanishing. Only A appears, in FTS only.
        List<CandidateProjection> fts = List.of(candidate(ID_A, 0.5), candidate(ID_B, 0.5));
        List<CandidateProjection> semantic = List.of();

        HybridStrategy hybrid = hybridWith(
                new SearchProperties.HybridProps("weighted", 0.5, 0.5, null), fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        // Both normalize to 1.0 -> both score 0.5 -> tiebreak by skillId: A before B.
        assertThat(ids(outcome)).containsExactly(ID_A, ID_B);
    }

    // ---- Trimming + mechanisms --------------------------------------------------------------

    @Test
    void fusedResultRespectsLimit() {
        List<CandidateProjection> fts = List.of(
                candidate(ID_A, 0.9), candidate(ID_B, 0.8), candidate(ID_C, 0.7));
        List<CandidateProjection> semantic = List.of(candidate(ID_D, 0.95));

        HybridStrategy hybrid = hybridWith(new SearchProperties.HybridProps("rrf", null, null, 60),
                fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 2);

        assertThat(outcome.candidates()).hasSize(2);
    }

    @Test
    void mechanismsReflectWhichListSurfacedEachCandidate() {
        // A: both lists. B: FTS only. C: semantic only.
        List<CandidateProjection> fts = List.of(candidate(ID_A, 0.9), candidate(ID_B, 0.8));
        List<CandidateProjection> semantic = List.of(candidate(ID_A, 0.95), candidate(ID_C, 0.6));

        HybridStrategy hybrid = hybridWith(new SearchProperties.HybridProps("rrf", null, null, 60),
                fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        assertThat(outcome.mechanismsFor(ID_A))
                .containsExactlyInAnyOrder(MatchMechanism.FTS, MatchMechanism.SEMANTIC);
        assertThat(outcome.mechanismsFor(ID_B)).containsExactly(MatchMechanism.FTS);
        assertThat(outcome.mechanismsFor(ID_C)).containsExactly(MatchMechanism.SEMANTIC);
    }

    @Test
    void deduplicatesDocumentPresentInBothLists() {
        List<CandidateProjection> fts = List.of(candidate(ID_A, 0.9));
        List<CandidateProjection> semantic = List.of(candidate(ID_A, 0.95));

        HybridStrategy hybrid = hybridWith(new SearchProperties.HybridProps("rrf", null, null, 60),
                fts, semantic);
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        assertThat(ids(outcome)).containsExactly(ID_A);
    }

    @Test
    void emptyBothListsYieldsEmptyOutcome() {
        HybridStrategy hybrid = hybridWith(new SearchProperties.HybridProps("rrf", null, null, 60),
                List.of(), List.of());
        SearchOutcome outcome = hybrid.find(ORG, USER, false, false, "q", 10);

        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.mechanisms()).isEmpty();
    }
}
