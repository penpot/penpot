DROP TABLE session;

CREATE TABLE http_session (
  id text PRIMARY KEY,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  profile_id uuid REFERENCES profile(id) ON DELETE CASCADE,
  user_agent text NULL
);

CREATE INDEX http_session__profile_id__idx
    ON http_session(profile_id);
