ALTER TABLE file_object_thumbnail
  ADD COLUMN media_id uuid NULL REFERENCES storage_object(id) ON DELETE CASCADE DEFERRABLE;
