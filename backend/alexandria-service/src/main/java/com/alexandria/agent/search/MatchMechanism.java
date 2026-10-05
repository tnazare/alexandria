package com.alexandria.agent.search;

/**
 * The retrieval mechanism that surfaced a candidate. Tracked per-candidate through fusion so the
 * resolve payload's {@code matchReasons} can honestly report <em>why</em> a skill ranked (R5.4),
 * instead of the former hardcoded "Matched full-text search".
 *
 * <p>In {@code fts} mode every candidate carries {@link #FTS}; in {@code semantic} mode every
 * candidate carries {@link #SEMANTIC}; in {@code hybrid} mode a candidate carries one or both,
 * depending on which source list(s) surfaced it.</p>
 */
public enum MatchMechanism {

    /** Candidate surfaced by PostgreSQL full-text search ({@code ts_rank} over the search vector). */
    FTS("Matched full-text search"),

    /** Candidate surfaced by semantic (pgvector cosine) retrieval. */
    SEMANTIC("Matched semantic search");

    private final String reason;

    MatchMechanism(String reason) {
        this.reason = reason;
    }

    /** Human-readable reason string surfaced in a resolve result's {@code matchReasons}. */
    public String reason() {
        return reason;
    }
}
