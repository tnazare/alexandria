package com.alexandria.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("SkillSlugGenerator")
@ExtendWith(MockitoExtension.class)
class SkillSlugGeneratorTest {

    @Mock
    private SkillRepository skillRepository;

    private final SkillSlugGenerator slugGenerator = new SkillSlugGenerator();

    @Nested
    @DisplayName("generateSlug")
    class GenerateSlugTests {

        @Test
        @DisplayName("lowercases the name")
        void lowercasesName() {
            assertThat(SkillSlugGenerator.generateSlug("My Skill Name")).isEqualTo("my-skill-name");
        }

        @Test
        @DisplayName("replaces spaces with hyphens")
        void replacesSpaces() {
            assertThat(SkillSlugGenerator.generateSlug("hello world")).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("replaces underscores with hyphens")
        void replacesUnderscores() {
            assertThat(SkillSlugGenerator.generateSlug("hello_world")).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("strips invalid characters")
        void stripsInvalidChars() {
            assertThat(SkillSlugGenerator.generateSlug("hello@world!")).isEqualTo("helloworld");
        }

        @Test
        @DisplayName("collapses consecutive hyphens")
        void collapsesConsecutiveHyphens() {
            assertThat(SkillSlugGenerator.generateSlug("hello   world")).isEqualTo("hello-world");
            assertThat(SkillSlugGenerator.generateSlug("hello---world")).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("trims leading and trailing hyphens")
        void trimsLeadingTrailingHyphens() {
            assertThat(SkillSlugGenerator.generateSlug("-hello-")).isEqualTo("hello");
            assertThat(SkillSlugGenerator.generateSlug("  hello  ")).isEqualTo("hello");
        }

        @Test
        @DisplayName("handles complex mixed input")
        void handlesMixedInput() {
            assertThat(SkillSlugGenerator.generateSlug("Code Review - Python & Java"))
                    .isEqualTo("code-review-python-java");
        }

        @Test
        @DisplayName("handles numeric characters")
        void handlesNumericChars() {
            assertThat(SkillSlugGenerator.generateSlug("Version 2.0 Release"))
                    .isEqualTo("version-20-release");
        }
    }

    @Nested
    @DisplayName("generateUniqueSlug")
    class GenerateUniqueSlugTests {

        @Test
        @DisplayName("returns base slug when no collision")
        void returnsBaseSlugWhenNoCollision() {
            UUID orgId = UUID.randomUUID();
            when(skillRepository.existsByOrganizationIdAndSlug(orgId, "my-skill")).thenReturn(false);

            String slug = slugGenerator.generateUniqueSlug("My Skill", orgId, skillRepository);

            assertThat(slug).isEqualTo("my-skill");
        }

        @Test
        @DisplayName("appends -2 on first collision")
        void appendsSuffix2OnFirstCollision() {
            UUID orgId = UUID.randomUUID();
            when(skillRepository.existsByOrganizationIdAndSlug(orgId, "my-skill")).thenReturn(true);
            when(skillRepository.existsByOrganizationIdAndSlug(orgId, "my-skill-2")).thenReturn(false);

            String slug = slugGenerator.generateUniqueSlug("My Skill", orgId, skillRepository);

            assertThat(slug).isEqualTo("my-skill-2");
        }

        @Test
        @DisplayName("increments suffix until unique")
        void incrementsSuffixUntilUnique() {
            UUID orgId = UUID.randomUUID();
            when(skillRepository.existsByOrganizationIdAndSlug(orgId, "my-skill")).thenReturn(true);
            when(skillRepository.existsByOrganizationIdAndSlug(orgId, "my-skill-2")).thenReturn(true);
            when(skillRepository.existsByOrganizationIdAndSlug(orgId, "my-skill-3")).thenReturn(true);
            when(skillRepository.existsByOrganizationIdAndSlug(orgId, "my-skill-4")).thenReturn(false);

            String slug = slugGenerator.generateUniqueSlug("My Skill", orgId, skillRepository);

            assertThat(slug).isEqualTo("my-skill-4");
        }
    }
}
