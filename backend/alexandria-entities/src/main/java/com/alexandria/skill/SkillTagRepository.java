package com.alexandria.skill;

import com.alexandria.skill.entity.SkillTagEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for skill tag entities (join table between skills and taxonomy entries).
 */
@Repository
public interface SkillTagRepository extends JpaRepository<SkillTagEntity, UUID> {

    List<SkillTagEntity> findBySkillId(UUID skillId);

    @Modifying
    @Transactional
    void deleteBySkillId(UUID skillId);

    boolean existsByTaxonomyEntryId(UUID taxonomyEntryId);
}
