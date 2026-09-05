package com.alexandria.agent.dto;

/**
 * Envelope-level advertisement of what this resolver instance supports. Lets an orchestrating
 * client detect available operations without version-sniffing.
 *
 * <p>In this spec {@code resolve} and {@code fetch} are always true, and {@code invoke} and
 * {@code compose} are always false — the latter two are reserved for a future orchestration
 * capability and are inert placeholders.</p>
 */
public record Capabilities(
        boolean resolve,
        boolean fetch,
        boolean invoke,
        boolean compose
) {
}
