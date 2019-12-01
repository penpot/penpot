-- Tables

CREATE TABLE IF NOT EXISTS images_collections (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS images (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  width int NOT NULL,
  height int NOT NULL,
  mimetype text NOT NULL,
  collection_id uuid REFERENCES images_collections(id)
                     ON DELETE SET NULL
                     DEFAULT NULL,
  name text NOT NULL,
  path text NOT NULL
);

-- Indexes

CREATE INDEX images_collections_user_idx
    ON images_collections (user_id);

CREATE INDEX images_collection_idx
    ON images (collection_id);

CREATE INDEX images_user_idx
    ON images (user_id);

-- Triggers

CREATE TRIGGER images_collections_modified_at_tgr BEFORE UPDATE ON images_collections
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER images_modified_at_tgr BEFORE UPDATE ON images
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

