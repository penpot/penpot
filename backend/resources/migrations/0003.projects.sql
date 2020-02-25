CREATE TABLE project (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  is_default boolean NOT NULL DEFAULT false,

  name text NOT NULL
);

CREATE INDEX project__team_id__idx
    ON project(team_id);

CREATE TRIGGER project__modified_at__tgr
BEFORE UPDATE ON project
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE project_profile_rel (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  is_owner boolean DEFAULT false,
  is_admin boolean DEFAULT false,
  can_edit boolean DEFAULT false,

  PRIMARY KEY (profile_id, project_id)
);

COMMENT ON TABLE project_profile_rel
     IS 'Relation between projects and profiles (NM)';

CREATE INDEX project_profile_rel__profile_id__idx
    ON project_profile_rel(profile_id);

CREATE INDEX project_profile_rel__project_id__idx
    ON project_profile_rel(project_id);



CREATE TABLE file (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE TRIGGER file__modified_at__tgr
BEFORE UPDATE ON file
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();


CREATE TABLE file_profile_rel (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  is_owner boolean DEFAULT false,
  is_admin boolean DEFAULT false,
  can_edit boolean DEFAULT false,

  PRIMARY KEY (file_id, profile_id)
);

COMMENT ON TABLE file_profile_rel
     IS 'Relation between files and profiles (NM)';

CREATE INDEX file_profile_rel__profile_id__idx
    ON file_profile_rel(profile_id);

CREATE INDEX file_profile_rel__file_id__idx
    ON file_profile_rel(file_id);

CREATE TRIGGER file_profile_rel__modified_at__tgr
BEFORE UPDATE ON file
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE file_image (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,

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

CREATE INDEX file_image__file_id__idx
    ON file_image(file_id);

CREATE TRIGGER file_image__modified_at__tgr
BEFORE UPDATE ON file_image
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER file_image__on_delete__tgr
 AFTER DELETE ON file_image
   FOR EACH ROW EXECUTE PROCEDURE handle_delete();



CREATE TABLE page (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  version bigint NOT NULL DEFAULT 0,
  revn bigint NOT NULL DEFAULT 0,

  ordering smallint NOT NULL,

  name text NOT NULL,
  data bytea NOT NULL
);

CREATE INDEX page__file_id__idx
    ON page(file_id);

CREATE FUNCTION handle_page_update()
  RETURNS TRIGGER AS $pagechange$
  DECLARE
    current_dt timestamptz := clock_timestamp();
    proj_id uuid;
  BEGIN
    NEW.modified_at := current_dt;

    UPDATE file
       SET modified_at = current_dt
     WHERE id = OLD.file_id
 RETURNING project_id
      INTO STRICT proj_id;

    --- Update projects modified_at attribute when a
    --- page of that project is modified.
    UPDATE project
       SET modified_at = current_dt
     WHERE id = proj_id;

    RETURN NEW;
  END;
$pagechange$ LANGUAGE plpgsql;

CREATE TRIGGER page__on_update__tgr
BEFORE UPDATE ON page
   FOR EACH ROW EXECUTE PROCEDURE handle_page_update();



CREATE TABLE page_version (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  page_id uuid NOT NULL REFERENCES page(id) ON DELETE CASCADE,
  profile_id uuid NULL REFERENCES profile(id) ON DELETE SET NULL,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  version bigint NOT NULL DEFAULT 0,

  label text NOT NULL DEFAULT '',
  data bytea NOT NULL,

  changes bytea NULL DEFAULT NULL
);

CREATE INDEX page_version__profile_id__idx
    ON page_version(profile_id);

CREATE INDEX page_version__page_id__idx
    ON page_version(page_id);

CREATE TRIGGER page_version__modified_at__tgr
BEFORE UPDATE ON page_version
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE page_change (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  page_id uuid NOT NULL REFERENCES page(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  revn bigint NOT NULL DEFAULT 0,

  label text NOT NULL DEFAULT '',
  data bytea NOT NULL,

  changes bytea NULL DEFAULT NULL
);

CREATE INDEX page_change__page_id__idx
    ON page_change(page_id);

CREATE TRIGGER page_change__modified_at__tgr
BEFORE UPDATE ON page_change
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
