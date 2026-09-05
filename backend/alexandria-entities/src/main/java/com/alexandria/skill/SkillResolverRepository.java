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
}
