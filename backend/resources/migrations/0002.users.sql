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

CREATE TABLE IF NOT EXISTS user_storage (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  key text NOT NULL,
  val bytea NOT NULL,

  PRIMARY KEY (key, user_id)
);

CREATE TABLE user_tokens (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  used_at timestamptz DEFAULT NULL,

  PRIMARY KEY (token, user_id)
);

CREATE TABLE sessions (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  user_id uuid REFERENCES users(id) ON DELETE CASCADE,
  user_agent TEXT NULL
);

-- Insert a placeholder system user.

INSERT INTO users (id, fullname, username, email, photo, password, metadata)
VALUES ('00000000-0000-0000-0000-000000000000'::uuid,
        'System User',
        '00000000-0000-0000-0000-000000000000',
        'system@uxbox.io',
        '',
        '!',
        '{}');

CREATE UNIQUE INDEX users_username_idx
    ON users USING btree (username)
 WHERE deleted_at is null;

CREATE UNIQUE INDEX users_email_idx
    ON users USING btree (email)
 WHERE deleted_at is null;

CREATE TRIGGER users_modified_at_tgr BEFORE UPDATE ON users
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER user_storage_modified_at_tgr BEFORE UPDATE ON user_storage
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

