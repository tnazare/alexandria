package com.alexandria.skill;

import com.alexandria.skill.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Agent-facing retrieval queries for the Skill Resolver.
 *
 * <p>{@link #findVisibleCandidates} intentionally duplicates the visibility disjunction from
 * {@link SkillRepository#findVisibleSkills} rather than sharing SQL, because the resolver needs a
 * lightweight candidate projection (no instruction bodies) with resolver-specific limit/ordering
 * semantics. The parity between the two visibility predicates is guarded by an integration test so
 * the copies cannot drift silently.</p>
 */
public interface SkillResolverRepository extends JpaRepository<SkillEntity, UUID> {

    /**
     * Returns up to {@code limit} ACL-visible, non-Blocked skill candidates matching the full-text
     * {@code query}, ordered by relevance with a deterministic tiebreak.
     *
     * <p>Visibility rules mirror {@code findVisibleSkills}: organization skills to any org member;
     * team skills to owner-team members; private skills to their creator; restricted skills to the
     * creator or explicitly granted users; {@code isOrgAdmin} bypasses all of the above. Blocked
     * skills are always excluded; Archived skills are excluded unless {@code includeArchived}.</p>
     *
     * <p>The projection carries no instruction/example/limitation bodies (Requirement 1.4). Ordering
     * is {@code score DESC, updated_at DESC, id} for determinism (Requirement 1.7).</p>
     */
    @Query(value = """
        SELECT s.id AS skillId,
               s.current_version_id AS skillVersionId,
               s.name AS name,
               s.description AS description,
               s.slug AS slug,
               s.lifecycle_status AS lifecycleStatus,
               s.risk_level AS riskLevel,
               s.visibility AS visibility,
               s.owner_team_id AS ownerTeamId,
               s.deprecation_reason AS deprecationReason,
               ts_rank(s.search_vector, plainto_tsquery('english', :query), 1) AS score
        FROM skills s
        WHERE s.organization_id = :orgId
          AND (
            s.visibility = 'organization'
            OR (s.visibility = 'team' AND s.owner_team_id IN (
                SELECT tm.team_id FROM team_members tm WHERE tm.user_id = :userId))
            OR (s.visibility = 'private' AND s.created_by = :userId)
            OR (s.visibility = 'restricted' AND (
                s.created_by = :userId
                OR EXISTS (SELECT 1 FROM skill_access_grants sag
                           WHERE sag.skill_id = s.id AND sag.user_id = :userId)))
            OR :isOrgAdmin = true
          )
          AND s.lifecycle_status <> 'Blocked'
          AND (:includeArchived = true OR s.lifecycle_status <> 'Archived')
          AND s.current_version_id IS NOT NULL
          AND s.search_vector @@ plainto_tsquery('english', :query)
        ORDER BY score DESC, s.updated_at DESC, s.id
        LIMIT :limit
        """, nativeQuery = true)
    List<CandidateProjection> findVisibleCandidates(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("isOrgAdmin") boolean isOrgAdmin,
            @Param("includeArchived") boolean includeArchived,
            @Param("query") String query,
            @Param("limit") int limit
    );

    /**
     * Semantic sibling of {@link #findVisibleCandidates}: ranks ACL-visible, non-Blocked candidates
     * by vector distance to the query embedding rather than by full-text relevance.
     *
     * <p>The organization filter, visibility disjunction, {@code lifecycle_status <> 'Blocked'},
     * archived predicate, and {@code current_version_id IS NOT NULL} are copied <strong>verbatim</strong>
     * from {@link #findVisibleCandidates} so the two retrieval modes enforce the identical ACL — an
     * integration test guards this parity so the copies cannot drift (R4.4). A semantic query must
     * never surface a skill the FTS query would hide.</p>
     *
     * <p>The {@code JOIN skill_embeddings} naturally excludes skills with no embedding yet (R4.3):
     * un-backfilled skills simply do not participate in semantic ranking rather than crashing or
     * leaking. Candidates are ordered by cosine distance ({@code embedding <=> queryVec}) with the
     * same deterministic tiebreak as FTS ({@code updated_at DESC, id}); the score is reported as
     * {@code 1 - distance} so a higher score means more similar, matching the FTS score orientation.</p>
     *
     * @param queryVec the query embedding serialized as a pgvector literal, e.g. {@code [0.1,0.2,...]},
     *                 cast to {@code vector} in SQL
     */
    @Query(value = """
        SELECT s.id AS skillId,
               s.current_version_id AS skillVersionId,
               s.name AS name,
               s.description AS description,
               s.slug AS slug,
               s.lifecycle_status AS lifecycleStatus,
               s.risk_level AS riskLevel,
               s.visibility AS visibility,
               s.owner_team_id AS ownerTeamId,
               s.deprecation_reason AS deprecationReason,
               1 - (e.embedding <=> CAST(:queryVec AS vector)) AS score
        FROM skills s
        JOIN skill_embeddings e ON e.skill_id = s.id
        WHERE s.organization_id = :orgId
          AND (
            s.visibility = 'organization'
            OR (s.visibility = 'team' AND s.owner_team_id IN (
                SELECT tm.team_id FROM team_members tm WHERE tm.user_id = :userId))
            OR (s.visibility = 'private' AND s.created_by = :userId)
            OR (s.visibility = 'restricted' AND (
                s.created_by = :userId
                OR EXISTS (SELECT 1 FROM skill_access_grants sag
                           WHERE sag.skill_id = s.id AND sag.user_id = :userId)))
            OR :isOrgAdmin = true
          )
          AND s.lifecycle_status <> 'Blocked'
          AND (:includeArchived = true OR s.lifecycle_status <> 'Archived')
          AND s.current_version_id IS NOT NULL
        ORDER BY e.embedding <=> CAST(:queryVec AS vector), s.updated_at DESC, s.id
        LIMIT :limit
        """, nativeQuery = true)
    List<CandidateProjection> findVisibleCandidatesSemantic(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("isOrgAdmin") boolean isOrgAdmin,
            @Param("includeArchived") boolean includeArchived,
            @Param("queryVec") String queryVec,
            @Param("limit") int limit
    );

    /**
     * Loads a skill version by id, scoped to an organization via its parent skill, for fetch.
     * Returns empty when the version does not exist or belongs to another organization. The caller
     * still re-checks per-user visibility via the application layer before rendering.
     */
    @Query("""
        SELECT v FROM SkillVersionEntity v
        WHERE v.id = :skillVersionId
          AND v.skillId IN (SELECT s.id FROM SkillEntity s WHERE s.organizationId = :orgId)
        """)
    java.util.Optional<com.alexandria.skill.entity.SkillVersionEntity> loadVersionForFetch(
            @Param("skillVersionId") UUID skillVersionId,
            @Param("orgId") UUID orgId);

    /**
     * Resolves the latest approved version id for a skill (Requirement 5.2). Eligible only when the
     * skill's lifecycle is an approved state (Company_Approved or Team_Approved); returns the most
     * recently published version. Returns empty when the skill is not approved or has no published
     * version, so the caller excludes it rather than surfacing an unapproved version.
     */
    @Query(value = """
        SELECT v.id
        FROM skill_versions v
        JOIN skills s ON s.id = v.skill_id
        WHERE s.id = :skillId
          AND s.lifecycle_status IN ('Company_Approved', 'Team_Approved')
          AND v.published_at IS NOT NULL
        ORDER BY v.version_number DESC
        LIMIT 1
        """, nativeQuery = true)
    java.util.Optional<UUID> findLatestApprovedVersionId(@Param("skillId") UUID skillId);

    /**
     * Resolves the latest published-or-higher version id for a skill (Requirement 5.3): the most
     * recently published version, regardless of approval state, excluding Blocked skills. Returns
     * empty when the skill has no published version.
     */
    @Query(value = """
        SELECT v.id
        FROM skill_versions v
        JOIN skills s ON s.id = v.skill_id
        WHERE s.id = :skillId
          AND s.lifecycle_status <> 'Blocked'
          AND v.published_at IS NOT NULL
        ORDER BY v.version_number DESC
        LIMIT 1
        """, nativeQuery = true)
    java.util.Optional<UUID> findLatestPublishedVersionId(@Param("skillId") UUID skillId);

    /**
     * Lists ACL-visible, non-Blocked skills for the resident Skill Catalog — <strong>without</strong>
     * requiring a full-text or semantic match (unlike {@link #findVisibleCandidates}, which gates on
     * {@code search_vector @@ plainto_tsquery}). This powers the catalog MCP resource: the full set
     * the caller may see, as compact lines, so skill selection can be resident in the agent's context.
     *
     * <p>The visibility disjunction and the {@code lifecycle_status <> 'Blocked'}, archived-exclusion,
     * and {@code current_version_id IS NOT NULL} predicates are copied <strong>verbatim</strong> from
     * {@link #findVisibleCandidates} so a catalog can never surface a skill the resolver would hide
     * (R1.2). An integration test guards this ACL parity so the copies cannot drift (R1.5).</p>
     *
     * <p>The only additions are the optional {@code approvedOnly} lifecycle narrowing (R6) and a
     * deterministic {@code name ASC, id} ordering (R1.4) so the resident catalog is byte-stable for a
     * given org/caller/skill-set. The projection is a slim catalog line (R1.3). Callers typically pass
     * {@code limit = maxEntries + 1} to detect truncation (R5.2).</p>
     */
    @Query(value = """
        SELECT s.id AS skillId,
               s.current_version_id AS skillVersionId,
               s.slug AS slug,
               s.name AS name,
               s.description AS description,
               s.lifecycle_status AS lifecycleStatus,
               s.risk_level AS riskLevel,
               s.visibility AS visibility
        FROM skills s
        WHERE s.organization_id = :orgId
          AND (
            s.visibility = 'organization'
            OR (s.visibility = 'team' AND s.owner_team_id IN (
                SELECT tm.team_id FROM team_members tm WHERE tm.user_id = :userId))
            OR (s.visibility = 'private' AND s.created_by = :userId)
            OR (s.visibility = 'restricted' AND (
                s.created_by = :userId
                OR EXISTS (SELECT 1 FROM skill_access_grants sag
                           WHERE sag.skill_id = s.id AND sag.user_id = :userId)))
            OR :isOrgAdmin = true
          )
          AND s.lifecycle_status <> 'Blocked'
          AND (:includeArchived = true OR s.lifecycle_status <> 'Archived')
          AND s.current_version_id IS NOT NULL
          AND (:approvedOnly = false
               OR s.lifecycle_status IN ('Company_Approved', 'Team_Approved'))
        ORDER BY s.name ASC, s.id
        LIMIT :limit
        """, nativeQuery = true)
    List<CatalogEntryProjection> findVisibleCatalogEntries(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("isOrgAdmin") boolean isOrgAdmin,
            @Param("includeArchived") boolean includeArchived,
            @Param("approvedOnly") boolean approvedOnly,
            @Param("limit") int limit
    );
}
