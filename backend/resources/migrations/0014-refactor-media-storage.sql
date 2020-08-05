ALTER TABLE image
  RENAME TO media_object;

ALTER TABLE media_object
  ADD COLUMN is_local boolean NOT NULL DEFAULT false;

INSERT INTO media_object
  (id, file_id, created_at, modified_at, deleted_at, name, path,
   width, height, mtype, thumb_path, thumb_width, thumb_height,
   thumb_quality, thumb_mtype, is_local)
 (SELECT id, file_id, created_at, modified_at, deleted_at, name, path,
         width, height, mtype, thumb_path, thumb_width, thumb_height,
         thumb_quality, thumb_mtype, true
    FROM file_image);

CREATE TABLE media_thumbnail (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  media_object_id uuid NOT NULL REFERENCES media_object(id) ON DELETE CASCADE,
  mtype text NOT NULL,
  path text NOT NULL,
  width int NOT NULL,
  height int NOT NULL,
  quality int NOT NULL
);

CREATE INDEX media_thumbnail__media_object_id__idx
    ON media_thumbnail(media_object_id);

INSERT INTO media_thumbnail
 (media_object_id, mtype, path, width, height, quality)
 (SELECT id, thumb_mtype, thumb_path, thumb_width, thumb_height, thumb_quality
    FROM media_object);

ALTER TABLE media_object
 DROP COLUMN thumb_mtype,
 DROP COLUMN thumb_path,
 DROP COLUMN thumb_width,
 DROP COLUMN thumb_height,
 DROP COLUMN thumb_quality;

DROP TABLE color_library;
DROP TABLE icon;
DROP TABLE icon_library;
DROP TABLE image_library;
DROP TABLE file_image;

