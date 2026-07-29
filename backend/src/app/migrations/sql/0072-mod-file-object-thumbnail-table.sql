TRUNCATE TABLE file_object_thumbnail;

ALTER TABLE file_object_thumbnail
  ALTER COLUMN object_id TYPE text;
