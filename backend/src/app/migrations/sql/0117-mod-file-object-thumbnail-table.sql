ALTER TABLE file_object_thumbnail
 DROP CONSTRAINT file_object_thumbnail_file_id_fkey,
  ADD FOREIGN KEY (file_id) REFERENCES file(id) DEFERRABLE,
 DROP CONSTRAINT file_object_thumbnail_media_id_fkey,
  ADD FOREIGN KEY (media_id) REFERENCES storage_object(id) DEFERRABLE;

--- Mark all related storage_object row as touched
-- UPDATE storage_object SET touched_at = now()
--  WHERE id IN (SELECT DISTINCT media_id
--                 FROM file_object_thumbnail
--                WHERE media_id IS NOT NULL)
--    AND touched_at IS NULL;
