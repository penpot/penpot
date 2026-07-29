ALTER TABLE file
  ADD COLUMN has_media_trimmed boolean DEFAULT false;

CREATE INDEX file__modified_at__has_media_trimed__idx
    ON file(modified_at)
 WHERE has_media_trimmed IS false;

CREATE FUNCTION on_media_object_insert()
  RETURNS TRIGGER AS $$
  BEGIN
    UPDATE file
       SET has_media_trimmed = false,
           modified_at = now()
     WHERE id = NEW.file_id;
    RETURN NEW;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER media_object__insert__tgr
AFTER INSERT ON media_object
   FOR EACH ROW EXECUTE PROCEDURE on_media_object_insert();

CREATE TRIGGER media_thumbnail__on_delete__tgr
 AFTER DELETE ON media_thumbnail
   FOR EACH ROW EXECUTE PROCEDURE handle_delete();
