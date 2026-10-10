package com.alexandria.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.CatalogProperties;
import com.alexandria.skill.CatalogEntryProjection;
import com.alexandria.skill.SkillResolverRepository;
import com.alexandria.user.entity.Role;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CatalogService} with a stubbed repository: truncation math, limit clamping,
 * org-admin bypass, and scope/archived application are exercised without a database.
 *
 * <p>Validates: skill-catalog R2.2, R2.3, R2.4, R5.1, R5.2, R6.1, R6.3</p>
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private SkillResolverRepository resolverRepository;
    @Mock
    private com.alexandria.skill.SkillRepository skillRepository;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private CatalogService serviceWith(CatalogProperties props) {
        return new CatalogService(resolverRepository, skillRepository, props);
    }

    private AlexandriaUserDetails identity(Role role) {
        return new AlexandriaUserDetails(userId, orgId, role, "u@test.com");
    }

    private static CatalogEntryProjection entry(String name) {
        // Stable ids so an entry's versionId is consistent across repeated accessor calls.
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        return new CatalogEntryProjection() {
            public UUID getSkillId() { return skillId; }
            public UUID getSkillVersionId() { return versionId; }
            public String getSlug() { return name.toLowerCase().replace(' ', '-'); }
            public String getName() { return name; }
            public String getDescription() { return "desc of " + name; }
            public String getLifecycleStatus() { return "Published"; }
            public String getRiskLevel() { return "low"; }
            public String getVisibility() { return "organization"; }
        };
    }

    private static List<CatalogEntryProjection> entries(int n) {
        return IntStream.range(0, n).mapToObj(i -> entry("skill-" + i)).map(e -> e).toList();
    }

    private static CatalogProperties props(boolean enabled, int maxEntries, String scope,
                                           boolean includeArchivedDefault) {
        return new CatalogProperties(enabled, maxEntries, false, scope, includeArchivedDefault);
    }

    @Test
    void returnsAllEntriesUntruncatedWhenUnderCap() {
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), eq(11))).thenReturn(entries(5));
        CatalogService service = serviceWith(props(true, 10, "all", false));

        CatalogResult result = service.catalog(identity(Role.Skill_Consumer), null, null);

        assertThat(result.truncated()).isFalse();
        assertThat(result.totalReturned()).isEqualTo(5);
        assertThat(result.entries()).hasSize(5);
    }

    @Test
    void truncatesWhenVisibleSetExceedsMaxEntries() {
        // maxEntries=10 → service queries 11; repository returns 11 → truncated, trimmed to 10 (R5.2).
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), eq(11))).thenReturn(entries(11));
        CatalogService service = serviceWith(props(true, 10, "all", false));

        CatalogResult result = service.catalog(identity(Role.Skill_Consumer), null, null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.totalReturned()).isEqualTo(10);
        assertThat(result.entries()).hasSize(10);
    }

    @Test
    void callerLimitIsClampedToMaxEntries() {
        // Caller asks for 1000 but maxEntries=10 → effective limit 10, query 11.
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), eq(11))).thenReturn(entries(4));
        CatalogService service = serviceWith(props(true, 10, "all", false));

        CatalogResult result = service.catalog(identity(Role.Skill_Consumer), null, 1000);

        assertThat(result.entries()).hasSize(4);
    }

    @Test
    void orgAdminTriggersBypassFlag() {
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), eq(true), anyBoolean(),
                anyBoolean(), eq(501))).thenReturn(List.of());
        CatalogService service = serviceWith(props(true, 500, "all", false));

        service.catalog(identity(Role.Org_Admin), null, null);

        verify(resolverRepository).findVisibleCatalogEntries(eq(orgId), eq(userId), eq(true),
                anyBoolean(), anyBoolean(), eq(501));
    }

    @Test
    void nonAdminDoesNotBypass() {
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), eq(false), anyBoolean(),
                anyBoolean(), eq(501))).thenReturn(List.of());
        CatalogService service = serviceWith(props(true, 500, "all", false));

        service.catalog(identity(Role.Skill_Consumer), null, null);

        verify(resolverRepository).findVisibleCatalogEntries(eq(orgId), eq(userId), eq(false),
                anyBoolean(), anyBoolean(), eq(501));
    }

    @Test
    void approvedOnlyScopeIsPassedThrough() {
        ArgumentCaptor<Boolean> approvedOnly = ArgumentCaptor.forClass(Boolean.class);
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(), anyBoolean(),
                approvedOnly.capture(), eq(501))).thenReturn(List.of());
        CatalogService service = serviceWith(props(true, 500, "approved_only", false));

        service.catalog(identity(Role.Skill_Consumer), null, null);

        assertThat(approvedOnly.getValue()).isTrue();
    }

    @Test
    void allScopeDoesNotRestrictToApproved() {
        ArgumentCaptor<Boolean> approvedOnly = ArgumentCaptor.forClass(Boolean.class);
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(), anyBoolean(),
                approvedOnly.capture(), eq(501))).thenReturn(List.of());
        CatalogService service = serviceWith(props(true, 500, "all", false));

        service.catalog(identity(Role.Skill_Consumer), null, null);

        assertThat(approvedOnly.getValue()).isFalse();
    }

    @Test
    void includeArchivedDefaultIsAppliedWhenCallerDoesNotSpecify() {
        ArgumentCaptor<Boolean> archived = ArgumentCaptor.forClass(Boolean.class);
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(),
                archived.capture(), anyBoolean(), eq(501))).thenReturn(List.of());
        CatalogService service = serviceWith(props(true, 500, "all", true));

        service.catalog(identity(Role.Skill_Consumer), null, null);

        assertThat(archived.getValue()).isTrue();
    }

    @Test
    void callerIncludeArchivedOverridesDefault() {
        ArgumentCaptor<Boolean> archived = ArgumentCaptor.forClass(Boolean.class);
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(),
                archived.capture(), anyBoolean(), eq(501))).thenReturn(List.of());
        CatalogService service = serviceWith(props(true, 500, "all", true));

        service.catalog(identity(Role.Skill_Consumer), false, null);

        assertThat(archived.getValue()).isFalse();
    }

    @Test
    void entryCarriesVersionIdForDirectFetch() {
        CatalogEntryProjection e = entry("alpha");
        when(resolverRepository.findVisibleCatalogEntries(any(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), eq(501))).thenReturn(List.of(e));
        CatalogService service = serviceWith(props(true, 500, "all", false));

        CatalogResult result = service.catalog(identity(Role.Skill_Consumer), null, null);

        assertThat(result.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.skillVersionId()).isEqualTo(e.getSkillVersionId());
            assertThat(entry.slug()).isEqualTo("alpha");
            assertThat(entry.name()).isEqualTo("alpha");
        });
    }

    @Test
    void resolveSlugToVersionIdReturnsCurrentVersionOrgScoped() {
        UUID vid = UUID.randomUUID();
        var skill = new com.alexandria.skill.entity.SkillEntity();
        skill.setCurrentVersionId(vid);
        when(skillRepository.findByOrganizationIdAndSlug(orgId, "pdf-tools"))
                .thenReturn(java.util.Optional.of(skill));
        CatalogService service = serviceWith(props(true, 500, "all", false));

        assertThat(service.resolveSlugToVersionId(identity(Role.Skill_Consumer), "pdf-tools"))
                .contains(vid);
    }

    @Test
    void resolveSlugToVersionIdEmptyWhenAbsentOrBlank() {
        when(skillRepository.findByOrganizationIdAndSlug(orgId, "ghost"))
                .thenReturn(java.util.Optional.empty());
        CatalogService service = serviceWith(props(true, 500, "all", false));

        assertThat(service.resolveSlugToVersionId(identity(Role.Skill_Consumer), "ghost")).isEmpty();
        assertThat(service.resolveSlugToVersionId(identity(Role.Skill_Consumer), "  ")).isEmpty();
        assertThat(service.resolveSlugToVersionId(identity(Role.Skill_Consumer), null)).isEmpty();
    }

    @Test
    void resolveSlugToVersionIdEmptyWhenSkillHasNoCurrentVersion() {
        var skill = new com.alexandria.skill.entity.SkillEntity();  // currentVersionId null
        when(skillRepository.findByOrganizationIdAndSlug(orgId, "versionless"))
                .thenReturn(java.util.Optional.of(skill));
        CatalogService service = serviceWith(props(true, 500, "all", false));

        assertThat(service.resolveSlugToVersionId(identity(Role.Skill_Consumer), "versionless"))
                .isEmpty();
    }
}
