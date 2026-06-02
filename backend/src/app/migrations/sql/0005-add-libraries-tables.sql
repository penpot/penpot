CREATE TABLE image_library (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE INDEX image_library__team_id__idx
    ON image_library(team_id);

CREATE TRIGGER image_library__modified_at__tgr
BEFORE UPDATE ON image_library
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE image (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  library_id uuid NOT NULL REFERENCES image_library(id) ON DELETE CASCADE,

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

CREATE INDEX image__library_id__idx
    ON image(library_id);

CREATE TRIGGER image__modified_at__tgr
BEFORE UPDATE ON image
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER image__on_delete__tgr
 AFTER DELETE ON image
   FOR EACH ROW EXECUTE PROCEDURE handle_delete();



CREATE TABLE icon_library (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE INDEX icon_colection__team_id__idx
    ON icon_library (team_id);

CREATE TRIGGER icon_library__modified_at__tgr
BEFORE UPDATE ON icon_library
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE icon (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  library_id uuid REFERENCES icon_library(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,


  name text NOT NULL,
  content text NOT NULL,
  metadata bytea NOT NULL
);

CREATE INDEX icon__library_id__idx
    ON icon(library_id);

CREATE TRIGGER icon__modified_at__tgr
BEFORE UPDATE ON icon
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE color_library (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE INDEX color_colection__team_id__idx
    ON color_library (team_id);

CREATE TRIGGER color_library__modified_at__tgr
BEFORE UPDATE ON color_library
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE color (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  library_id uuid REFERENCES color_library(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL,
  content text NOT NULL
);

CREATE INDEX color__library_id__idx
    ON color(library_id);

CREATE TRIGGER color__modified_at__tgr
BEFORE UPDATE ON color
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
