package com.alexandria.agent.render;

import com.alexandria.agent.dto.Governance;
import com.alexandria.agent.dto.Resolution;
import com.alexandria.agent.dto.SkillRef;

/**
 * Everything a {@link SkillFetchRenderer} needs to render one fetched skill version, independent of
 * output format. Assembled by the resolver service after governance gates pass.
 *
 * @param ref                   embedded identity (skill id, version id, source instance)
 * @param name                  skill display name
 * @param slug                  skill slug (used in export markers)
 * @param description           skill description (SKILL.md frontmatter description base)
 * @param versionNumber         the resolved version number
 * @param governance            lifecycle/risk/visibility snapshot, incl. deprecation
 * @param resolution            reference-mode resolution + commit policy
 * @param whenToUse             version when-to-use guidance (nullable)
 * @param canonicalInstructions version canonical instructions
 * @param applicability         version applicability (nullable)
 * @param limitations           version limitations (nullable)
 * @param examples              version examples (nullable)
 * @param selectedTool          the tool whose instructions were selected (or canonical marker)
 * @param selectedInstructions  the instructions to render (variant or canonical)
 * @param isCanonicalFallback   true when no variant matched and canonical was used
 * @param supportedTools        tools the version supports (for compatibility metadata)
 */
public record FetchContext(
        SkillRef ref,
        String name,
        String slug,
        String description,
        int versionNumber,
        Governance governance,
        Resolution resolution,
        String whenToUse,
        String canonicalInstructions,
        String applicability,
        String limitations,
        String examples,
        String selectedTool,
        String selectedInstructions,
        boolean isCanonicalFallback,
        java.util.List<String> supportedTools
) {
}
