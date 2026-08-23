package com.alexandria.team;

import com.alexandria.team.entity.TeamMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for team member entities.
 */
@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, UUID> {

    List<TeamMemberEntity> findByTeamId(UUID teamId);

    Optional<TeamMemberEntity> findByTeamIdAndUserId(UUID teamId, UUID userId);

    long countByTeamIdAndTeamRole(UUID teamId, String teamRole);

    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);
}
