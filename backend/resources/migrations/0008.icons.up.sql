-- Tables

CREATE TABLE IF NOT EXISTS icons_collections (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  "user" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,
  version bigint NOT NULL DEFAULT 0,

  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS icons (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  "user" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,
  version bigint NOT NULL DEFAULT 0,

  name text NOT NULL,
  content text NOT NULL,
  metadata bytea NOT NULL,
  collection uuid REFERENCES icons_collections(id)
                  ON DELETE SET NULL
                  DEFAULT NULL
);

-- Indexes

CREATE INDEX icon_colections_user_idx
    ON icons_collections ("user");

CREATE INDEX icons_user_idx
    ON icons ("user");

CREATE INDEX icons_collection_idx
    ON icons (collection);

-- Triggers

CREATE TRIGGER icons_collections_occ_tgr BEFORE UPDATE ON icons_collections
  FOR EACH ROW EXECUTE PROCEDURE handle_occ();

CREATE TRIGGER icons_collections_modified_at_tgr BEFORE UPDATE ON icons_collections
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER icons_occ_tgr BEFORE UPDATE ON icons
  FOR EACH ROW EXECUTE PROCEDURE handle_occ();

CREATE TRIGGER icons_modified_at_tgr BEFORE UPDATE ON icons
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

