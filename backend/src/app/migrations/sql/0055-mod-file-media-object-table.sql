ALTER TABLE file_media_object
 DROP CONSTRAINT file_media_object_thumbnail_id_fkey,
  ADD CONSTRAINT file_media_object_thumbnail_id_fkey
      FOREIGN KEY (thumbnail_id) REFERENCES storage_object (id) ON DELETE SET NULL;
