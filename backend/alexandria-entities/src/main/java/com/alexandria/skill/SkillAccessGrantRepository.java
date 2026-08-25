package com.alexandria.skill;

import com.alexandria.skill.entity.SkillAccessGrantEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for skill access grant entities (restricted-visibility access control).
 */
@Repository
public interface SkillAccessGrantRepository extends JpaRepository<SkillAccessGrantEntity, UUID> {

    boolean existsBySkillIdAndUserId(UUID skillId, UUID userId);

    @Modifying
    @Transactional
    void deleteBySkillId(UUID skillId);
}
