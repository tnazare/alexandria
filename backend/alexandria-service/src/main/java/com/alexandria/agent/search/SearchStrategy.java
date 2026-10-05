package com.alexandria.agent.search;

import com.alexandria.skill.CandidateProjection;

import java.util.UUID;

/**
 * Retrieval strategy for the agent-facing Skill Resolver. Each mode (full-text, semantic, hybrid)
 * is a small, independently testable component that returns a ranked list of
 * {@link CandidateProjection} wrapped in a {@link SearchOutcome} (which also records which
 * {@link MatchMechanism}(s) surfaced each candidate), so {@code ResolverServiceImpl.doResolve} is a
 * single lookup over the active strategy and everything downstream (version resolution, result
 * assembly, adapters) is unchanged.
 *
 * <p>Strategies encapsulate <em>how</em> candidates are surfaced; ACL/visibility, lifecycle, and
 * archived predicates are enforced inside each strategy's query so no mode can leak restricted
 * skills. The active strategy is selected once from {@code SearchProperties.mode}.</p>
 */
public interface SearchStrategy {

    /**
     * The {@code alexandria.search.mode} value this strategy serves: {@code fts} | {@code semantic}
     * | {@code hybrid}. Used to select the active strategy at resolve time.
     */
    String mode();

    /**
     * Returns up to {@code limit} ACL-visible, non-Blocked skill candidates for {@code query},
     * ordered by relevance with a deterministic tiebreak. Mirrors the contract of
     * {@code SkillResolverRepository.findVisibleCandidates}: organization skills to any org member;
     * team skills to owner-team members; private to the creator; restricted to the creator or
     * explicitly granted users; {@code isOrgAdmin} bypasses visibility. Blocked skills are always
     * excluded; Archived skills are excluded unless {@code includeArchived}.
     *
     * @param orgId           the caller's organization (scopes every result)
     * @param userId          the caller's user id (drives team/private/restricted visibility)
     * @param isOrgAdmin      whether the caller bypasses visibility rules
     * @param includeArchived whether Archived skills are included
     * @param query           the free-text resolve query (never blank at this point)
     * @param limit           the clamped maximum number of candidates to return
     * @return the ranked candidate projections (identity + governance + score; no instruction
     *         bodies) plus the per-candidate match mechanism(s), wrapped in a {@link SearchOutcome}
     */
    SearchOutcome find(
            UUID orgId,
            UUID userId,
            boolean isOrgAdmin,
            boolean includeArchived,
            String query,
            int limit);
}
