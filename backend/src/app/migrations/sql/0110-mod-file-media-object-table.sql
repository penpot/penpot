--- Fix legacy naming
ALTER INDEX media_object_pkey RENAME TO file_media_object_pkey;
ALTER INDEX media_object__file_id__idx RENAME TO file_media_object__file_id__idx;

--- Create index for the deleted_at column
CREATE INDEX IF NOT EXISTS file_media_object__deleted_at__idx
    ON file_media_object (deleted_at, id, media_id)
 WHERE deleted_at IS NOT NULL;

--- Drop now unnecesary trigger because this will be handled by the
--- application code
DROP TRIGGER file_media_object__on_delete__tgr ON file_media_object;
DROP FUNCTION on_delete_file_media_object ( ) CASCADE;
DROP TRIGGER file_media_object__on_insert__tgr ON file_media_object;
DROP FUNCTION on_media_object_insert () CASCADE;

--- Remove CASCADE from file FOREIGN KEY
ALTER TABLE file_media_object
 DROP CONSTRAINT file_media_object_file_id_fkey,
  ADD FOREIGN KEY (file_id) REFERENCES file(id) DEFERRABLE;

--- Add deletion protection
CREATE OR REPLACE TRIGGER deletion_protection__tgr
BEFORE DELETE ON file_media_object FOR EACH STATEMENT
  WHEN ((current_setting('rules.deletion_protection', true) IN ('on', '')) OR
        (current_setting('rules.deletion_protection', true) IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();
