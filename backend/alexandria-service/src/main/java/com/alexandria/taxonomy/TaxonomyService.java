package com.alexandria.taxonomy;

import com.alexandria.taxonomy.entity.TaxonomyEntryEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for taxonomy lifecycle management.
 *
 * <p>Manages the controlled vocabulary of tag entries organized by type
 * (task_type, tool, language, framework). Only Catalog_Admins can create,
 * update, or deactivate entries; all authenticated users can read.</p>
 */
public interface TaxonomyService {

    /**
     * Returns all taxonomy entries for an organization grouped by tag type.
     *
     * @param orgId the organization ID
     * @return entries grouped by tag type (key = tagType, value = entries)
     */
    Map<String, List<TaxonomyEntryEntity>> getAllGrouped(UUID orgId);

    /**
     * Returns all taxonomy entries for a specific tag type within an organization.
     *
     * @param orgId   the organization ID
     * @param tagType the tag type (must be one of: task_type, tool, language, framework)
     * @return entries for the given type, ordered by value ascending
     * @throws IllegalArgumentException if tagType is not a valid type
     */
    List<TaxonomyEntryEntity> getByType(UUID orgId, String tagType);

    /**
     * Creates a new taxonomy entry.
     *
     * @param orgId       the organization ID
     * @param tagType     the tag type (must be one of: task_type, tool, language, framework)
     * @param value       the entry value (must be unique case-insensitive within type)
     * @param description optional description
     * @param actorUserId the user performing the action
     * @return the created entity
     * @throws IllegalArgumentException    if tagType is not valid
     * @throws TaxonomyConflictException   if a value already exists for this type (case-insensitive)
     */
    TaxonomyEntryEntity create(UUID orgId, String tagType, String value, String description, UUID actorUserId);

    /**
     * Updates an existing taxonomy entry's value and/or description.
     *
     * @param orgId       the organization ID
     * @param tagId       the taxonomy entry ID
     * @param value       the new value (must be unique case-insensitive within type, excluding self)
     * @param description the new description
     * @param actorUserId the user performing the action
     * @return the updated entity
     * @throws IllegalArgumentException          if tagType is not valid
     * @throws TaxonomyConflictException         if the new value conflicts with another entry
     * @throws jakarta.persistence.EntityNotFoundException if the entry is not found
     */
    TaxonomyEntryEntity update(UUID orgId, UUID tagId, String value, String description, UUID actorUserId);

    /**
     * Deactivates a taxonomy entry. Inactive entries remain on existing skills
     * but cannot be assigned to new skills.
     *
     * @param orgId       the organization ID
     * @param tagId       the taxonomy entry ID
     * @param actorUserId the user performing the action
     * @return the deactivated entity
     * @throws jakarta.persistence.EntityNotFoundException if the entry is not found
     */
    TaxonomyEntryEntity deactivate(UUID orgId, UUID tagId, UUID actorUserId);
}
