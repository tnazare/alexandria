package com.alexandria.team;

import com.alexandria.common.dto.PageResponse;
import com.alexandria.team.dto.AddMemberRequest;
import com.alexandria.team.dto.CreateTeamRequest;
import com.alexandria.team.dto.TeamDetailResponse;
import com.alexandria.team.dto.TeamMemberResponse;
import com.alexandria.team.dto.TeamResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * REST API contract for team management.
 */
@RequestMapping("/api/teams")
public interface TeamApi {

    @PostMapping
    ResponseEntity<TeamResponse> createTeam(@Valid @RequestBody CreateTeamRequest request);

    @GetMapping
    ResponseEntity<PageResponse<TeamResponse>> listTeams(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    );

    @GetMapping("/{teamId}")
    ResponseEntity<TeamDetailResponse> getTeamDetail(@PathVariable UUID teamId);

    @PostMapping("/{teamId}/members")
    ResponseEntity<TeamMemberResponse> addMember(
            @PathVariable UUID teamId,
            @Valid @RequestBody AddMemberRequest request
    );

    @DeleteMapping("/{teamId}/members/{userId}")
    ResponseEntity<Void> removeMember(@PathVariable UUID teamId, @PathVariable UUID userId);
}
