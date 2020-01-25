-- Tables

CREATE TABLE icon_collections (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE TABLE icons (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL,
  content text NOT NULL,
  metadata bytea NOT NULL,

  collection_id uuid REFERENCES icon_collections(id)
                     ON DELETE SET NULL
                     DEFAULT NULL
);

-- Indexes

CREATE INDEX icon_colections__user_id__idx ON icon_collections (user_id);
CREATE INDEX icons__user_id__idx ON icons(user_id);
CREATE INDEX icons__collection_id__idx ON icons(collection_id);

-- Triggers

CREATE TRIGGER icon_collections__modified_at__tgr
BEFORE UPDATE ON icon_collections
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER icons__modified_at__tgr
BEFORE UPDATE ON icons
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
