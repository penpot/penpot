ALTER TABLE storage_object
  ADD COLUMN touched_at timestamptz NULL;

CREATE INDEX storage_object__id_touched_at__idx
    ON storage_object (touched_at, id)
 WHERE touched_at IS NOT NULL;

CREATE OR REPLACE FUNCTION on_delete_file_media_object()
  RETURNS TRIGGER AS $func$
  BEGIN
    IF OLD.thumbnail_id IS NOT NULL THEN
      UPDATE storage_object
         SET touched_at = now()
       WHERE id in (OLD.thumbnail_id, OLD.media_id);
    ELSE
      UPDATE storage_object
         SET touched_at = now()
       WHERE id = OLD.media_id;
    END IF;
    RETURN OLD;
  END;
$func$ LANGUAGE plpgsql;

