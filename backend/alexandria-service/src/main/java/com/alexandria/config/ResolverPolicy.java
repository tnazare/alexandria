package com.alexandria.config;

import com.alexandria.agent.ResolverPolicyRepository;
import com.alexandria.agent.dto.Resolution;
import com.alexandria.agent.entity.ResolverPolicyEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes the effective {@link Resolution} for a resolved/fetched skill from the caller's requested
 * reference mode, the skill's risk level, and the organization's resolver policy (Requirement 7).
 *
 * <p>Policy per risk level: {@code permissive} honors the requested mode; {@code pin_and_revalidate}
 * forces {@code pinned} + {@code requiresRevalidation=true}; {@code block_commit} sets
 * {@code commitAllowed=false}. When no policy row exists for the org, the code default applies —
 * permissive for low/medium, pin-and-revalidate for high/restricted (Requirement 7.4).</p>
 */
@Component
@RequiredArgsConstructor
public class ResolverPolicy {

    private static final String PERMISSIVE = "permissive";
    private static final String PIN_AND_REVALIDATE = "pin_and_revalidate";
    private static final String BLOCK_COMMIT = "block_commit";

    private final ResolverPolicyRepository policyRepository;
    private final ResolverProperties properties;

    /**
     * Builds the effective resolution for a skill.
     *
     * @param orgId            the caller's organization
     * @param riskLevel        the skill's risk level (may be null → treated as low/medium tier)
     * @param requestedMode    the caller's requested reference mode (already normalized/defaulted)
     * @param resolvedVersion  the concrete resolved version number as a string (may be null)
     * @param revalidateHint   stable hint for a future revalidation endpoint
     */
    public Resolution resolve(UUID orgId, String riskLevel, String requestedMode,
                              String resolvedVersion, String revalidateHint) {
        String effectivePolicy = policyForRisk(orgId, riskLevel);

        String mode = requestedMode;
        boolean commitAllowed = true;
        boolean requiresRevalidation = false;

        switch (effectivePolicy) {
            case PIN_AND_REVALIDATE -> {
                mode = "pinned";
                requiresRevalidation = true;
            }
            case BLOCK_COMMIT -> commitAllowed = false;
            default -> { /* permissive: honor requested mode as-is */ }
        }

        return new Resolution(mode, resolvedVersion, commitAllowed, requiresRevalidation, revalidateHint);
    }

    /**
     * Returns the policy string ({@code permissive}/{@code pin_and_revalidate}/{@code block_commit})
     * that applies to a given risk level for an organization, falling back to code defaults.
     */
    public String policyForRisk(UUID orgId, String riskLevel) {
        String risk = riskLevel == null ? "" : riskLevel.toLowerCase(Locale.ROOT);
        Optional<ResolverPolicyEntity> policy = policyRepository.findByOrganizationId(orgId);

        return switch (risk) {
            case "high" -> policy.map(ResolverPolicyEntity::getHighRiskPolicy).orElse(PIN_AND_REVALIDATE);
            case "restricted" -> policy.map(ResolverPolicyEntity::getRestrictedRiskPolicy)
                    .orElse(PIN_AND_REVALIDATE);
            // low/medium/unset: permissive by default (Requirement 7.4).
            default -> PERMISSIVE;
        };
    }
}
