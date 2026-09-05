package com.alexandria.config;

import com.alexandria.agent.ResolverPolicyRepository;
import com.alexandria.agent.dto.Resolution;
import com.alexandria.agent.entity.ResolverPolicyEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ResolverPolicy} risk-aware resolution (skill-resolver Property 9, Req 7.2–7.4).
 *
 * <p>Matrix over {low, medium, high, restricted, null} risk × {permissive, pin_and_revalidate,
 * block_commit, unconfigured} policy, asserting the effective mode and commit/revalidation flags.</p>
 */
@Tag("skill-resolver")
class ResolverPolicyPropertyTest {

    private static final ResolverProperties PROPS =
            new ResolverProperties("https://alx.example.com", 25, 5, "floating_latest_approved",
                    8192, 12, true);

    private ResolverPolicy withConfiguredPolicy(String high, String restricted) {
        ResolverPolicyRepository repo = mock(ResolverPolicyRepository.class);
        ResolverPolicyEntity entity = new ResolverPolicyEntity();
        entity.setHighRiskPolicy(high);
        entity.setRestrictedRiskPolicy(restricted);
        entity.setDefaultReferenceMode("floating_latest_approved");
        when(repo.findByOrganizationId(any())).thenReturn(Optional.of(entity));
        return new ResolverPolicy(repo, PROPS);
    }

    private ResolverPolicy withNoPolicy() {
        ResolverPolicyRepository repo = mock(ResolverPolicyRepository.class);
        when(repo.findByOrganizationId(any())).thenReturn(Optional.empty());
        return new ResolverPolicy(repo, PROPS);
    }

    @Test
    void unconfiguredDefaultsPermissiveForLowAndMedium() {
        ResolverPolicy policy = withNoPolicy();

        Resolution low = policy.resolve(UUID.randomUUID(), "low", "floating_latest_approved", "3", "hint");
        Resolution medium = policy.resolve(UUID.randomUUID(), "medium", "floating_latest_published", "3", "hint");

        assertThat(low.mode()).isEqualTo("floating_latest_approved");
        assertThat(low.commitAllowed()).isTrue();
        assertThat(low.requiresRevalidation()).isFalse();
        assertThat(medium.mode()).isEqualTo("floating_latest_published");
        assertThat(medium.commitAllowed()).isTrue();
    }

    @Test
    void unconfiguredDefaultsPinAndRevalidateForHighAndRestricted() {
        ResolverPolicy policy = withNoPolicy();

        Resolution high = policy.resolve(UUID.randomUUID(), "high", "floating_latest_approved", "3", "hint");
        Resolution restricted = policy.resolve(UUID.randomUUID(), "restricted", "floating_latest_approved", "3", "hint");

        assertThat(high.mode()).isEqualTo("pinned");
        assertThat(high.requiresRevalidation()).isTrue();
        assertThat(restricted.mode()).isEqualTo("pinned");
        assertThat(restricted.requiresRevalidation()).isTrue();
    }

    @Test
    void blockCommitPolicySetsCommitNotAllowed() {
        ResolverPolicy policy = withConfiguredPolicy("block_commit", "block_commit");

        Resolution high = policy.resolve(UUID.randomUUID(), "high", "floating_latest_approved", "3", "hint");

        assertThat(high.commitAllowed()).isFalse();
        // block_commit does not force pinned; it honors the requested mode.
        assertThat(high.mode()).isEqualTo("floating_latest_approved");
    }

    @Test
    void permissiveConfiguredHonorsRequestedModeForHighRisk() {
        ResolverPolicy policy = withConfiguredPolicy("permissive", "permissive");

        Resolution high = policy.resolve(UUID.randomUUID(), "high", "floating_latest_published", "3", "hint");

        assertThat(high.mode()).isEqualTo("floating_latest_published");
        assertThat(high.commitAllowed()).isTrue();
        assertThat(high.requiresRevalidation()).isFalse();
    }

    @Property(tries = 50)
    void lowAndMediumAlwaysHonorRequestedModeUnderDefault(
            @ForAll("lowMediumRisk") String risk,
            @ForAll("referenceModes") String requestedMode) {
        ResolverPolicy policy = withNoPolicy();

        Resolution resolution = policy.resolve(UUID.randomUUID(), risk, requestedMode, "3", "hint");

        assertThat(resolution.mode()).isEqualTo(requestedMode);
        assertThat(resolution.commitAllowed()).isTrue();
        assertThat(resolution.requiresRevalidation()).isFalse();
    }

    @Provide
    Arbitrary<String> lowMediumRisk() {
        return Arbitraries.of("low", "medium");
    }

    @Provide
    Arbitrary<String> referenceModes() {
        return Arbitraries.of("pinned", "floating_latest_approved", "floating_latest_published");
    }
}
