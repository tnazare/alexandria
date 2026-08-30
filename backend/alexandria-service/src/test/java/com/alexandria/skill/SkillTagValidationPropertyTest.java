package com.alexandria.skill;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.organization.OrganizationRepository;
import com.alexandria.organization.entity.OrganizationEntity;
import com.alexandria.skill.entity.SkillEntity;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence-backed property test for atomic tag validation.
 *
 * <p><b>Property 12: Tag validation against active taxonomy</b> —
 * when assigning tags to a skill, if ANY tag ID is invalid (unknown), inactive,
 * or belongs to another organization, the entire assignment is rejected and the
 * skill's existing tags are left unchanged (no partial assignment).</p>
 *
 * <p><b>Validates: Requirements 7.2</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("skill-crud")
@Tag("Property-12-Tag-validation")
class SkillTagValidationPropertyTest {

    private static final Random RANDOM = new Random(7);

    @Autowired
    private SkillService skillService;
    @Autowired
    private SkillTagRepository skillTagRepository;
    @Autowired
    private TaxonomyEntryRepository taxonomyEntryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamMemberRepository teamMemberRepository;

    private UUID orgId;
    private UUID userId;
    private UUID teamId;
    private AlexandriaUserDetails user;

    private final List<UUID> validActiveTagIds = new ArrayList<>();
    private UUID inactiveTagId;
    private UUID otherOrgTagId;

    @BeforeAll
    void setup() {
        var org = new OrganizationEntity();
        org.setName("Tag Org");
        org.setSlug("tag-org-" + UUID.randomUUID());
        org = organizationRepository.saveAndFlush(org);
        orgId = org.getId();

        var otherOrg = new OrganizationEntity();
        otherOrg.setName("Other Org");
        otherOrg.setSlug("other-org-" + UUID.randomUUID());
        otherOrg = organizationRepository.saveAndFlush(otherOrg);
        UUID otherOrgId = otherOrg.getId();

        var u = new UserEntity();
        u.setOrganizationId(orgId);
        u.setExternalSubject("subject-tag-" + UUID.randomUUID());
        u.setEmail("tag@test.com");
        u.setRole(Role.Skill_Author.name());
        u.setEnabled(true);
        userId = userRepository.saveAndFlush(u).getId();

        var team = new TeamEntity();
        team.setOrganizationId(orgId);
        team.setName("Tag Team");
        team.setSlug("tag-team-" + UUID.randomUUID().toString().substring(0, 8));
        team.setCreatedBy(userId);
        team = teamRepository.saveAndFlush(team);
        teamId = team.getId();

        var member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setTeamRole("owner");
        teamMemberRepository.saveAndFlush(member);

        user = new AlexandriaUserDetails(userId, orgId, Role.Skill_Author, "tag@test.com");

        // Valid, active tags in the user's org
        for (int i = 0; i < 4; i++) {
            validActiveTagIds.add(createTag(orgId, "task_type", "valid-" + i, true));
        }
        // Inactive tag in the user's org
        inactiveTagId = createTag(orgId, "language", "inactive-tag", false);
        // Active tag belonging to a different org
        otherOrgTagId = createTag(otherOrgId, "framework", "other-org-tag", true);
    }

    private UUID createTag(UUID organizationId, String tagType, String value, boolean active) {
        var tag = new TaxonomyEntryEntity();
        tag.setOrganizationId(organizationId);
        tag.setTagType(tagType);
        tag.setValue(value + "-" + UUID.randomUUID().toString().substring(0, 8));
        tag.setActive(active);
        return taxonomyEntryRepository.saveAndFlush(tag).getId();
    }

    @Test
    @DisplayName("Any invalid/inactive/wrong-org tag rejects the whole assignment; existing tags unchanged")
    void invalidTagSetRejectedAtomically() {
        for (int i = 0; i < 40; i++) {
            SkillEntity skill = skillService.create(
                    "Tag Skill " + i + " " + UUID.randomUUID().toString().substring(0, 6),
                    "desc", teamId, "instructions", user);
            UUID skillId = skill.getId();

            // First, assign a valid baseline set so we can confirm it stays unchanged.
            List<UUID> baseline = List.of(validActiveTagIds.get(0), validActiveTagIds.get(1));
            skillService.update(skillId, tagParams(baseline), user);
            List<UUID> baselinePersisted = persistedTagIds(skillId);
            assertThat(baselinePersisted).containsExactlyInAnyOrderElementsOf(baseline);

            // Build a "poisoned" set: some valid tags plus at least one bad tag.
            List<UUID> poisoned = new ArrayList<>();
            poisoned.add(validActiveTagIds.get(2));
            poisoned.add(pickBadTag());
            if (RANDOM.nextBoolean()) {
                poisoned.add(validActiveTagIds.get(3));
            }
            Collections.shuffle(poisoned, RANDOM);

            // The assignment must be rejected wholesale.
            assertThatThrownBy(() -> skillService.update(skillId, tagParams(poisoned), user))
                    .isInstanceOf(IllegalArgumentException.class);

            // Existing tags remain exactly the baseline — no partial assignment.
            assertThat(persistedTagIds(skillId))
                    .as("iteration %d: tags must be unchanged after rejected assignment", i)
                    .containsExactlyInAnyOrderElementsOf(baseline);
        }
    }

    private UUID pickBadTag() {
        return switch (RANDOM.nextInt(3)) {
            case 0 -> UUID.randomUUID();   // unknown id
            case 1 -> inactiveTagId;        // inactive
            default -> otherOrgTagId;       // wrong org
        };
    }

    private UpdateParams tagParams(List<UUID> tagIds) {
        return new UpdateParams(null, null, null, null, null, null, null, null,
                tagIds, null, null);
    }

    private List<UUID> persistedTagIds(UUID skillId) {
        return skillTagRepository.findBySkillId(skillId).stream()
                .map(t -> t.getTaxonomyEntryId())
                .toList();
    }
}
