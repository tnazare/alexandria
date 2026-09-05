package com.alexandria.agent.render;

/**
 * The rendered output of a fetch. Carries the media type so the REST adapter can content-negotiate:
 * {@code agent_skill} produces {@code text/markdown} with a String body; {@code alexandria_native}
 * produces {@code application/json} with a record body serialized by Spring.
 *
 * @param format    the format identifier that produced this result
 * @param mediaType the HTTP media type to respond with
 * @param body      the response body — a String (SKILL.md) or a DTO record (native JSON)
 */
public record FetchResult(
        String format,
        String mediaType,
        Object body
) {
}
