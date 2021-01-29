DROP TABLE IF EXISTS file_media_thumbnail;

ALTER TABLE profile DROP COLUMN photo;
ALTER TABLE team DROP COLUMN photo;

ALTER TABLE file_media_object DROP COLUMN path;
ALTER TABLE file_media_object ALTER COLUMN media_id SET NOT NULL;

ALTER TRIGGER media_object__insert__tgr
   ON file_media_object RENAME TO file_media_object__on_insert__tgr;
