CREATE TABLE image_collections (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE INDEX image_collections__user_id__idx
    ON image_collections(user_id);

CREATE TABLE images (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  collection_id uuid NOT NULL REFERENCES image_collections(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL,

  path text NOT NULL,
  width int NOT NULL,
  height int NOT NULL,
  mtype text NOT NULL,

  thumb_path text NOT NULL,
  thumb_width int NOT NULL,
  thumb_height int NOT NULL,
  thumb_quality int NOT NULL,
  thumb_mtype text NOT NULL
);

CREATE INDEX images__user_id__idx
    ON images(user_id);

CREATE INDEX images__collection_id__idx
    ON images(collection_id);

CREATE TRIGGER image_collections__modified_at__tgr
BEFORE UPDATE ON image_collections
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER images__modified_at__tgr
BEFORE UPDATE ON images
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

