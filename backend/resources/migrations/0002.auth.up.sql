CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  fullname text NOT NULL DEFAULT '',
  username text NOT NULL,
  email text NOT NULL,
  photo text NOT NULL,
  password text NOT NULL,
  metadata bytea NOT NULL
);

-- Insert a placeholder system user.
INSERT INTO users (id, fullname, username, email, photo, password, metadata)
VALUES ('00000000-0000-0000-0000-000000000000'::uuid,
        'System User',
        '00000000-0000-0000-0000-000000000000',
        'system@uxbox.io',
        '',
        '!',
        ''::bytea);

CREATE UNIQUE INDEX users_username_idx
  ON users WHERE deleted_at is null
  USING btree (username) ;

CREATE UNIQUE INDEX users_email_idx
  ON users WHERE deleted_at is null
  USING btree (email);

CREATE TRIGGER users_modified_at_tgr BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TABLE user_pswd_recovery (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  "user" uuid REFERENCES users(id) ON DELETE CASCADE,
  token text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  used_at timestamptz DEFAULT NULL
);

CREATE INDEX user_pswd_recovery_user_idx
    ON user_pswd_recovery USING btree ("user");

CREATE UNIQUE INDEX user_pswd_recovery_token_idx
    ON user_pswd_recovery USING btree (token);

