package com.alexandria.skill;

import com.alexandria.skill.entity.SkillVariantEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for skill variant entities.
 */
@Repository
public interface SkillVariantRepository extends JpaRepository<SkillVariantEntity, UUID> {

    List<SkillVariantEntity> findBySkillVersionId(UUID skillVersionId);

    Optional<SkillVariantEntity> findBySkillVersionIdAndTool(UUID skillVersionId, String tool);

    boolean existsBySkillVersionIdAndTool(UUID skillVersionId, String tool);

    @Modifying
    @Transactional
    void deleteBySkillVersionId(UUID skillVersionId);
}
