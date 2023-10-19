ALTER TABLE file_object_thumbnail
  ADD COLUMN tag text DEFAULT 'frame';

ALTER TABLE file_object_thumbnail DROP CONSTRAINT file_object_thumbnail_pkey;
ALTER TABLE file_object_thumbnail ADD PRIMARY KEY (file_id, tag, object_id);