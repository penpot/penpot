CREATE INDEX storage_object__hash_backend_bucket__idx
    ON storage_object ((metadata->>'~:hash'), (metadata->>'~:bucket'), backend)
 WHERE deleted_at IS NULL;
