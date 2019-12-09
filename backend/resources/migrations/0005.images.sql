-- Tables

CREATE TABLE IF NOT EXISTS image_collections (
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
  collection_id uuid REFERENCES image_collections(id)
                     ON DELETE SET NULL
                     DEFAULT NULL,
  name text NOT NULL,
  path text NOT NULL
);

-- Indexes

CREATE INDEX image_collections__user_id__idx ON image_collections (user_id);
CREATE INDEX images__collection_id__idx ON images (collection_id);
CREATE INDEX images__user_id__idx ON images (user_id);

-- Triggers

CREATE TRIGGER image_collections__modified_at__tgr
BEFORE UPDATE ON image_collections
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER images__modified_at__tgr
BEFORE UPDATE ON images
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

