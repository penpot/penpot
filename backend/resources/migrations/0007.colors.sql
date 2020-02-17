CREATE TABLE color_collection (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE INDEX color_colection__profile_id__idx
    ON color_collection (profile_id);

CREATE TRIGGER color_collection__modified_at__tgr
BEFORE UPDATE ON color_collection
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE color (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  collection_id uuid REFERENCES color_collection(id)
                     ON DELETE CASCADE,

  name text NOT NULL,
  content text NOT NULL
);

CREATE INDEX color__profile_id__idx
    ON color(profile_id);
CREATE INDEX color__collection_id__idx
    ON color(collection_id);

CREATE TRIGGER color__modified_at__tgr
BEFORE UPDATE ON color
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
