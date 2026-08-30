package com.alexandria.skill;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.skill.entity.SkillVersionEntity;
import com.alexandria.taxonomy.TaxonomyEntryRepository;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import com.alexandria.team.TeamMemberRepository;
import com.alexandria.team.TeamRepository;
import com.alexandria.team.entity.TeamEntity;
import com.alexandria.team.entity.TeamMemberEntity;
import com.alexandria.user.UserRepository;
import com.alexandria.user.entity.Role;
import com.alexandria.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-backed property tests for skill retrieval and version ordering.
 *
 * <p><b>Property 15: Skill retrieval round-trip</b> — a skill created with
 * random valid inputs is returned by {@code getById} with all persisted fields
 * matching the inputs.</p>
 *
 * <p><b>Property 16: Version list ordering</b> — a skill edited through N
 * publish/edit cycles exposes its versions in strictly descending version-number
 * order with no gaps (N, N-1, ..., 1).</p>
 *
 * <p>Because jqwik's generation lifecycle does not compose cleanly with the
 * Spring test context, each property is validated by iterating over many
 * randomized inputs within a single Spring-managed test method.</p>
 *
 * <p><b>Validates: Requirements 2.1, 2.6</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("skill-crud")
@Tag("Property-15-16-Retrieval-and-version-order")
class SkillRetrievalAndVersionOrderPropertyTest {

    private static final Random RANDOM = new Random(42);

    @Autowired
    private SkillService skillService;
    @Autowired
    private SkillVersionRepository skillVersionRepository;
    @Autowired
    private SkillTagRepository skillTagRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamMemberRepository teamMemberRepository;
    @Autowired
    private TaxonomyEntryRepository taxonomyEntryRepository;

    private UUID orgId;
    private UUID userId;
    private UUID teamId;
    private AlexandriaUserDetails user;

    @BeforeAll
    void setup() {
        var org = new OrganizationEntity();
        org.setName("Retrieval Org");
        org.setSlug("retrieval-org-" + UUID.randomUUID());
        org = organizationRepository.saveAndFlush(org);
        orgId = org.getId();

        var u = new UserEntity();
        u.setOrganizationId(orgId);
        u.setExternalSubject("subject-retrieval-" + UUID.randomUUID());
        u.setEmail("retrieval@test.com");
        u.setRole(Role.Skill_Author.name());
        u.setEnabled(true);
        userId = userRepository.saveAndFlush(u).getId();

        var team = new TeamEntity();
        team.setOrganizationId(orgId);
        team.setName("Retrieval Team");
        team.setSlug("retrieval-team-" + UUID.randomUUID().toString().substring(0, 8));
        team.setCreatedBy(userId);
        team = teamRepository.saveAndFlush(team);
        teamId = team.getId();

        var member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setTeamRole("owner");
        teamMemberRepository.saveAndFlush(member);

        user = new AlexandriaUserDetails(userId, orgId, Role.Skill_Author, "retrieval@test.com");
    }

    @Test
    @DisplayName("Property 15: created skills round-trip through getById with all fields intact")
    void retrievalRoundTrip() {
        for (int i = 0; i < 30; i++) {
            String name = "Round Trip Skill " + i + " " + randomAlpha(8);
            String description = "Description " + randomAlpha(20);
            String instructions = "Instructions " + randomAlpha(50);

            SkillEntity created = skillService.create(name, description, teamId, instructions, user);

            SkillEntity fetched = skillService.getById(created.getId(), user);

            assertThat(fetched.getId()).isEqualTo(created.getId());
            assertThat(fetched.getName()).isEqualTo(name);
            assertThat(fetched.getDescription()).isEqualTo(description);
            assertThat(fetched.getOwnerTeamId()).isEqualTo(teamId);
            assertThat(fetched.getOrganizationId()).isEqualTo(orgId);
            assertThat(fetched.getCreatedBy()).isEqualTo(userId);
            assertThat(fetched.getLifecycleStatus()).isEqualTo("Draft");
            assertThat(fetched.getVisibility()).isEqualTo("private");

            SkillVersionEntity version = skillVersionRepository.findById(fetched.getCurrentVersionId()).orElseThrow();
            assertThat(version.getVersionNumber()).isEqualTo(1);
            assertThat(version.getCanonicalInstructions()).isEqualTo(instructions);
        }
    }

    @Test
    @DisplayName("Property 16: version list is strictly descending with no gaps")
    void versionListOrdering() {
        for (int iteration = 0; iteration < 10; iteration++) {
            int extraVersions = 1 + RANDOM.nextInt(9); // total versions 2..10

            SkillEntity skill = skillService.create(
                    "Versioned Skill " + iteration + " " + randomAlpha(6),
                    "desc", teamId, "v1 instructions", user);
            UUID skillId = skill.getId();

            // Publish then edit repeatedly to force new versions.
            for (int v = 0; v < extraVersions; v++) {
                // Make it publishable, publish, then edit to create a new version.
                makePublishable(skillId);
                skillService.publish(skillId, user);
                // Editing a published skill creates a new version and reverts to Draft.
                skillService.update(skillId,
                        new UpdateParams(null, null, "updated instructions v" + (v + 2),
                                null, null, null, null, "changelog v" + (v + 2), null, null, null),
                        user);
            }

            int expectedTotal = extraVersions + 1;

            List<SkillVersionEntity> versions = skillVersionRepository
                    .findBySkillIdOrderByVersionNumberDesc(skillId, PageRequest.of(0, 50))
                    .getContent();

            assertThat(versions).hasSize(expectedTotal);

            // Strictly descending, no gaps: expectedTotal, expectedTotal-1, ..., 1
            for (int idx = 0; idx < versions.size(); idx++) {
                assertThat(versions.get(idx).getVersionNumber()).isEqualTo(expectedTotal - idx);
            }
        }
    }

    /**
     * Populates the current draft version with all metadata required for publishing.
     *
     * <p>The task_type tag is only assigned when the skill has none yet. Re-sending
     * the same tag id on every call would collide with the unique
     * (skill_id, taxonomy_entry_id) constraint, since the delete-and-reinsert
     * happens within a single transaction; a tag assigned on version 1 remains
     * attached to the skill across subsequent versions.</p>
     */
    private void makePublishable(UUID skillId) {
        boolean hasTag = !skillTagRepository.findBySkillId(skillId).isEmpty();
        List<UUID> tagIds = hasTag ? null : List.of(ensureTaskTypeTag());
        skillService.update(skillId,
                new UpdateParams(null, null, null,
                        "applicability text", null, null, "example text", null,
                        tagIds, List.of("cursor"), "low"),
                user);
    }

    private UUID cachedTagId;

    private UUID ensureTaskTypeTag() {
        if (cachedTagId != null) {
            return cachedTagId;
        }
        var tag = new TaxonomyEntryEntity();
        tag.setOrganizationId(orgId);
        tag.setTagType("task_type");
        tag.setValue("task-" + randomAlpha(6));
        tag.setActive(true);
        cachedTagId = taxonomyEntryRepository.saveAndFlush(tag).getId();
        return cachedTagId;
    }

    private static String randomAlpha(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }
}
