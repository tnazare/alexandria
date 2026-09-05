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
import com.alexandria.agent.render.FetchContext;
import com.alexandria.agent.render.FetchResult;
import com.alexandria.agent.render.SkillFetchRendererRegistry;
import com.alexandria.audit.AuditService;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ResolverPolicy;
import com.alexandria.config.ResolverProperties;
import com.alexandria.skill.CandidateProjection;
import com.alexandria.skill.SkillResolverRepository;
import com.alexandria.skill.SkillService;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.skill.variant.SkillVariantService;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.TeamRepository;
import com.alexandria.usage.UsageEventService;
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
    private final SkillService skillService;
    private final SkillVariantService variantService;
    private final SkillFetchRendererRegistry rendererRegistry;
    private final UsageEventService usageEventService;
    private final AuditService auditService;
    private final ResolverPolicy resolverPolicy;

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
            // Resolve the reference mode to a concrete version. Under a floating mode with no
            // eligible version, exclude the candidate rather than surface an unapproved version.
            ResolvedRef resolved = resolveVersion(c, referenceMode);
            if (resolved == null) {
                continue;
            }
            results.add(toResult(c, request.context(), resolved, identity.organizationId()));
        }

        Capabilities capabilities = new Capabilities(true, true, false, false);
        return new ResolveResponse(UUID.randomUUID().toString(), SCHEMA_VERSION, capabilities, results);
    }

    @Override
    public FetchResult fetch(UUID skillVersionId, String tool, String format, String source,
                             AlexandriaUserDetails identity) {
        // 1. Load the version org-scoped. Missing/other-org -> 404 (anti-enumeration).
        SkillVersionEntity version = resolverRepository
                .loadVersionForFetch(skillVersionId, identity.organizationId())
                .orElseThrow(() -> new com.alexandria.skill.SkillNotFoundException("Skill version not found"));

        // 2. Load the parent skill with per-user visibility enforcement.
        //    getById throws SkillNotFoundException (404) when hidden — the anti-enumeration path.
        SkillEntity skill = skillService.getById(version.getSkillId(), identity);

        // 3. Blocked (but visible) -> 403 for the agent surface (distinct from human export's 409).
        if ("Blocked".equals(skill.getLifecycleStatus())) {
            auditService.record(identity.organizationId(), identity.actorUserId(),
                    "skill.resolver_fetch_denied", "skill", skill.getId(),
                    Map.of("skillVersionId", skillVersionId.toString(), "reason", "Blocked"));
            throw new ResolverBlockedException("Skill is blocked and cannot be fetched");
        }

        // 4. Resolve instructions: variant for the tool, else canonical fallback.
        boolean canonicalFallback = true;
        String selectedTool = "generic";
        String instructions = version.getCanonicalInstructions();
        if (tool != null && !tool.isBlank()) {
            String variantInstructions = variantService.resolve(version.getId(), tool);
            if (variantInstructions != null) {
                instructions = variantInstructions;
                selectedTool = tool;
                canonicalFallback = false;
            }
        }

        // 5. Build governance + placeholder resolution (Task 3 wires risk-aware ResolverPolicy).
        boolean deprecated = "Deprecated".equals(skill.getLifecycleStatus());
        Governance governance = new Governance(
                skill.getLifecycleStatus(), skill.getRiskLevel(), skill.getVisibility(),
                ownerTeamName(skill.getOwnerTeamId(), identity.organizationId()),
                deprecated, deprecated ? skill.getDeprecationReason() : null);
        // Risk-aware resolution: an explicit fetch of a specific version is inherently pinned;
        // the policy may still force revalidation / block commit for high/restricted risk.
        Resolution resolution = resolverPolicy.resolve(
                identity.organizationId(), skill.getRiskLevel(), "pinned",
                String.valueOf(version.getVersionNumber()), revalidateHint(version.getId()));
        SkillRef ref = new SkillRef(skill.getId(), version.getId(), properties.instanceUrl());

        FetchContext ctx = new FetchContext(
                ref, skill.getName(), skill.getSlug(), skill.getDescription(),
                version.getVersionNumber(), governance, resolution,
                version.getWhenToUse(), version.getCanonicalInstructions(),
                version.getApplicability(), version.getLimitations(), version.getExamples(),
                selectedTool, instructions, canonicalFallback, version.getSupportedTools());

        FetchResult result = rendererRegistry.rendererFor(format).render(ctx);

        // 6. Record a view usage event on success (fire-and-forget). Actor is null for service
        //    tokens, whose id references no users row.
        usageEventService.record(identity.organizationId(), identity.actorUserId(),
                skill.getId(), version.getId(), "view", source, null);

        return result;
    }

    private ResolveResult toResult(CandidateProjection c, ResolveContext context,
                                   ResolvedRef resolved, UUID orgId) {
        boolean deprecated = "Deprecated".equals(c.getLifecycleStatus());
        Governance governance = new Governance(
                c.getLifecycleStatus(),
                c.getRiskLevel(),
                c.getVisibility(),
                ownerTeamName(c.getOwnerTeamId(), orgId),
                deprecated,
                deprecated ? c.getDeprecationReason() : null);

        // Risk-aware resolution via policy (Requirement 7): forces pinned+revalidate or block-commit
        // for high/restricted risk, else honors the resolved reference mode.
        Resolution resolution = resolverPolicy.resolve(
                orgId, c.getRiskLevel(), resolved.mode(), resolved.versionNumber(),
                revalidateHint(resolved.versionId()));

        Delivery delivery = new Delivery(
                fetchHref(resolved.versionId()),
                List.of("agent_skill", "alexandria_native"));

        SkillRef ref = new SkillRef(c.getSkillId(), resolved.versionId(), properties.instanceUrl());

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

    /** Resolved version identity + effective reference mode for a candidate. */
    private record ResolvedRef(UUID versionId, String versionNumber, String mode) {
    }

    /**
     * Resolves a candidate's reference mode to a concrete version. For pinned, uses the candidate's
     * current version. For floating modes, resolves the latest approved / latest published version;
     * returns null when no eligible version exists (Requirement 5.6), so the caller excludes it.
     */
    private ResolvedRef resolveVersion(CandidateProjection c, String mode) {
        switch (mode) {
            case "floating_latest_approved" -> {
                Optional<UUID> v = resolverRepository.findLatestApprovedVersionId(c.getSkillId());
                return v.map(id -> new ResolvedRef(id, null, mode)).orElse(null);
            }
            case "floating_latest_published" -> {
                Optional<UUID> v = resolverRepository.findLatestPublishedVersionId(c.getSkillId());
                return v.map(id -> new ResolvedRef(id, null, mode)).orElse(null);
            }
            default -> {
                // pinned: the candidate's current published version.
                return new ResolvedRef(c.getSkillVersionId(), null, "pinned");
            }
        }
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
