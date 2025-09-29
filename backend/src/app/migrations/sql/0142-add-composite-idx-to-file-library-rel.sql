-- Add composite index for better query performance on file_library_rel
-- This improves the recursive CTE queries used for library resolution

CREATE INDEX CONCURRENTLY IF NOT EXISTS file_library_rel__file_library__idx
    ON file_library_rel (file_id, library_file_id)
    WHERE deleted_at IS NULL;

-- Add index for reverse lookups (which files use this library)
CREATE INDEX CONCURRENTLY IF NOT EXISTS file_library_rel__library_file_reverse__idx
    ON file_library_rel (library_file_id, file_id)
    WHERE deleted_at IS NULL;
