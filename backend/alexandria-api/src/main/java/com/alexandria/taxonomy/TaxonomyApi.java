package com.alexandria.taxonomy;

import com.alexandria.taxonomy.dto.CreateTaxonomyEntryRequest;
import com.alexandria.taxonomy.dto.TaxonomyEntryResponse;
import com.alexandria.taxonomy.dto.TaxonomyGroupResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

/**
 * REST API contract for taxonomy management.
 */
@RequestMapping("/api/taxonomy")
public interface TaxonomyApi {

    @GetMapping
    ResponseEntity<List<TaxonomyGroupResponse>> getAllEntriesGrouped();

    @GetMapping("/{tagType}")
    ResponseEntity<List<TaxonomyEntryResponse>> getEntriesByType(@PathVariable String tagType);

    @PostMapping("/{tagType}")
    ResponseEntity<TaxonomyEntryResponse> createEntry(
            @PathVariable String tagType,
            @Valid @RequestBody CreateTaxonomyEntryRequest request
    );

    @PutMapping("/{tagType}/{tagId}")
    ResponseEntity<TaxonomyEntryResponse> updateEntry(
            @PathVariable String tagType,
            @PathVariable UUID tagId,
            @Valid @RequestBody CreateTaxonomyEntryRequest request
    );

    @PatchMapping("/{tagType}/{tagId}/deactivate")
    ResponseEntity<TaxonomyEntryResponse> deactivateEntry(
            @PathVariable String tagType,
            @PathVariable UUID tagId
    );
}
