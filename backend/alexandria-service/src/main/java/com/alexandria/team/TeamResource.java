package com.alexandria.team;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.common.dto.PageResponse;
import com.alexandria.team.dto.AddMemberRequest;
import com.alexandria.team.dto.CreateTeamRequest;
import com.alexandria.team.dto.TeamDetailResponse;
import com.alexandria.team.dto.TeamMemberResponse;
import com.alexandria.team.dto.TeamResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for team management endpoints.
 */
@RestController
@RequiredArgsConstructor
public class TeamResource implements TeamApi {

    private final TeamService teamService;

    @Override
    @PreAuthorize("hasAnyRole('Org_Admin', 'Catalog_Admin')")
    public ResponseEntity<TeamResponse> createTeam(CreateTeamRequest request) {
        AlexandriaUserDetails user = getAuthenticatedUser();
        TeamResponse team = teamService.createTeam(user.organizationId(), user.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(team);
    }

    @Override
    public ResponseEntity<PageResponse<TeamResponse>> listTeams(int page, int size) {
        AlexandriaUserDetails user = getAuthenticatedUser();
        PageResponse<TeamResponse> teams = teamService.listTeams(user.organizationId(), PageRequest.of(page, size));
        return ResponseEntity.ok(teams);
    }

    @Override
    @PreAuthorize("hasRole('Org_Admin')")
    public ResponseEntity<TeamDetailResponse> getTeamDetail(UUID teamId) {
        AlexandriaUserDetails user = getAuthenticatedUser();
        TeamDetailResponse detail = teamService.getTeamDetail(user.organizationId(), teamId);
        return ResponseEntity.ok(detail);
    }

    @Override
    @PreAuthorize("@teamSecurity.isOwnerOrAdmin(#teamId, authentication)")
    public ResponseEntity<TeamMemberResponse> addMember(UUID teamId, AddMemberRequest request) {
        AlexandriaUserDetails user = getAuthenticatedUser();
        TeamMemberResponse member = teamService.addMember(user.organizationId(), teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @Override
    @PreAuthorize("@teamSecurity.isOwnerOrAdmin(#teamId, authentication)")
    public ResponseEntity<Void> removeMember(UUID teamId, UUID userId) {
        AlexandriaUserDetails user = getAuthenticatedUser();
        teamService.removeMember(user.organizationId(), teamId, userId);
        return ResponseEntity.noContent().build();
    }

    private AlexandriaUserDetails getAuthenticatedUser() {
        return org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() instanceof com.alexandria.auth.AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
