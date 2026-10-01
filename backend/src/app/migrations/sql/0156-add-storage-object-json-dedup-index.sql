-- Dedup lookup index for the plain-JSON encoding of
-- storage_object.metadata ("hash" / "bucket").
--
-- The dedup lookup matches both metadata encodings while Transit rows
-- still exist (a UNION ALL of two indexable branches). The legacy "~:"
-- index (storage_object__hash_backend_bucket__idx, migration 0068) keeps
-- covering the Transit branch until Transit support is removed.

CREATE INDEX storage_object__hash_backend_bucket_json__idx
    ON storage_object ((metadata->>'hash'), (metadata->>'bucket'), backend)
 WHERE deleted_at IS NULL AND status = 'valid';
