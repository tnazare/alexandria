package com.alexandria.skill;

import com.alexandria.skill.entity.SkillEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     * Paginated skill list with visibility enforcement.
     * Uses a native query with visibility predicates so unauthorized skills are never loaded.
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
          AND (:status IS NULL OR s.lifecycle_status = :status)
          AND (:teamId IS NULL OR s.owner_team_id = :teamId)
          AND (:searchQuery IS NULL OR s.search_vector @@ plainto_tsquery('english', :searchQuery))
        ORDER BY s.updated_at DESC
        """, nativeQuery = true)
    Page<SkillEntity> findVisibleSkills(
        @Param("orgId") UUID orgId,
        @Param("userId") UUID userId,
        @Param("isOrgAdmin") boolean isOrgAdmin,
        @Param("status") String status,
        @Param("teamId") UUID teamId,
        @Param("searchQuery") String searchQuery,
        Pageable pageable
    );
}
