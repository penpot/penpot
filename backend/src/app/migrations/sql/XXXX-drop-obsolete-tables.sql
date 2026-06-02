--- This is a second migration but it should be applied when manual
--- migration intervention is already executed.

ALTER TABLE file_media_object ALTER COLUMN media_id SET NOT NULL;
DROP TABLE file_media_thumbnail;

ALTER TABLE team DROP COLUMN photo;
ALTER TABLE profile DROP COLUMN photo;
ALTER TABLE file_media_object DROP COLUMN path;
