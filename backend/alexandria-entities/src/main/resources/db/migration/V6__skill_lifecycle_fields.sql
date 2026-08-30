-- V6__skill_lifecycle_fields.sql
-- Adds fields supporting governance lifecycle transitions (deprecate / block / archive / restore).
-- Both columns are nullable; existing rows are in active states with no prior status recorded.
-- The lifecycle_status CHECK constraint from V4 already permits all seven states, so no
-- constraint change is required here.

ALTER TABLE skills ADD COLUMN deprecation_reason TEXT;
ALTER TABLE skills ADD COLUMN prior_status TEXT;
