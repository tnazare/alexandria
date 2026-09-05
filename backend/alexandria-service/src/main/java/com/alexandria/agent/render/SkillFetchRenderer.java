package com.alexandria.agent.render;

/**
 * Strategy that renders a fetched skill version into one output format. One {@code @Component} per
 * format; the resolver service selects among them by {@link #format()}, defaulting to
 * {@code agent_skill}.
 */
public interface SkillFetchRenderer {

    /** The format identifier this renderer produces (e.g. {@code agent_skill}, {@code alexandria_native}). */
    String format();

    /** Renders the context into a {@link FetchResult} carrying the media type and body. */
    FetchResult render(FetchContext ctx);
}
