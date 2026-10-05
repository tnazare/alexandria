package com.alexandria.agent.search;

import com.alexandria.skill.CandidateProjection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The result of a {@link SearchStrategy#find} call: the ranked candidate list plus a per-candidate
 * record of which {@link MatchMechanism}(s) surfaced each one.
 *
 * <p>Carrying the mechanisms alongside the list — rather than on {@link CandidateProjection} — keeps
 * the repository projection contract untouched (it stays a pure identity+governance+score read) while
 * giving {@code ResolverServiceImpl.matchReasons} the information it needs to be honest about why a
 * skill ranked (R5.4). The map is keyed by {@code skillId} so a candidate can be looked up regardless
 * of list position after fusion.</p>
 *
 * @param candidates ranked candidates (relevance order, deterministic tiebreak), trimmed to the limit
 * @param mechanisms per-{@code skillId} set of mechanisms that surfaced the candidate; for a single
 *                   mode this is one mechanism per candidate, for hybrid it may be one or both
 */
public record SearchOutcome(
        List<CandidateProjection> candidates,
        Map<UUID, Set<MatchMechanism>> mechanisms) {

    /**
     * Builds an outcome where every candidate was surfaced by the same single mechanism — the shape
     * {@link FtsStrategy} and {@link SemanticStrategy} produce.
     */
    public static SearchOutcome ofSingleMechanism(List<CandidateProjection> candidates,
                                                  MatchMechanism mechanism) {
        Map<UUID, Set<MatchMechanism>> mechanisms = new java.util.HashMap<>(candidates.size());
        for (CandidateProjection c : candidates) {
            mechanisms.put(c.getSkillId(), Set.of(mechanism));
        }
        return new SearchOutcome(List.copyOf(candidates), Map.copyOf(mechanisms));
    }

    /** The mechanisms that surfaced {@code skillId}, or an empty set if the candidate is unknown. */
    public Set<MatchMechanism> mechanismsFor(UUID skillId) {
        return mechanisms.getOrDefault(skillId, Set.of());
    }
}
