DROP TABLE IF EXISTS access_token;
CREATE TABLE access_token (
  id uuid NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  name text NOT NULL,
  token text NOT NULL,
  perms text[] NULL
);

ALTER TABLE access_token
  ALTER COLUMN name SET STORAGE external,
  ALTER COLUMN token SET STORAGE external,
  ALTER COLUMN perms SET STORAGE external;

CREATE INDEX access_token__profile_id__idx ON access_token(profile_id);
