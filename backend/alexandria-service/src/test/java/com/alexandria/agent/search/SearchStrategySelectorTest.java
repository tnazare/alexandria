package com.alexandria.agent.search;

import com.alexandria.config.SearchProperties;
import com.alexandria.skill.CandidateProjection;
import com.alexandria.skill.SkillResolverRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchStrategySelector} and {@link FtsStrategy}: the strategy seam over the
 * existing full-text search.
 *
 * <p>Validates:
 * <ul>
 *   <li>Requirement 6.1 — default mode ({@code fts}) selects the FTS strategy, which delegates to
 *       {@code findVisibleCandidates} verbatim (no behavior change).</li>
 *   <li>Requirement 6.3 — a valid-but-unwired mode ({@code semantic}/{@code hybrid}) fails fast with
 *       "not yet enabled" rather than silently falling back.</li>
 * </ul>
 */
@Tag("skill-resolver")
class SearchStrategySelectorTest {

    private SearchStrategySelector selectorFor(String mode, SearchStrategy... strategies) {
        SearchProperties props = new SearchProperties(mode, null, null, null);
        return new SearchStrategySelector(props, List.of(strategies));
    }

    @Test
    void defaultModeSelectsFtsStrategy() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        FtsStrategy fts = new FtsStrategy(repo);

        SearchStrategySelector selector = selectorFor("fts", fts);

        assertThat(selector.active()).isSameAs(fts);
        assertThat(selector.active().mode()).isEqualTo("fts");
    }

    @Test
    void blankModeDefaultsToFts() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        FtsStrategy fts = new FtsStrategy(repo);

        // SearchProperties normalizes blank -> fts.
        SearchStrategySelector selector = selectorFor("   ", fts);

        assertThat(selector.active()).isSameAs(fts);
    }

    @Test
    void ftsStrategyDelegatesToFindVisibleCandidatesVerbatim() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CandidateProjection c = candidate(UUID.randomUUID(), 0.9);
        when(repo.findVisibleCandidates(orgId, userId, true, false, "query", 25))
                .thenReturn(List.of(c));

        FtsStrategy fts = new FtsStrategy(repo);
        SearchOutcome outcome = fts.find(orgId, userId, true, false, "query", 25);

        assertThat(outcome.candidates()).containsExactly(c);
        // Every FTS candidate is tagged with the FTS mechanism (R5.4 honesty source).
        assertThat(outcome.mechanismsFor(c.getSkillId())).containsExactly(MatchMechanism.FTS);
        verify(repo).findVisibleCandidates(orgId, userId, true, false, "query", 25);
    }

    private static CandidateProjection candidate(UUID skillId, double score) {
        CandidateProjection c = mock(CandidateProjection.class);
        when(c.getSkillId()).thenReturn(skillId);
        when(c.getScore()).thenReturn(score);
        return c;
    }

    @Test
    void semanticModeNotEnabledWhenNoStrategyRegistered() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        SearchStrategySelector selector = selectorFor("semantic", new FtsStrategy(repo));

        assertThatThrownBy(selector::active)
                .isInstanceOf(SearchModeNotEnabledException.class)
                .hasMessageContaining("semantic")
                .hasMessageContaining("not yet enabled");
    }

    @Test
    void hybridModeNotEnabledWhenNoStrategyRegistered() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        SearchStrategySelector selector = selectorFor("hybrid", new FtsStrategy(repo));

        assertThatThrownBy(selector::active)
                .isInstanceOf(SearchModeNotEnabledException.class)
                .hasMessageContaining("hybrid")
                .hasMessageContaining("not yet enabled");
    }

    @Test
    void selectorPicksStrategyMatchingConfiguredModeAmongSeveral() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        FtsStrategy fts = new FtsStrategy(repo);
        SearchStrategy fake = new SearchStrategy() {
            @Override
            public String mode() {
                return "semantic";
            }

            @Override
            public SearchOutcome find(UUID orgId, UUID userId, boolean isOrgAdmin,
                                      boolean includeArchived, String query, int limit) {
                return SearchOutcome.ofSingleMechanism(List.of(), MatchMechanism.SEMANTIC);
            }
        };

        SearchStrategySelector selector = selectorFor("semantic", fts, fake);

        assertThat(selector.active()).isSameAs(fake);
    }
}
