package com.alexandria.skill;

import com.alexandria.skill.entity.SkillVersionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for skill version entities.
 */
@Repository
public interface SkillVersionRepository extends JpaRepository<SkillVersionEntity, UUID> {

    Page<SkillVersionEntity> findBySkillIdOrderByVersionNumberDesc(UUID skillId, Pageable pageable);

    Optional<SkillVersionEntity> findBySkillIdAndVersionNumber(UUID skillId, int versionNumber);

    int countBySkillId(UUID skillId);
}
