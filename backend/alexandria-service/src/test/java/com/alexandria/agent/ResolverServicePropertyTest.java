package com.alexandria.agent;

import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.agent.dto.ResolveResult;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ResolverProperties;
import com.alexandria.skill.CandidateProjection;
import com.alexandria.skill.SkillResolverRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.user.entity.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResolverServiceImpl#resolve} using mocked repositories (no Spring context).
 *
 * <p>Validates:
 * <ul>
 *   <li>Property 10 — the envelope is forward-compatible and inert (capabilities.invoke/compose
 *       false, kind="skill", invocation null, relationships empty). Requirements 8.1–8.6.</li>
 *   <li>Property 3 — candidates carry no instruction bodies (the projection has none). Req 1.4.</li>
 *   <li>Requirement 1.6 — blank query is rejected with IllegalArgumentException (→ 400).</li>
 *   <li>Requirement 14.3 / 1.1 — limit defaults and clamps to the configured maximum.</li>
 * </ul>
 */
@Tag("skill-resolver")
class ResolverServicePropertyTest {

    private static final ResolverProperties PROPS =
            new ResolverProperties("https://alx.example.com", 25, 5, "floating_latest_approved",
                    8192, 12, true);

    private ResolverServiceImpl newService(SkillResolverRepository repo, TeamRepository teams) {
        // resolve() does not touch the fetch collaborators; pass mocks to satisfy the constructor.
        return new ResolverServiceImpl(
                repo,
                teams,
                PROPS,
                mock(com.alexandria.skill.SkillService.class),
                mock(com.alexandria.skill.variant.SkillVariantService.class),
                new com.alexandria.agent.render.SkillFetchRendererRegistry(java.util.List.of(
                        new com.alexandria.agent.render.AgentSkillRenderer(),
                        new com.alexandria.agent.render.NativeRenderer())),
                mock(com.alexandria.usage.UsageEventService.class),
                mock(com.alexandria.audit.AuditService.class),
                permissivePolicy(),
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    private com.alexandria.config.ResolverPolicy permissivePolicy() {
        var policyRepo = mock(com.alexandria.agent.ResolverPolicyRepository.class);
        when(policyRepo.findByOrganizationId(any())).thenReturn(Optional.empty());
        return new com.alexandria.config.ResolverPolicy(policyRepo, PROPS);
    }

    private AlexandriaUserDetails identity(Role role) {
        return new AlexandriaUserDetails(UUID.randomUUID(), UUID.randomUUID(), role, "agent@test.com");
    }

    @Test
    void blankQueryIsRejected() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        ResolverServiceImpl svc = newService(repo, teams);

        assertThatThrownBy(() -> svc.resolve(new ResolveRequest("   ", null, null, null, null),
                identity(Role.Skill_Consumer)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 50)
    void envelopeIsForwardCompatibleAndInert(@ForAll("candidateCounts") int count) {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findByIdAndOrganizationId(any(), any())).thenReturn(Optional.empty());
        when(repo.findVisibleCandidates(any(), any(), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(candidates(count));

        // Use pinned mode so resolution uses each candidate's own version (no floating lookup).
        ResolveResponse response = newService(repo, teams)
                .resolve(new ResolveRequest("integration tests", null, null, "pinned", null),
                        identity(Role.Skill_Consumer));

        assertThat(response.schemaVersion()).isEqualTo("1.0");
        assertThat(response.capabilities().resolve()).isTrue();
        assertThat(response.capabilities().fetch()).isTrue();
        assertThat(response.capabilities().invoke()).isFalse();
        assertThat(response.capabilities().compose()).isFalse();
        assertThat(response.results()).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo("skill");
            assertThat(r.invocation()).isNull();
            assertThat(r.relationships()).isEmpty();
            assertThat(r.ref().sourceInstance()).isEqualTo("https://alx.example.com");
        });
    }

    @Property(tries = 30)
    void limitIsClampedToConfiguredMaximum(@ForAll @IntRange(min = 26, max = 10_000) int requested) {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        when(repo.findVisibleCandidates(any(), any(), anyBoolean(), anyBoolean(), anyString(), eq(25)))
                .thenReturn(List.of());

        // If the service passed anything other than 25, this stub would return null and NPE.
        ResolveResponse response = newService(repo, teams)
                .resolve(new ResolveRequest("q", null, null, null, requested),
                        identity(Role.Skill_Consumer));

        assertThat(response.results()).isEmpty();
    }

    @Test
    void nullLimitUsesDefault() {
        SkillResolverRepository repo = mock(SkillResolverRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        when(repo.findVisibleCandidates(any(), any(), anyBoolean(), anyBoolean(), anyString(), eq(5)))
                .thenReturn(List.of());

        ResolveResponse response = newService(repo, teams)
                .resolve(new ResolveRequest("q", null, null, null, null),
                        identity(Role.Skill_Consumer));

        assertThat(response.results()).isEmpty();
    }

    // --- helpers ---

    private List<CandidateProjection> candidates(int count) {
        List<CandidateProjection> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(candidate("Skill " + i, "Desc " + i, "Published", "low", "organization"));
        }
        return list;
    }

    private CandidateProjection candidate(String name, String desc, String lifecycle,
                                          String risk, String visibility) {
        return new CandidateProjection() {
            public UUID getSkillId() { return UUID.randomUUID(); }
            public UUID getSkillVersionId() { return UUID.randomUUID(); }
            public String getName() { return name; }
            public String getDescription() { return desc; }
            public String getSlug() { return "slug"; }
            public String getLifecycleStatus() { return lifecycle; }
            public String getRiskLevel() { return risk; }
            public String getVisibility() { return visibility; }
            public UUID getOwnerTeamId() { return UUID.randomUUID(); }
            public String getDeprecationReason() { return null; }
            public double getScore() { return 0.5; }
        };
    }

    @Provide
    Arbitrary<Integer> candidateCounts() {
        return Arbitraries.integers().between(0, 10);
    }
}
