package com.alexandria.skill;

import com.alexandria.skill.entity.SkillEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for skill entities. Includes visibility-enforced native query for listing.
 */
@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, UUID> {

    Optional<SkillEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<SkillEntity> findByOrganizationIdAndSlug(UUID organizationId, String slug);

    boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);

    /**
     * Paginated skill list with visibility enforcement, full-text search, and faceted filters.
     *
     * <p>Results are ordered by {@code ts_rank} descending when a {@code searchQuery} is
     * present (relevance-first), falling back to {@code updated_at DESC} as a tiebreaker
     * or as the sole ordering when browsing without a query. The {@code CASE WHEN} expression
     * uses {@code 0.0} (not {@code NULL}) so PostgreSQL can infer a concrete {@code float4}
     * type for the expression.</p>
     *
     * <p>The explicit {@code countQuery} runs a lean {@code COUNT(*)} with the same predicates,
     * avoiding a full projection during page-count computation.</p>
     */
    @Query(value = """
        SELECT s.* FROM skills s
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
          AND (:includeArchived = true OR :status = 'Archived' OR s.lifecycle_status <> 'Archived')
          AND (:status IS NULL OR s.lifecycle_status = :status)
          AND (:visibility IS NULL OR s.visibility = :visibility)
          AND (:riskLevel IS NULL OR s.risk_level = :riskLevel)
          AND (:teamId IS NULL OR s.owner_team_id = :teamId)
          AND (:taskType IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id
                  AND te.tag_type = 'task_type'
                  AND LOWER(te.value) = LOWER(:taskType)
                  AND te.active = true))
          AND (:language IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id
                  AND te.tag_type = 'language'
                  AND LOWER(te.value) = LOWER(:language)
                  AND te.active = true))
          AND (:framework IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id
                  AND te.tag_type = 'framework'
                  AND LOWER(te.value) = LOWER(:framework)
                  AND te.active = true))
          AND (:tool IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id
                  AND te.tag_type = 'tool'
                  AND LOWER(te.value) = LOWER(:tool)
                  AND te.active = true))
          AND (:searchQuery IS NULL OR s.search_vector @@ plainto_tsquery('english', :searchQuery))
        ORDER BY
          CASE WHEN :searchQuery IS NULL THEN 0.0
               ELSE ts_rank(s.search_vector, plainto_tsquery('english', :searchQuery), 1)
          END DESC,
          s.updated_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM skills s
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
          AND (:includeArchived = true OR :status = 'Archived' OR s.lifecycle_status <> 'Archived')
          AND (:status IS NULL OR s.lifecycle_status = :status)
          AND (:visibility IS NULL OR s.visibility = :visibility)
          AND (:riskLevel IS NULL OR s.risk_level = :riskLevel)
          AND (:teamId IS NULL OR s.owner_team_id = :teamId)
          AND (:taskType IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id AND te.tag_type = 'task_type'
                  AND LOWER(te.value) = LOWER(:taskType) AND te.active = true))
          AND (:language IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id AND te.tag_type = 'language'
                  AND LOWER(te.value) = LOWER(:language) AND te.active = true))
          AND (:framework IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id AND te.tag_type = 'framework'
                  AND LOWER(te.value) = LOWER(:framework) AND te.active = true))
          AND (:tool IS NULL OR EXISTS (
                SELECT 1 FROM skill_tags st
                JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
                WHERE st.skill_id = s.id AND te.tag_type = 'tool'
                  AND LOWER(te.value) = LOWER(:tool) AND te.active = true))
          AND (:searchQuery IS NULL OR s.search_vector @@ plainto_tsquery('english', :searchQuery))
        """,
        nativeQuery = true)
    Page<SkillEntity> findVisibleSkills(
        @Param("orgId") UUID orgId,
        @Param("userId") UUID userId,
        @Param("isOrgAdmin") boolean isOrgAdmin,
        @Param("status") String status,
        @Param("visibility") String visibility,
        @Param("riskLevel") String riskLevel,
        @Param("teamId") UUID teamId,
        @Param("taskType") String taskType,
        @Param("language") String language,
        @Param("framework") String framework,
        @Param("tool") String tool,
        @Param("searchQuery") String searchQuery,
        @Param("includeArchived") boolean includeArchived,
        Pageable pageable
    );

    /**
     * Rebuilds the full-text search vector for a single skill to include all four weights:
     * name (A), description (B), tag values (C), and canonical instructions (D).
     *
     * <p>Must always be called <em>after</em> the skill row has been persisted (name and
     * description are read directly from the database row). Must NOT be called from within
     * {@code handleTagUpdate} — call it once at the {@code create} or {@code update} boundary
     * after all changes are applied, to avoid a redundant double-rebuild.</p>
     *
     * @param skillId      the skill to rebuild
     * @param tagValues    space-joined active tag values, or empty string if none
     * @param instructions canonical instructions of the current version, or empty string if none
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE skills
        SET search_vector =
            setweight(to_tsvector('english', COALESCE(name, '')), 'A') ||
            setweight(to_tsvector('english', COALESCE(description, '')), 'B') ||
            setweight(to_tsvector('english', COALESCE(:tagValues, '')), 'C') ||
            setweight(to_tsvector('english', COALESCE(:instructions, '')), 'D')
        WHERE id = :skillId
        """, nativeQuery = true)
    void rebuildSearchVector(
        @Param("skillId") UUID skillId,
        @Param("tagValues") String tagValues,
        @Param("instructions") String instructions
    );
}
