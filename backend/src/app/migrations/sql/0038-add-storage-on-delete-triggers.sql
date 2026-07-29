CREATE FUNCTION on_delete_profile()
  RETURNS TRIGGER AS $func$
  BEGIN
    UPDATE storage_object
       SET deleted_at = now()
     WHERE id = OLD.photo_id;

    RETURN OLD;
  END;
$func$ LANGUAGE plpgsql;

CREATE FUNCTION on_delete_team()
  RETURNS TRIGGER AS $func$
  BEGIN
    UPDATE storage_object
       SET deleted_at = now()
     WHERE id = OLD.photo_id;

    RETURN OLD;
  END;
$func$ LANGUAGE plpgsql;

CREATE FUNCTION on_delete_file_media_object()
  RETURNS TRIGGER AS $func$
  BEGIN
    UPDATE storage_object
       SET deleted_at = now()
     WHERE id = OLD.media_id;

    IF OLD.thumbnail_id IS NOT NULL THEN
      UPDATE storage_object
         SET deleted_at = now()
       WHERE id = OLD.thumbnail_id;
    END IF;

    RETURN OLD;
  END;
$func$ LANGUAGE plpgsql;

CREATE TRIGGER profile__on_delete__tgr
 AFTER DELETE ON profile
   FOR EACH ROW EXECUTE PROCEDURE on_delete_profile();

CREATE TRIGGER team__on_delete__tgr
 AFTER DELETE ON team
   FOR EACH ROW EXECUTE PROCEDURE on_delete_team();

CREATE TRIGGER file_media_object__on_delete__tgr
 AFTER DELETE ON file_media_object
   FOR EACH ROW EXECUTE PROCEDURE on_delete_file_media_object();
