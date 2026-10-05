package com.alexandria.embedding;

import com.alexandria.config.SearchProperties;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates and persists per-skill embeddings so the semantic/hybrid resolver can rank by meaning.
 *
 * <p>The source text mirrors what full-text search indexes (R3.3): skill {@code name} and
 * {@code description} plus the current version's {@code canonicalInstructions}, {@code applicability},
 * {@code whenToUse}, and {@code examples}. Assembling the same fields the FTS path uses keeps the two
 * retrieval modes seeing the same text, so a hybrid comparison moves only the ranking mechanism.</p>
 *
 * <p>{@link #reindex(UUID)} is called alongside {@code rebuildSearchVector} at the skill create/update
 * boundary (R3.4). It hashes the assembled source and skips the (potentially expensive) embed+upsert
 * when the hash is unchanged, so a no-op edit does not re-embed. {@link #backfillAll(UUID)} is the
 * bulk path for existing rows.</p>
 *
 * <p>Reindex is a safe no-op when retrieval mode is {@code fts} — the default. Existing FTS-only
 * deployments carry no pgvector table semantics on the write path, and embedding on every write would
 * be wasted work there. When mode is {@code semantic} or {@code hybrid}, writes keep embeddings in
 * lockstep with the indexed text. The design places reindex at the write boundary "alongside
 * rebuildSearchVector"; gating it on mode keeps that wiring while honoring "embedding on write must
 * not break fts-only deployments."</p>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingRepository embeddingRepository;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SearchProperties searchProperties;

    public EmbeddingService(EmbeddingProvider embeddingProvider,
                            EmbeddingRepository embeddingRepository,
                            SkillRepository skillRepository,
                            SkillVersionRepository skillVersionRepository,
                            SearchProperties searchProperties) {
        this.embeddingProvider = embeddingProvider;
        this.embeddingRepository = embeddingRepository;
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.searchProperties = searchProperties;
    }

    /** True when the active retrieval mode uses embeddings (semantic or hybrid). */
    private boolean embeddingsActive() {
        String mode = searchProperties.mode();
        return "semantic".equals(mode) || "hybrid".equals(mode);
    }

    /**
     * (Re)generates and persists the embedding for a single skill, skipping the embed+upsert when the
     * assembled source text is unchanged since the last run. A no-op when the active mode is
     * {@code fts}, so existing FTS-only deployments are unaffected by the write path.
     *
     * <p>Called at the skill create/update boundary alongside {@code rebuildSearchVector} (R3.4).</p>
     *
     * @param skillId the skill to reindex
     */
    @Transactional
    public void reindex(UUID skillId) {
        if (!embeddingsActive()) {
            return;
        }
        SkillEntity skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {
            log.debug("reindex skipped: skill {} not found", skillId);
            return;
        }
        reindexSkill(skill);
    }

    /**
     * Backfills embeddings for every eligible skill in an organization: non-Blocked skills that have
     * a current version. Idempotent — skills whose source text has not changed since their last embed
     * are skipped via the source-hash check. Returns the number of embeddings actually written.
     *
     * @param organizationId the organization to backfill
     * @return count of skills (re)embedded (excludes hash-skips)
     */
    @Transactional
    public int backfillAll(UUID organizationId) {
        List<SkillEntity> skills = skillRepository.findAll().stream()
                .filter(s -> organizationId.equals(s.getOrganizationId()))
                .filter(s -> !"Blocked".equals(s.getLifecycleStatus()))
                .filter(s -> s.getCurrentVersionId() != null)
                .toList();

        int written = 0;
        for (SkillEntity skill : skills) {
            if (reindexSkill(skill)) {
                written++;
            }
        }
        log.info("Embedding backfill for org {}: {} eligible, {} (re)embedded, {} unchanged",
                organizationId, skills.size(), written, skills.size() - written);
        return written;
    }

    /**
     * Count of eligible skills in an organization that have no embedding yet. Exposed for
     * observability so a partial-backfill state is detectable (R4.3).
     */
    public long countUnembedded(UUID organizationId) {
        return embeddingRepository.countUnembedded(organizationId);
    }

    /**
     * Core per-skill reindex: assemble source, hash, skip-if-unchanged, embed, upsert.
     *
     * @return {@code true} if an embedding was (re)written, {@code false} if skipped as unchanged
     */
    private boolean reindexSkill(SkillEntity skill) {
        String source = assembleSource(skill);
        String hash = sourceHash(source);

        Optional<String> existing = embeddingRepository.findSourceHash(skill.getId());
        if (existing.isPresent() && existing.get().equals(hash)) {
            log.debug("reindex skipped (unchanged): skill {}", skill.getId());
            return false;
        }

        float[] vector = embeddingProvider.embed(source);
        String literal = toVectorLiteral(vector);
        embeddingRepository.upsert(
                skill.getId(), skill.getOrganizationId(), literal,
                embeddingProvider.modelId(), hash);
        log.debug("reindexed skill {} (modelId={})", skill.getId(), embeddingProvider.modelId());
        return true;
    }

    /**
     * Assembles the embedding source text, mirroring the FTS A/B/C/D source fields (R3.3): skill
     * {@code name}, {@code description}, then the current version's {@code canonicalInstructions},
     * {@code applicability}, {@code whenToUse}, and {@code examples}, each null-safe and joined with a
     * single space in a fixed order so the hash is stable.
     *
     * <p>Package-private for unit tests that assert the assembled source matches the FTS source.</p>
     */
    String assembleSource(SkillEntity skill) {
        StringBuilder sb = new StringBuilder();
        append(sb, skill.getName());
        append(sb, skill.getDescription());

        if (skill.getCurrentVersionId() != null) {
            SkillVersionEntity version =
                    skillVersionRepository.findById(skill.getCurrentVersionId()).orElse(null);
            if (version != null) {
                append(sb, version.getCanonicalInstructions());
                append(sb, version.getApplicability());
                append(sb, version.getWhenToUse());
                append(sb, version.getExamples());
            }
        }
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value.trim());
        }
    }

    /**
     * SHA-256 of the assembled source text, hex-encoded. A stable hash lets {@link #reindexSkill}
     * skip re-embedding when nothing an embedding depends on has changed.
     *
     * <p>Package-private for unit tests of the hash-skip logic.</p>
     */
    String sourceHash(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required algorithm on every JVM; this cannot happen in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Serializes a float vector to a pgvector text literal, e.g. {@code [0.1,0.2,0.3]}, for storage
     * via {@code CAST(? AS vector)}. Uses {@link Float#toString(float)} so each component round-trips
     * exactly.
     *
     * <p>Also used by the semantic retrieval strategy to serialize the query embedding before it is
     * cast to a {@code vector} in SQL, so writes and reads share one literal format.</p>
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
