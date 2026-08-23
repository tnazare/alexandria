package com.alexandria.team;

import com.alexandria.audit.AuditService;
import com.alexandria.common.dto.PageResponse;
import com.alexandria.team.dto.AddMemberRequest;
import com.alexandria.team.dto.CreateTeamRequest;
import com.alexandria.team.dto.TeamDetailResponse;
import com.alexandria.team.dto.TeamMemberResponse;
import com.alexandria.team.dto.TeamResponse;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.UserEntity;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of team management operations including slug generation,
 * membership management, and audit event recording.
 */
@Service
@RequiredArgsConstructor
public class TeamServiceImpl implements TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public TeamResponse createTeam(UUID orgId, UUID actorId, CreateTeamRequest request) {
        String slug = generateSlug(request.name());

        if (teamRepository.existsByOrganizationIdAndSlug(orgId, slug)) {
            throw new TeamConflictException("Team name already taken");
        }

        TeamEntity team = new TeamEntity();
        team.setOrganizationId(orgId);
        team.setName(request.name());
        team.setSlug(slug);
        team.setDescription(request.description());
        team.setCreatedBy(actorId);

        team = teamRepository.save(team);

        // Add creator as owner
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(team.getId());
        member.setUserId(actorId);
        member.setTeamRole("owner");
        teamMemberRepository.save(member);

        auditService.record(orgId, actorId, "team.created", "team", team.getId(),
                Map.of("name", request.name(), "slug", slug));

        int memberCount = (int) teamMemberRepository.countByTeamIdAndTeamRole(team.getId(), "owner")
                + (int) teamMemberRepository.countByTeamIdAndTeamRole(team.getId(), "member");

        return toTeamResponse(team, memberCount);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TeamResponse> listTeams(UUID orgId, Pageable pageable) {
        Page<TeamEntity> page = teamRepository.findByOrganizationId(orgId, pageable);

        List<TeamResponse> content = page.getContent().stream()
                .map(team -> {
                    int count = (int) teamMemberRepository.countByTeamIdAndTeamRole(team.getId(), "owner")
                            + (int) teamMemberRepository.countByTeamIdAndTeamRole(team.getId(), "member");
                    return toTeamResponse(team, count);
                })
                .toList();

        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TeamDetailResponse getTeamDetail(UUID orgId, UUID teamId) {
        TeamEntity team = teamRepository.findByIdAndOrganizationId(teamId, orgId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found"));

        List<TeamMemberEntity> members = teamMemberRepository.findByTeamId(teamId);

        List<TeamMemberResponse> memberResponses = members.stream()
                .map(m -> {
                    UserEntity user = userRepository.findByIdAndOrganizationId(m.getUserId(), orgId)
                            .orElse(null);
                    String email = user != null ? user.getEmail() : "";
                    String displayName = user != null ? user.getDisplayName() : "";
                    return new TeamMemberResponse(m.getUserId(), email, displayName, m.getTeamRole(), m.getJoinedAt());
                })
                .toList();

        return new TeamDetailResponse(
                team.getId(),
                team.getName(),
                team.getSlug(),
                team.getDescription(),
                memberResponses,
                team.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public TeamMemberResponse addMember(UUID orgId, UUID teamId, AddMemberRequest request) {
        TeamEntity team = teamRepository.findByIdAndOrganizationId(teamId, orgId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found"));

        UserEntity user = userRepository.findByIdAndOrganizationId(request.userId(), orgId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        // Check if already a member
        if (teamMemberRepository.findByTeamIdAndUserId(teamId, request.userId()).isPresent()) {
            throw new TeamConflictException("User is already a member of this team");
        }

        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(request.userId());
        member.setTeamRole(request.teamRole());
        member = teamMemberRepository.save(member);

        auditService.record(orgId, request.userId(), "team_member.added", "team_member", member.getId(),
                Map.of("teamId", teamId, "userId", request.userId(), "teamRole", request.teamRole()));

        return new TeamMemberResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                member.getTeamRole(),
                member.getJoinedAt()
        );
    }

    @Override
    @Transactional
    public void removeMember(UUID orgId, UUID teamId, UUID userId) {
        TeamEntity team = teamRepository.findByIdAndOrganizationId(teamId, orgId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found"));

        TeamMemberEntity member = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found"));

        // Last-owner protection
        if ("owner".equals(member.getTeamRole())) {
            long ownerCount = teamMemberRepository.countByTeamIdAndTeamRole(teamId, "owner");
            if (ownerCount <= 1) {
                throw new TeamConflictException("Team must have at least one owner");
            }
        }

        teamMemberRepository.deleteByTeamIdAndUserId(teamId, userId);

        auditService.record(orgId, userId, "team_member.removed", "team_member", member.getId(),
                Map.of("teamId", teamId, "userId", userId));
    }

    /**
     * Generates a URL-safe slug from a team name.
     * <ol>
     *   <li>Lowercase the name</li>
     *   <li>Replace spaces and underscores with hyphens</li>
     *   <li>Strip characters not in [a-z0-9-]</li>
     *   <li>Collapse consecutive hyphens into one</li>
     *   <li>Trim leading/trailing hyphens</li>
     * </ol>
     */
    static String generateSlug(String name) {
        String slug = name.toLowerCase();
        slug = slug.replace(' ', '-').replace('_', '-');
        slug = slug.replaceAll("[^a-z0-9\\-]", "");
        slug = slug.replaceAll("-{2,}", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug;
    }

    private TeamResponse toTeamResponse(TeamEntity team, int memberCount) {
        return new TeamResponse(
                team.getId(),
                team.getName(),
                team.getSlug(),
                team.getDescription(),
                memberCount,
                team.getCreatedAt()
        );
    }
}
