package com.alexandria.agent.dto;

import java.util.List;

/**
 * One ranked candidate in a resolve response. Carries embedded identity, governance, and match
 * reasons but never full instruction bodies — those are returned only by fetch.
 *
 * <p>The {@code kind}, {@code invocation}, and {@code relationships} fields are forward-compatible
 * placeholders: {@code kind} is always {@code "skill"} in this spec, {@code invocation} is always
 * null, and {@code relationships} is always empty. They reserve space for a future orchestration
 * capability and are inert here.</p>
 *
 * @param kind          result discriminator; always "skill" in this spec
 * @param ref           embedded identity (skill id, version id, source instance)
 * @param name          the skill's display name
 * @param summary       a short summary (the skill description)
 * @param score         the relevance score used for ordering
 * @param governance    lifecycle/risk/visibility snapshot
 * @param matchReasons  short human-readable reasons this candidate was returned
 * @param resolution    reference-mode resolution and commit policy
 * @param delivery      how to fetch the full instructions and in which formats
 * @param invocation    reserved for future orchestration; always null in this spec
 * @param relationships reserved for future skill relationships; always empty in this spec
 */
public record ResolveResult(
        String kind,
        SkillRef ref,
        String name,
        String summary,
        double score,
        Governance governance,
        List<String> matchReasons,
        Resolution resolution,
        Delivery delivery,
        Object invocation,
        List<Object> relationships
) {
}
