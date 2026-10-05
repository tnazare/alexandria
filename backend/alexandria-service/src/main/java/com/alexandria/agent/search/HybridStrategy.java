package com.alexandria.agent.search;

import com.alexandria.config.SearchProperties;
import com.alexandria.skill.CandidateProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hybrid retrieval strategy: runs <em>both</em> the full-text ({@link FtsStrategy}) and semantic
 * ({@link SemanticStrategy}) strategies, fuses their ranked lists into one, and trims to {@code
 * limit}. Registering this component enables {@code mode=hybrid} — {@link SearchStrategySelector}
 * discovers it by {@link #mode()} so the "not yet enabled" error no longer fires for that mode.
 *
 * <h2>Over-fetch</h2>
 * Each source list is over-fetched to {@code limit * }{@value #OVER_FETCH_FACTOR} (floored at
 * {@value #MIN_OVER_FETCH}) before fusion. Fusion can promote a document that neither list ranked in
 * its own top-{@code limit} — e.g. a skill ranked 8th by FTS and 7th by semantic can beat one ranked
 * 1st by FTS but absent from semantic. Over-fetching gives fusion that wider pool while still bounding
 * the work the two underlying queries do. The factor is a pragmatic default for the benchmark's
 * 20–500 skill corpora, not a tuned constant.
 *
 * <h2>Fusion ({@code alexandria.search.hybrid.fusion})</h2>
 * <ul>
 *   <li><b>{@code rrf} (default)</b> — Reciprocal Rank Fusion:
 *       {@code score(d) = Σ 1 / (rrfK + rank_i(d))} over the two lists, where {@code rank_i(d)} is
 *       the 1-based position of {@code d} in list {@code i} (a document absent from a list contributes
 *       nothing from that list). Rank-based, so it needs no score normalization between {@code
 *       ts_rank} and cosine similarity — the two live on different scales. {@code rrfK} is
 *       env-configurable (default 60).</li>
 *   <li><b>{@code weighted}</b> — normalized-score blend: each list's scores are min-max normalized to
 *       {@code [0,1]} independently, then combined as
 *       {@code ftsWeight * ftsNorm(d) + semanticWeight * semanticNorm(d)} (a document absent from a
 *       list contributes 0 for that list). Weights are env-configurable (default 0.5 / 0.5). When a
 *       list's scores are all equal (zero spread), every member of that list normalizes to 1.0 so a
 *       degenerate list does not silently vanish from the blend.</li>
 * </ul>
 *
 * <h2>Deterministic tiebreak</h2>
 * Candidates are ordered by fused score descending, then by {@code skillId} ascending as a stable
 * secondary key. {@link CandidateProjection} intentionally carries no {@code updated_at}, so unlike
 * the FTS SQL (which breaks ties on {@code updated_at DESC, id}) the fused ordering uses the skill id
 * as its stable key. This keeps hybrid ordering fully reproducible across runs for identical inputs —
 * the property the benchmark depends on.
 *
 * <h2>Match mechanisms (R5.4)</h2>
 * The fusion tracks, per {@code skillId}, which source list(s) surfaced the candidate, so the
 * returned {@link SearchOutcome} reports {@link MatchMechanism#FTS}, {@link MatchMechanism#SEMANTIC},
 * or both — letting {@code ResolverServiceImpl.matchReasons} be honest about why a skill ranked.
 */
@Component
public class HybridStrategy implements SearchStrategy {

    static final String MODE = "hybrid";

    /** Multiplier applied to {@code limit} when over-fetching each source list before fusion. */
    static final int OVER_FETCH_FACTOR = 5;

    /** Floor for the over-fetch size, so a tiny {@code limit} still gives fusion a usable pool. */
    static final int MIN_OVER_FETCH = 50;

    static final String FUSION_WEIGHTED = "weighted";

    private static final Logger log = LoggerFactory.getLogger(HybridStrategy.class);

    private final FtsStrategy ftsStrategy;
    private final SemanticStrategy semanticStrategy;
    private final SearchProperties searchProperties;

    public HybridStrategy(FtsStrategy ftsStrategy,
                          SemanticStrategy semanticStrategy,
                          SearchProperties searchProperties) {
        this.ftsStrategy = ftsStrategy;
        this.semanticStrategy = semanticStrategy;
        this.searchProperties = searchProperties;
    }

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
        int overFetch = Math.max(MIN_OVER_FETCH, Math.multiplyExact(limit, OVER_FETCH_FACTOR));

        List<CandidateProjection> ftsList =
                ftsStrategy.find(orgId, userId, isOrgAdmin, includeArchived, query, overFetch)
                        .candidates();
        List<CandidateProjection> semanticList =
                semanticStrategy.find(orgId, userId, isOrgAdmin, includeArchived, query, overFetch)
                        .candidates();

        String fusion = searchProperties.hybrid().fusion();
        List<FusedCandidate> fused = FUSION_WEIGHTED.equalsIgnoreCase(fusion)
                ? fuseWeighted(ftsList, semanticList)
                : fuseRrf(ftsList, semanticList);

        if (log.isDebugEnabled()) {
            log.debug("Hybrid resolve (fusion={}): {} fts + {} semantic -> {} fused, trimmed to {}",
                    fusion, ftsList.size(), semanticList.size(), fused.size(), limit);
        }

        List<CandidateProjection> candidates = new ArrayList<>(Math.min(limit, fused.size()));
        Map<UUID, Set<MatchMechanism>> mechanisms = new LinkedHashMap<>();
        for (int i = 0; i < fused.size() && i < limit; i++) {
            FusedCandidate fc = fused.get(i);
            candidates.add(fc.candidate());
            mechanisms.put(fc.candidate().getSkillId(), Set.copyOf(fc.mechanisms()));
        }
        return new SearchOutcome(List.copyOf(candidates), Map.copyOf(mechanisms));
    }

    // ---- Reciprocal Rank Fusion -------------------------------------------------------------

    private List<FusedCandidate> fuseRrf(List<CandidateProjection> ftsList,
                                         List<CandidateProjection> semanticList) {
        int rrfK = searchProperties.hybrid().rrfK();
        Map<UUID, Accumulator> byId = new LinkedHashMap<>();

        accumulateRrf(byId, ftsList, MatchMechanism.FTS, rrfK);
        accumulateRrf(byId, semanticList, MatchMechanism.SEMANTIC, rrfK);

        return order(byId);
    }

    private static void accumulateRrf(Map<UUID, Accumulator> byId, List<CandidateProjection> list,
                                      MatchMechanism mechanism, int rrfK) {
        for (int i = 0; i < list.size(); i++) {
            CandidateProjection c = list.get(i);
            int rank = i + 1; // 1-based rank
            double contribution = 1.0 / (rrfK + rank);
            byId.computeIfAbsent(c.getSkillId(), id -> new Accumulator(c))
                    .add(contribution, mechanism);
        }
    }

    // ---- Weighted normalized-score blend ----------------------------------------------------

    private List<FusedCandidate> fuseWeighted(List<CandidateProjection> ftsList,
                                              List<CandidateProjection> semanticList) {
        double ftsWeight = searchProperties.hybrid().ftsWeight();
        double semanticWeight = searchProperties.hybrid().semanticWeight();
        Map<UUID, Accumulator> byId = new LinkedHashMap<>();

        accumulateWeighted(byId, ftsList, MatchMechanism.FTS, ftsWeight);
        accumulateWeighted(byId, semanticList, MatchMechanism.SEMANTIC, semanticWeight);

        return order(byId);
    }

    private static void accumulateWeighted(Map<UUID, Accumulator> byId,
                                           List<CandidateProjection> list,
                                           MatchMechanism mechanism, double weight) {
        if (list.isEmpty()) {
            return;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (CandidateProjection c : list) {
            double s = c.getScore();
            min = Math.min(min, s);
            max = Math.max(max, s);
        }
        double spread = max - min;
        for (CandidateProjection c : list) {
            // Degenerate (all-equal) list: every member normalizes to 1.0 so it still contributes.
            double norm = spread > 0 ? (c.getScore() - min) / spread : 1.0;
            byId.computeIfAbsent(c.getSkillId(), id -> new Accumulator(c))
                    .add(weight * norm, mechanism);
        }
    }

    // ---- Shared ordering --------------------------------------------------------------------

    /**
     * Orders accumulated candidates by fused score descending, then {@code skillId} ascending as the
     * deterministic stable tiebreak.
     */
    private static List<FusedCandidate> order(Map<UUID, Accumulator> byId) {
        List<FusedCandidate> fused = new ArrayList<>(byId.size());
        for (Accumulator acc : byId.values()) {
            fused.add(new FusedCandidate(acc.candidate, acc.score, acc.mechanisms));
        }
        fused.sort(Comparator
                .comparingDouble(FusedCandidate::score).reversed()
                .thenComparing(fc -> fc.candidate().getSkillId()));
        return fused;
    }

    /** Mutable per-candidate fusion accumulator (score + the mechanisms that surfaced it). */
    private static final class Accumulator {
        private final CandidateProjection candidate;
        private double score;
        private final EnumSet<MatchMechanism> mechanisms = EnumSet.noneOf(MatchMechanism.class);

        private Accumulator(CandidateProjection candidate) {
            this.candidate = candidate;
        }

        private void add(double contribution, MatchMechanism mechanism) {
            this.score += contribution;
            this.mechanisms.add(mechanism);
        }
    }

    /** A fused candidate with its combined score and the mechanisms that surfaced it. */
    private record FusedCandidate(CandidateProjection candidate, double score,
                                  Set<MatchMechanism> mechanisms) {
    }
}
