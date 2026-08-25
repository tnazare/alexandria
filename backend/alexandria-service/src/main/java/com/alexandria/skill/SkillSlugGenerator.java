package com.alexandria.skill;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Generates URL-safe slugs for skills with collision resolution.
 *
 * <p>The slug algorithm:
 * <ol>
 *   <li>Lowercase the name</li>
 *   <li>Replace spaces and underscores with hyphens</li>
 *   <li>Strip characters not in [a-z0-9-]</li>
 *   <li>Collapse consecutive hyphens into one</li>
 *   <li>Trim leading/trailing hyphens</li>
 * </ol>
 *
 * <p>If the resulting slug collides with an existing skill within the same organization,
 * a numeric suffix ("-2", "-3", etc.) is appended until a unique slug is found.</p>
 */
@Component
public class SkillSlugGenerator {

    /**
     * Generates a URL-safe slug from a skill name.
     *
     * @param name the skill name to slugify
     * @return a well-formed slug containing only [a-z0-9-], no consecutive hyphens, no leading/trailing hyphens
     */
    public static String generateSlug(String name) {
        String slug = name.toLowerCase();
        slug = slug.replace(' ', '-').replace('_', '-');
        slug = slug.replaceAll("[^a-z0-9\\-]", "");
        slug = slug.replaceAll("-{2,}", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug;
    }

    /**
     * Generates a unique slug within the organization by appending a numeric suffix if needed.
     *
     * @param name       the skill name to slugify
     * @param orgId      the organization ID to check for collisions
     * @param repository the skill repository for collision checks
     * @return a unique slug within the organization
     */
    public String generateUniqueSlug(String name, UUID orgId, SkillRepository repository) {
        String baseSlug = generateSlug(name);

        if (!repository.existsByOrganizationIdAndSlug(orgId, baseSlug)) {
            return baseSlug;
        }

        int suffix = 2;
        while (true) {
            String candidateSlug = baseSlug + "-" + suffix;
            if (!repository.existsByOrganizationIdAndSlug(orgId, candidateSlug)) {
                return candidateSlug;
            }
            suffix++;
        }
    }
}
