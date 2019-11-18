-- Tables

CREATE TABLE IF NOT EXISTS icons_collections (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS icons (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL,
  content text NOT NULL,
  metadata bytea NOT NULL,

  collection_id uuid REFERENCES icons_collections(id)
                     ON DELETE SET NULL
                     DEFAULT NULL
);

-- Indexes

CREATE INDEX icon_colections_user_idx
    ON icons_collections (user_id);

CREATE INDEX icons_user_idx
    ON icons (user_id);

CREATE INDEX icons_collection_idx
    ON icons (collection_id);

-- Triggers

CREATE TRIGGER icons_collections_modified_at_tgr BEFORE UPDATE ON icons_collections
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER icons_modified_at_tgr BEFORE UPDATE ON icons
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

