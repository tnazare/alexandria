package com.alexandria.taxonomy;

import com.alexandria.auth.AlexandriaAuthentication;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.taxonomy.dto.CreateTaxonomyEntryRequest;
import com.alexandria.taxonomy.dto.TaxonomyEntryResponse;
import com.alexandria.taxonomy.dto.TaxonomyGroupResponse;
import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for taxonomy management endpoints.
 *
 * <p>Read operations are available to all authenticated users. Mutating
 * operations (create, update, deactivate) are restricted to Catalog_Admin
 * and Org_Admin roles.</p>
 */
@RestController
@RequiredArgsConstructor
public class TaxonomyResource implements TaxonomyApi {

    private final TaxonomyService taxonomyService;

    @Override
    public ResponseEntity<List<TaxonomyGroupResponse>> getAllEntriesGrouped() {
        AlexandriaUserDetails user = currentUser();
        Map<String, List<TaxonomyEntryEntity>> grouped = taxonomyService.getAllGrouped(user.organizationId());

        List<TaxonomyGroupResponse> body = grouped.entrySet().stream()
                .map(entry -> new TaxonomyGroupResponse(
                        entry.getKey(),
                        entry.getValue().stream().map(TaxonomyResource::toResponse).toList()))
                .sorted(java.util.Comparator.comparing(TaxonomyGroupResponse::tagType))
                .toList();

        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<List<TaxonomyEntryResponse>> getEntriesByType(String tagType) {
        AlexandriaUserDetails user = currentUser();
        List<TaxonomyEntryResponse> body = taxonomyService.getByType(user.organizationId(), tagType).stream()
                .map(TaxonomyResource::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    @PreAuthorize("hasAnyRole('Catalog_Admin', 'Org_Admin')")
    public ResponseEntity<TaxonomyEntryResponse> createEntry(String tagType, CreateTaxonomyEntryRequest request) {
        AlexandriaUserDetails user = currentUser();
        TaxonomyEntryEntity entry = taxonomyService.create(
                user.organizationId(), tagType, request.value(), request.description(), user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(entry));
    }

    @Override
    @PreAuthorize("hasAnyRole('Catalog_Admin', 'Org_Admin')")
    public ResponseEntity<TaxonomyEntryResponse> updateEntry(
            String tagType, UUID tagId, CreateTaxonomyEntryRequest request) {
        AlexandriaUserDetails user = currentUser();
        TaxonomyEntryEntity entry = taxonomyService.update(
                user.organizationId(), tagId, request.value(), request.description(), user.userId());
        return ResponseEntity.ok(toResponse(entry));
    }

    @Override
    @PreAuthorize("hasAnyRole('Catalog_Admin', 'Org_Admin')")
    public ResponseEntity<TaxonomyEntryResponse> deactivateEntry(String tagType, UUID tagId) {
        AlexandriaUserDetails user = currentUser();
        TaxonomyEntryEntity entry = taxonomyService.deactivate(user.organizationId(), tagId, user.userId());
        return ResponseEntity.ok(toResponse(entry));
    }

    // --- helpers ---

    private static TaxonomyEntryResponse toResponse(TaxonomyEntryEntity entry) {
        return new TaxonomyEntryResponse(
                entry.getId(),
                entry.getTagType(),
                entry.getValue(),
                entry.getDescription(),
                entry.isActive(),
                entry.getCreatedAt()
        );
    }

    private AlexandriaUserDetails currentUser() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AlexandriaAuthentication auth
                ? auth.getPrincipal()
                : null;
    }
}
