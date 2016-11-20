-- Tables

CREATE TABLE IF NOT EXISTS images_collections (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  "user" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,
  version bigint NOT NULL DEFAULT 0,

  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS images (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  "user" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  version bigint NOT NULL DEFAULT 0,

  width int NOT NULL,
  height int NOT NULL,
  mimetype text NOT NULL,
  collection uuid REFERENCES images_collections(id)
                  ON DELETE SET NULL
                  DEFAULT NULL,
  name text NOT NULL,
  path text NOT NULL
);

-- Indexes

CREATE INDEX images_collections_user_idx
    ON images_collections ("user");

CREATE INDEX images_collection_idx
    ON images (collection);

CREATE INDEX images_user_idx
    ON images ("user");

-- Triggers

CREATE TRIGGER images_collections_occ_tgr BEFORE UPDATE ON images_collections
   FOR EACH ROW EXECUTE PROCEDURE handle_occ();

CREATE TRIGGER images_collections_modified_at_tgr BEFORE UPDATE ON images_collections
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER images_occ_tgr BEFORE UPDATE ON images
  FOR EACH ROW EXECUTE PROCEDURE handle_occ();

CREATE TRIGGER images_modified_at_tgr BEFORE UPDATE ON images
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

