CREATE INDEX IF NOT EXISTS storage_object__metadata_upload_id__idx ON storage_object ((metadata->>'~:upload-id')) WHERE deleted_at IS NULL;
