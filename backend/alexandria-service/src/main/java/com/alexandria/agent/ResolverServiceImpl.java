package com.alexandria.agent;

import com.alexandria.agent.dto.Capabilities;
import com.alexandria.agent.dto.Delivery;
import com.alexandria.agent.dto.Governance;
import com.alexandria.agent.dto.Resolution;
import com.alexandria.agent.dto.ResolveContext;
import com.alexandria.agent.dto.ResolveFilters;
import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.agent.dto.ResolveResult;
import com.alexandria.agent.dto.SkillRef;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ResolverProperties;
import com.alexandria.skill.CandidateProjection;
import com.alexandria.skill.SkillResolverRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.TeamRepository;
import com.alexandria.user.entity.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core resolve implementation. Mirrors {@code SkillServiceImpl.list}'s ACL shape: derive
 * {@code isOrgAdmin} narrowly (Org_Admin only), enforce visibility at the query layer via
 * {@link SkillResolverRepository#findVisibleCandidates}, and assemble the forward-compatible
 * envelope.
 *
 * <p>The reference-mode {@link Resolution} block is a placeholder in this task (it always reports
 * the requested-or-default mode with permissive commit flags); the risk-aware policy is wired in
 * Task 3. Fetch is added in Task 2.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResolverServiceImpl implements ResolverService {

    private static final String SCHEMA_VERSION = "1.0";

    private final SkillResolverRepository resolverRepository;
    private final TeamRepository teamRepository;
    private final ResolverProperties properties;

    @Override
    public ResolveResponse resolve(ResolveRequest request, AlexandriaUserDetails identity) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("Resolve query must not be blank");
        }

        int limit = clampLimit(request.limit());
        boolean includeArchived = requestsArchived(request.filters());
        // Narrow bypass: only Org_Admin sees beyond visibility rules, matching SkillServiceImpl.list.
        boolean isOrgAdmin = identity.role() == Role.Org_Admin;

        List<CandidateProjection> candidates = resolverRepository.findVisibleCandidates(
                identity.organizationId(), identity.userId(), isOrgAdmin, includeArchived, query, limit);

        String referenceMode = resolveReferenceMode(request.referenceMode());

        List<ResolveResult> results = new ArrayList<>(candidates.size());
        for (CandidateProjection c : candidates) {
            results.add(toResult(c, request.context(), referenceMode, identity.organizationId()));
        }

        Capabilities capabilities = new Capabilities(true, true, false, false);
        return new ResolveResponse(UUID.randomUUID().toString(), SCHEMA_VERSION, capabilities, results);
    }

    private ResolveResult toResult(CandidateProjection c, ResolveContext context,
                                   String referenceMode, UUID orgId) {
        boolean deprecated = "Deprecated".equals(c.getLifecycleStatus());
        Governance governance = new Governance(
                c.getLifecycleStatus(),
                c.getRiskLevel(),
                c.getVisibility(),
                ownerTeamName(c.getOwnerTeamId(), orgId),
                deprecated,
                deprecated ? c.getDeprecationReason() : null);

        // Placeholder resolution — Task 3 replaces this with risk-aware ResolverPolicy output.
        Resolution resolution = new Resolution(
                referenceMode,
                null,
                true,
                false,
                revalidateHint(c.getSkillVersionId()));

        Delivery delivery = new Delivery(
                fetchHref(c.getSkillVersionId()),
                List.of("agent_skill", "alexandria_native"));

        SkillRef ref = new SkillRef(c.getSkillId(), c.getSkillVersionId(), properties.instanceUrl());

        return new ResolveResult(
                "skill",
                ref,
                c.getName(),
                c.getDescription(),
                c.getScore(),
                governance,
                matchReasons(c, context),
                resolution,
                delivery,
                null,
                List.of());
    }

    /**
     * Builds short, human-readable reasons a candidate was returned, from the overlap between the
     * request context and the candidate. Kept deterministic and side-effect free.
     */
    private List<String> matchReasons(CandidateProjection c, ResolveContext context) {
        List<String> reasons = new ArrayList<>();
        reasons.add("Matched full-text search");
        if (isApproved(c.getLifecycleStatus())) {
            reasons.add("Approval state: " + c.getLifecycleStatus());
        }
        if (context != null && context.tool() != null && !context.tool().isBlank()) {
            reasons.add("Requested tool: " + context.tool());
        }
        return reasons;
    }

    private static boolean isApproved(String lifecycleStatus) {
        return "Team_Approved".equals(lifecycleStatus) || "Company_Approved".equals(lifecycleStatus);
    }

    private String ownerTeamName(UUID ownerTeamId, UUID orgId) {
        if (ownerTeamId == null) {
            return null;
        }
        Optional<TeamEntity> team = teamRepository.findByIdAndOrganizationId(ownerTeamId, orgId);
        return team.map(TeamEntity::getName).orElse(null);
    }

    private int clampLimit(Integer requested) {
        int max = properties.maxLimit();
        if (requested == null) {
            return Math.min(properties.defaultLimit(), max);
        }
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, max);
    }

    private String resolveReferenceMode(String requested) {
        if (requested == null || requested.isBlank()) {
            return properties.defaultReferenceMode();
        }
        String normalized = requested.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pinned", "floating_latest_approved", "floating_latest_published" -> normalized;
            default -> properties.defaultReferenceMode();
        };
    }

    private static boolean requestsArchived(ResolveFilters filters) {
        return filters != null
                && filters.lifecycleStates() != null
                && filters.lifecycleStates().stream().anyMatch("Archived"::equalsIgnoreCase);
    }

    private String fetchHref(UUID skillVersionId) {
        return "/api/agent/v1/skills/" + skillVersionId + "/instructions";
    }

    private String revalidateHint(UUID skillVersionId) {
        return "/api/agent/v1/skills/versions/" + skillVersionId + "/governance";
    }
}
