-- Profile
ALTER TABLE profile ADD COLUMN photo_id uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL;
CREATE INDEX profile__photo_id__idx ON profile(photo_id);

-- Team
ALTER TABLE team ADD COLUMN photo_id uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL;
CREATE INDEX team__photo_id__idx ON team(photo_id);

-- Media Objects -> File Media Objects
ALTER TABLE media_object RENAME TO file_media_object;
ALTER TABLE media_thumbnail RENAME TO file_media_thumbnail;

ALTER TABLE file_media_object
  ADD COLUMN media_id uuid NULL REFERENCES storage_object(id) ON DELETE CASCADE,
  ADD COLUMN thumbnail_id uuid NULL REFERENCES storage_object(id) ON DELETE CASCADE;

CREATE INDEX file_media_object__image_id__idx ON file_media_object(media_id);
CREATE INDEX file_media_object__thumbnail_id__idx ON file_media_object(thumbnail_id);

ALTER TABLE file_media_object ALTER COLUMN path DROP NOT NULL;
ALTER TABLE profile ALTER COLUMN photo DROP NOT NULL;
ALTER TABLE team ALTER COLUMN photo DROP NOT NULL;
