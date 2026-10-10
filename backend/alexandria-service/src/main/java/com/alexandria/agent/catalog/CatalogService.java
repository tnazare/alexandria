package com.alexandria.agent.catalog;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.CatalogProperties;
import com.alexandria.skill.CatalogEntryProjection;
import com.alexandria.skill.SkillRepository;
import com.alexandria.skill.SkillResolverRepository;
import com.alexandria.skill.entity.SkillEntity;
import com.alexandria.user.entity.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the resident Skill Catalog for an authenticated identity, enforcing ACL, governance, the
 * configured lifecycle scope, and the {@code maxEntries} payload cap in one place.
 *
 * <p>This is the service seam the MCP {@code resources/read} handler calls. It reuses the exact
 * query-layer ACL of the resolver (via {@link SkillResolverRepository#findVisibleCatalogEntries}) and
 * applies the same org-admin narrow bypass as {@code ResolverServiceImpl.doResolve}. Side-effect free
 * except for observability recorded by the caller (R2.5, R7).</p>
 *
 * <p>See the {@code skill-catalog} spec, R2 / R5 / R6.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final SkillResolverRepository resolverRepository;
    private final SkillRepository skillRepository;
    private final CatalogProperties catalogProperties;

    /**
     * Returns the resident catalog for {@code identity}. Enforces org scoping + the full visibility
     * ACL at the query layer, the org-admin narrow bypass, the configured {@code lifecycleScope}, and
     * the {@code maxEntries} cap (querying one extra row to detect truncation).
     *
     * @param identity         the authenticated agent identity (org + role + user)
     * @param includeArchived  whether to include Archived skills (null → config default)
     * @param limit            optional caller cap, clamped to {@code maxEntries} (null → maxEntries)
     * @return the ACL-scoped, ordered, capped catalog with a truncation flag
     */
    @Transactional(readOnly = true)
    public CatalogResult catalog(AlexandriaUserDetails identity, Boolean includeArchived, Integer limit) {
        int maxEntries = catalogProperties.maxEntries();
        int effectiveLimit = limit == null ? maxEntries : Math.min(Math.max(limit, 0), maxEntries);
        boolean isOrgAdmin = identity.role() == Role.Org_Admin;
        boolean archived = includeArchived != null
                ? includeArchived
                : Boolean.TRUE.equals(catalogProperties.includeArchivedDefault());
        boolean approvedOnly = catalogProperties.approvedOnly();

        // Query one more than the cap so we can tell whether the visible set was larger than the cap
        // (R5.2) without a separate count query.
        int queryLimit = effectiveLimit == Integer.MAX_VALUE ? effectiveLimit : effectiveLimit + 1;
        List<CatalogEntryProjection> rows = resolverRepository.findVisibleCatalogEntries(
                identity.organizationId(), identity.userId(), isOrgAdmin, archived, approvedOnly,
                queryLimit);

        boolean truncated = rows.size() > effectiveLimit;
        List<CatalogEntryProjection> capped =
                truncated ? rows.subList(0, effectiveLimit) : rows;

        List<CatalogEntry> entries = new ArrayList<>(capped.size());
        for (CatalogEntryProjection r : capped) {
            entries.add(new CatalogEntry(
                    r.getSkillId(), r.getSkillVersionId(), r.getSlug(), r.getName(),
                    r.getDescription(), r.getLifecycleStatus(), r.getRiskLevel(), r.getVisibility()));
        }

        // Observability (R7.1): one record per catalog read, counts/ids only — never skill names or
        // descriptions (R7.3). INFO so it is visible in default operator logs.
        log.info("Served skill catalog: org={} entries={} truncated={} scope={} includeArchived={}",
                identity.organizationId(), entries.size(), truncated,
                catalogProperties.lifecycleScope(), archived);

        return new CatalogResult(entries, truncated, entries.size());
    }

    /**
     * Resolves a catalog line's {@code slug} to the skill's current {@code skillVersionId}, scoped to
     * the caller's organization, so {@code fetch_skill} can accept the compact slug the resident
     * catalog advertises instead of a UUID.
     *
     * <p>This is only the org-scoped first hop. Per-user visibility and governance (Blocked, etc.) are
     * still enforced by {@code ResolverService.fetch} on the returned version id, so a slug the caller
     * is not allowed to see yields a version id that {@code fetch} then rejects with a 404 — the same
     * anti-enumeration posture as a direct version fetch.</p>
     *
     * @return the current version id for the slug in the caller's org, or empty if no such skill
     *         exists there or it has no current version
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveSlugToVersionId(AlexandriaUserDetails identity, String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return skillRepository.findByOrganizationIdAndSlug(identity.organizationId(), slug.trim())
                .map(SkillEntity::getCurrentVersionId)
                .filter(vid -> vid != null);
    }
}
