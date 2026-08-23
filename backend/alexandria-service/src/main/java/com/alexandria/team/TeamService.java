package com.alexandria.team;

import com.alexandria.common.dto.PageResponse;
import com.alexandria.team.dto.AddMemberRequest;
import com.alexandria.team.dto.CreateTeamRequest;
import com.alexandria.team.dto.TeamDetailResponse;
import com.alexandria.team.dto.TeamMemberResponse;
import com.alexandria.team.dto.TeamResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service for team lifecycle and membership management.
 */
public interface TeamService {

    TeamResponse createTeam(UUID orgId, UUID actorId, CreateTeamRequest request);

    PageResponse<TeamResponse> listTeams(UUID orgId, Pageable pageable);

    TeamDetailResponse getTeamDetail(UUID orgId, UUID teamId);

    TeamMemberResponse addMember(UUID orgId, UUID teamId, AddMemberRequest request);

    void removeMember(UUID orgId, UUID teamId, UUID userId);
}
