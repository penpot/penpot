CREATE TABLE presence (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  session_id uuid NOT NULL,

  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (file_id, session_id, profile_id)
);
