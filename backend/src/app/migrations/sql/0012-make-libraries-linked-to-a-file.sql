TRUNCATE TABLE color;
TRUNCATE TABLE color_library CASCADE;
TRUNCATE TABLE image;
TRUNCATE TABLE image_library CASCADE;
TRUNCATE TABLE icon;
TRUNCATE TABLE icon_library CASCADE;

ALTER TABLE color
  DROP COLUMN library_id,
  ADD COLUMN file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE;

CREATE INDEX color__file_id__idx
    ON color(file_id);

ALTER TABLE image
  DROP COLUMN library_id,
  ADD COLUMN file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE;

CREATE INDEX image__file_id__idx
    ON image(file_id);

