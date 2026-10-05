package com.alexandria.agent.search;

/**
 * Thrown when {@code alexandria.search.mode} names a valid mode that has no retrieval strategy wired
 * yet. {@code semantic} and {@code hybrid} are accepted by {@code SearchProperties} validation but
 * are only enabled in later tasks; selecting one before then fails loudly here instead of silently
 * degrading to full-text search.
 */
public class SearchModeNotEnabledException extends RuntimeException {

    public SearchModeNotEnabledException(String mode) {
        super("Search mode '" + mode + "' is not yet enabled. Set ALEXANDRIA_SEARCH_MODE=fts, "
                + "or wait for the semantic/hybrid retrieval tasks to be enabled.");
    }
}
