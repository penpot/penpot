CREATE TABLE profile (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz NULL,

  fullname text NOT NULL DEFAULT '',
  email text NOT NULL,
  photo text NOT NULL,
  password text NOT NULL,

  lang text NULL,
  is_demo boolean NOT NULL DEFAULT false
);

CREATE UNIQUE INDEX profile__email__idx
    ON profile (email)
 WHERE deleted_at IS null;

CREATE INDEX profile__is_demo
    ON profile (is_demo)
 WHERE deleted_at IS null
   AND is_demo IS true;

INSERT INTO profile (id, fullname, email, photo, password)
VALUES ('00000000-0000-0000-0000-000000000000'::uuid,
        'System Profile',
        'system@uxbox.io',
        '',
        '!');



CREATE TABLE profile_email (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  verified_at timestamptz NULL DEFAULT NULL,

  email text NOT NULL,

  is_main boolean NOT NULL DEFAULT false,
  is_verified boolean NOT NULL DEFAULT false
);

CREATE INDEX profile_email__profile_id__idx
    ON profile_email (profile_id);

CREATE UNIQUE INDEX profile_email__email__idx
    ON profile_email (email);



CREATE TABLE team (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz NULL,

  name text NOT NULL,
  photo text NOT NULL,

  is_default boolean NOT NULL DEFAULT false
);

CREATE TRIGGER team__modified_at__tgr
BEFORE UPDATE ON team
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

INSERT INTO team (id, name, photo, is_default)
VALUES ('00000000-0000-0000-0000-000000000000'::uuid,
        'System Team',
        '',
        true);



CREATE TABLE team_profile_rel (
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE RESTRICT,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  is_admin boolean DEFAULT false,
  is_owner boolean DEFAULT false,
  can_edit boolean DEFAULT false,

  PRIMARY KEY (team_id, profile_id)
);

COMMENT ON TABLE team_profile_rel
     IS 'Relation between teams and profiles (NM)';

CREATE TRIGGER team_profile_rel__modified_at__tgr
BEFORE UPDATE ON team_profile_rel
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE profile_attr (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  key text NOT NULL,
  val bytea NOT NULL,

  PRIMARY KEY (key, profile_id)
);

CREATE INDEX profile_attr__profile_id__idx
    ON profile_attr(profile_id);

CREATE TRIGGER profile_attr__modified_at__tgr
BEFORE UPDATE ON profile_attr
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();



CREATE TABLE password_recovery_token (
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  token text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  used_at timestamptz NULL,

  PRIMARY KEY (profile_id, token)
);



CREATE TABLE session (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  profile_id uuid REFERENCES profile(id) ON DELETE CASCADE,
  user_agent text NULL
);

CREATE INDEX session__profile_id__idx
    ON session(profile_id);
