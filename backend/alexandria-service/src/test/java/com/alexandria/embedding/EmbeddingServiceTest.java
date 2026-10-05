package com.alexandria.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexandria.config.SearchProperties;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.SkillVersionRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmbeddingService}.
 *
 * <p>Covers the three behaviors the task calls out: (1) the assembled source text mirrors the FTS
 * A/B/C/D source fields (name, description, canonicalInstructions, applicability, whenToUse,
 * examples), (2) the source-hash skip logic avoids re-embedding unchanged skills, and (3) reindex at
 * the write boundary (re)embeds when the source changes. Plus the fts-mode no-op guard, backfill, and
 * the pgvector literal serialization.</p>
 *
 * <p>Validates: Requirements 3.3, 3.4, 8.2</p>
 */
class EmbeddingServiceTest {

    private EmbeddingProvider provider;
    private EmbeddingRepository embeddingRepository;
    private SkillRepository skillRepository;
    private SkillVersionRepository versionRepository;

    private static final UUID ORG_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        provider = mock(EmbeddingProvider.class);
        embeddingRepository = mock(EmbeddingRepository.class);
        skillRepository = mock(SkillRepository.class);
        versionRepository = mock(SkillVersionRepository.class);

        lenient().when(provider.modelId()).thenReturn("local-hashing-ngram-v1");
        lenient().when(provider.dimension()).thenReturn(4);
        lenient().when(provider.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f, 0.4f});
    }

    private EmbeddingService service(String mode) {
        SearchProperties props = new SearchProperties(mode, 4, "local", null);
        return new EmbeddingService(provider, embeddingRepository, skillRepository,
                versionRepository, props);
    }

    private SkillEntity skillWithVersion(UUID skillId, UUID versionId) {
        SkillEntity skill = new SkillEntity();
        skill.setId(skillId);
        skill.setOrganizationId(ORG_ID);
        skill.setName("Terraform Review");
        skill.setDescription("Review terraform modules for drift");
        skill.setLifecycleStatus("Published");
        skill.setCurrentVersionId(versionId);

        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(versionId);
        version.setSkillId(skillId);
        version.setCanonicalInstructions("Run terraform plan and inspect the diff");
        version.setApplicability("Infrastructure-as-code repositories");
        version.setWhenToUse("When a PR changes .tf files");
        version.setExamples("example: plan then apply");

        lenient().when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));
        lenient().when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));
        return skill;
    }

    // ---------------- source assembly ----------------

    @Test
    void assembleSourceMirrorsFtsSourceFields() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillEntity skill = skillWithVersion(skillId, versionId);

        String source = service("semantic").assembleSource(skill);

        // Mirrors the FTS source fields: name + description + current version's
        // canonicalInstructions + applicability + whenToUse + examples (R3.3).
        assertThat(source)
                .contains("Terraform Review")
                .contains("Review terraform modules for drift")
                .contains("Run terraform plan and inspect the diff")
                .contains("Infrastructure-as-code repositories")
                .contains("When a PR changes .tf files")
                .contains("example: plan then apply");
    }

    @Test
    void assembleSourceIsNullSafeForMissingVersionFields() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        SkillEntity skill = new SkillEntity();
        skill.setId(skillId);
        skill.setOrganizationId(ORG_ID);
        skill.setName("Lonely Skill");
        skill.setDescription("Only name and description");
        skill.setCurrentVersionId(versionId);

        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(versionId);
        version.setCanonicalInstructions("instructions only");
        // applicability / whenToUse / examples left null

        when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

        String source = service("semantic").assembleSource(skill);

        assertThat(source)
                .isEqualTo("Lonely Skill Only name and description instructions only");
    }

    @Test
    void assembleSourceHandlesNoCurrentVersion() {
        SkillEntity skill = new SkillEntity();
        skill.setId(UUID.randomUUID());
        skill.setName("No Version");
        skill.setDescription("desc");
        skill.setCurrentVersionId(null);

        assertThat(service("semantic").assembleSource(skill))
                .isEqualTo("No Version desc");
    }

    // ---------------- hash-skip logic ----------------

    @Test
    void reindexSkipsEmbedWhenSourceHashUnchanged() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillEntity skill = skillWithVersion(skillId, versionId);

        EmbeddingService svc = service("semantic");
        String expectedHash = svc.sourceHash(svc.assembleSource(skill));
        when(embeddingRepository.findSourceHash(skillId)).thenReturn(Optional.of(expectedHash));

        svc.reindex(skillId);

        // Unchanged source → no embed, no upsert (R8.2 stability / skip).
        verify(provider, never()).embed(anyString());
        verify(embeddingRepository, never()).upsert(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void reindexEmbedsAndUpsertsWhenHashDiffers() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        skillWithVersion(skillId, versionId);

        when(embeddingRepository.findSourceHash(skillId)).thenReturn(Optional.of("stale-hash"));

        service("semantic").reindex(skillId);

        verify(provider, times(1)).embed(anyString());
        verify(embeddingRepository, times(1))
                .upsert(eq(skillId), eq(ORG_ID), eq("[0.1,0.2,0.3,0.4]"),
                        eq("local-hashing-ngram-v1"), anyString());
    }

    @Test
    void reindexEmbedsWhenNoExistingEmbedding() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        skillWithVersion(skillId, versionId);

        when(embeddingRepository.findSourceHash(skillId)).thenReturn(Optional.empty());

        service("semantic").reindex(skillId);

        verify(provider, times(1)).embed(anyString());
        verify(embeddingRepository, times(1))
                .upsert(eq(skillId), eq(ORG_ID), anyString(), anyString(), anyString());
    }

    // ---------------- reindex-on-update behavior ----------------

    @Test
    void reindexIsNoOpUnderFtsMode() {
        UUID skillId = UUID.randomUUID();

        service("fts").reindex(skillId);

        // fts mode: no provider lookup, no DB access — existing FTS-only deployments unaffected.
        verify(skillRepository, never()).findById(any());
        verify(provider, never()).embed(anyString());
        verify(embeddingRepository, never()).upsert(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void reindexIsNoOpWhenSkillMissing() {
        UUID skillId = UUID.randomUUID();
        when(skillRepository.findById(skillId)).thenReturn(Optional.empty());

        service("hybrid").reindex(skillId);

        verify(provider, never()).embed(anyString());
    }

    // ---------------- backfill ----------------

    @Test
    void backfillAllEmbedsEligibleSkillsInOrg() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillEntity eligible = skillWithVersion(skillId, versionId);

        // A blocked skill and a different-org skill must be excluded.
        SkillEntity blocked = new SkillEntity();
        blocked.setId(UUID.randomUUID());
        blocked.setOrganizationId(ORG_ID);
        blocked.setLifecycleStatus("Blocked");
        blocked.setCurrentVersionId(UUID.randomUUID());

        SkillEntity otherOrg = new SkillEntity();
        otherOrg.setId(UUID.randomUUID());
        otherOrg.setOrganizationId(UUID.randomUUID());
        otherOrg.setLifecycleStatus("Published");
        otherOrg.setCurrentVersionId(UUID.randomUUID());

        when(skillRepository.findAll()).thenReturn(List.of(eligible, blocked, otherOrg));
        when(embeddingRepository.findSourceHash(skillId)).thenReturn(Optional.empty());

        int written = service("semantic").backfillAll(ORG_ID);

        assertThat(written).isEqualTo(1);
        verify(embeddingRepository, times(1))
                .upsert(eq(skillId), eq(ORG_ID), anyString(), anyString(), anyString());
    }

    @Test
    void countUnembeddedDelegatesToRepository() {
        when(embeddingRepository.countUnembedded(ORG_ID)).thenReturn(7L);

        assertThat(service("semantic").countUnembedded(ORG_ID)).isEqualTo(7L);
    }

    // ---------------- vector literal ----------------

    @Test
    void toVectorLiteralSerializesPgvectorFormat() {
        assertThat(EmbeddingService.toVectorLiteral(new float[] {0.1f, -0.2f, 0.3f}))
                .isEqualTo("[0.1,-0.2,0.3]");
        assertThat(EmbeddingService.toVectorLiteral(new float[] {})).isEqualTo("[]");
    }
}
