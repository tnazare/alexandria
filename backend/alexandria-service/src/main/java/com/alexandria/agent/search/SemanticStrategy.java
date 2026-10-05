package com.alexandria.agent.search;

import com.alexandria.embedding.EmbeddingProvider;
import com.alexandria.embedding.EmbeddingService;
import com.alexandria.skill.CandidateProjection;
import com.alexandria.skill.SkillResolverRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Semantic (vector) retrieval strategy. Embeds the free-text query with the active
 * {@link EmbeddingProvider}, serializes the vector to a pgvector literal, and ranks candidates by
 * cosine distance via {@link SkillResolverRepository#findVisibleCandidatesSemantic}.
 *
 * <p>The repository query carries the <em>identical</em> organization filter, visibility
 * disjunction, and lifecycle/archived predicates as the FTS query, so switching modes changes only
 * the ranking mechanism — never which skills a caller is allowed to see (R4.1, R4.2). Skills without
 * an embedding yet are excluded by the query's {@code JOIN skill_embeddings} (R4.3).</p>
 *
 * <p>Registering this component wires {@code mode=semantic}: {@link SearchStrategySelector} discovers
 * it by {@link #mode()} and selects it when {@code alexandria.search.mode=semantic}, so no
 * "not yet enabled" error is thrown for that mode anymore.</p>
 *
 * <p>For observability of partial-backfill states (R4.3), the count of visible-but-unembedded skills
 * is logged at debug on each resolve via {@link EmbeddingService#countUnembedded(UUID)} — a nonzero
 * count means semantic ranking is seeing an incomplete corpus.</p>
 */
@Component
public class SemanticStrategy implements SearchStrategy {

    static final String MODE = "semantic";

    private static final Logger log = LoggerFactory.getLogger(SemanticStrategy.class);

    private final SkillResolverRepository resolverRepository;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingService embeddingService;

    public SemanticStrategy(SkillResolverRepository resolverRepository,
                            EmbeddingProvider embeddingProvider,
                            EmbeddingService embeddingService) {
        this.resolverRepository = resolverRepository;
        this.embeddingProvider = embeddingProvider;
        this.embeddingService = embeddingService;
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
        if (log.isDebugEnabled()) {
            long unembedded = embeddingService.countUnembedded(orgId);
            if (unembedded > 0) {
                log.debug("Semantic resolve for org {}: {} visible skill(s) have no embedding yet "
                        + "and are excluded from ranking (R4.3)", orgId, unembedded);
            }
        }

        float[] queryVector = embeddingProvider.embed(query);
        String queryLiteral = EmbeddingService.toVectorLiteral(queryVector);

        List<CandidateProjection> candidates = resolverRepository.findVisibleCandidatesSemantic(
                orgId, userId, isOrgAdmin, includeArchived, queryLiteral, limit);
        return SearchOutcome.ofSingleMechanism(candidates, MatchMechanism.SEMANTIC);
    }
}
