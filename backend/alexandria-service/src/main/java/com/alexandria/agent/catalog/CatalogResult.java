package com.alexandria.agent.catalog;

import java.util.List;

/**
 * The resident Skill Catalog for one authenticated identity: the ACL-scoped entries, whether the
 * listing was truncated by the {@code maxEntries} cap, and how many entries were returned.
 *
 * @param entries        the catalog lines (already ACL-filtered, scoped, ordered, and capped)
 * @param truncated      true when the caller's visible set exceeded {@code maxEntries} and the list
 *                       was cut (R5.2) — the agent/operator should know the catalog is partial
 * @param totalReturned  the number of entries actually returned (== {@code entries.size()})
 */
public record CatalogResult(List<CatalogEntry> entries, boolean truncated, int totalReturned) {

    public CatalogResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
        totalReturned = entries.size();
    }
}
