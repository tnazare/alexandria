package com.alexandria.agent.dto;

import java.util.List;

/**
 * Points a candidate at the fetch operation that returns its full instructions, and lists the
 * output formats available for that fetch.
 *
 * @param fetchHref the relative href of the fetch endpoint for this candidate's version
 * @param formats   the supported output formats (e.g. agent_skill, alexandria_native)
 */
public record Delivery(
        String fetchHref,
        List<String> formats
) {
}
