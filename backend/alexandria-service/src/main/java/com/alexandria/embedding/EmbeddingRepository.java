package com.alexandria.embedding;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code skill_embeddings} using {@link JdbcTemplate}.
 *
 * <p>This side table stores a pgvector {@code vector(256)} column, which Spring Data JPA / Hibernate
 * cannot map to a portable Java type without a vendor extension. The rows are also written and read
 * through pgvector's own SQL operators ({@code <=>}, {@code ::vector}), so a thin JDBC layer that
 * speaks pgvector literals directly is the right boundary — the rest of the persistence tier remains
 * Spring Data JPA, but the vector column lives behind this repository.</p>
 *
 * <p>Writes go through an idempotent {@code INSERT ... ON CONFLICT (skill_id) DO UPDATE} so a reindex
 * either inserts a new embedding or replaces the existing one in a single round trip. The embedding
 * is serialized to a pgvector text literal ({@code [0.1,0.2,...]}) and cast with
 * {@code CAST(? AS vector)} so no custom JDBC type is required.</p>
 */
@Repository
public class EmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the stored {@code source_hash} for a skill's embedding, or empty if the skill has no
     * embedding row yet. Used to skip re-embedding when the assembled source text is unchanged.
     */
    public Optional<String> findSourceHash(UUID skillId) {
        return jdbcTemplate.query(
                "SELECT source_hash FROM skill_embeddings WHERE skill_id = ?",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(),
                skillId);
    }

    /**
     * Inserts or replaces a skill's embedding in a single statement.
     *
     * @param skillId        the skill the embedding belongs to
     * @param organizationId the owning organization (carried for ACL-direct filtering)
     * @param vectorLiteral  the embedding serialized as a pgvector literal, e.g. {@code [0.1,0.2]}
     * @param modelId        provenance of the vector (the provider's model id)
     * @param sourceHash     hash of the assembled source text
     */
    public void upsert(UUID skillId, UUID organizationId, String vectorLiteral,
                       String modelId, String sourceHash) {
        jdbcTemplate.update("""
                INSERT INTO skill_embeddings
                    (skill_id, organization_id, embedding, model_id, source_hash, updated_at)
                VALUES (?, ?, CAST(? AS vector), ?, ?, now())
                ON CONFLICT (skill_id) DO UPDATE SET
                    organization_id = EXCLUDED.organization_id,
                    embedding       = EXCLUDED.embedding,
                    model_id        = EXCLUDED.model_id,
                    source_hash     = EXCLUDED.source_hash,
                    updated_at      = now()
                """,
                skillId, organizationId, vectorLiteral, modelId, sourceHash);
    }

    /** Removes a skill's embedding row, if any (used when a skill's content can no longer be sourced). */
    public void deleteBySkillId(UUID skillId) {
        jdbcTemplate.update("DELETE FROM skill_embeddings WHERE skill_id = ?", skillId);
    }

    /**
     * Counts skills in an organization that are visible candidates (non-Blocked, have a current
     * version) but have no embedding yet. Exposed for observability so a partial-backfill state is
     * detectable (R4.3) and {@link EmbeddingService#backfillAll(UUID)} progress can be reported.
     */
    public long countUnembedded(UUID organizationId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM skills s
                WHERE s.organization_id = ?
                  AND s.lifecycle_status <> 'Blocked'
                  AND s.current_version_id IS NOT NULL
                  AND NOT EXISTS (
                        SELECT 1 FROM skill_embeddings e WHERE e.skill_id = s.id)
                """,
                Long.class, organizationId);
        return count == null ? 0L : count;
    }
}
