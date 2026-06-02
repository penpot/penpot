ALTER TABLE file_thumbnail
  ADD COLUMN media_id uuid NULL REFERENCES storage_object(id) ON DELETE CASCADE DEFERRABLE;
