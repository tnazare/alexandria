package com.alexandria.agent.search;

import com.alexandria.skill.CandidateProjection;
import com.alexandria.skill.SkillResolverRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Full-text search strategy — today's behavior, verbatim. Delegates straight to
 * {@link SkillResolverRepository#findVisibleCandidates}, which carries the ACL disjunction,
 * lifecycle/archived predicates, {@code ts_rank} ordering, and deterministic tiebreak. This is the
 * default mode, so wiring it through the strategy seam is a no-op for existing deployments.
 */
@Component
@RequiredArgsConstructor
public class FtsStrategy implements SearchStrategy {

    static final String MODE = "fts";

    private final SkillResolverRepository resolverRepository;

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public SearchOutcome find(
            UUID orgId,
            UUID userId,
            boolean isOrgAdmin,
            boolean includeArchived,
            String query,
            int limit) {
        List<CandidateProjection> candidates = resolverRepository.findVisibleCandidates(
                orgId, userId, isOrgAdmin, includeArchived, query, limit);
        return SearchOutcome.ofSingleMechanism(candidates, MatchMechanism.FTS);
    }
}
