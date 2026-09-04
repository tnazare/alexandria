-- V7__rebuild_search_vectors.sql
-- One-time bulk rebuild of search_vector for all existing skills to include
-- tag values (weight C) and current-version canonical instructions (weight D),
-- which were missing from the original trigger (name A, description B only).
--
-- Idempotent: running this migration multiple times produces the same result.
-- The DB trigger (trg_skills_search_vector) continues to handle A+B on
-- name/description writes; this migration adds C+D for historical data.

UPDATE skills s
SET search_vector =
    setweight(to_tsvector('english', COALESCE(s.name, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(s.description, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE((
        SELECT string_agg(te.value, ' ' ORDER BY te.value)
        FROM skill_tags st
        JOIN taxonomy_entries te ON te.id = st.taxonomy_entry_id
        WHERE st.skill_id = s.id AND te.active = true
    ), '')), 'C') ||
    setweight(to_tsvector('english', COALESCE((
        SELECT sv.canonical_instructions
        FROM skill_versions sv
        WHERE sv.id = s.current_version_id
    ), '')), 'D');
