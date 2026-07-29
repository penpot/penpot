CREATE TABLE upload_session (
  id         uuid PRIMARY KEY,

  created_at timestamptz NOT NULL DEFAULT now(),

  profile_id    uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  total_chunks  integer NOT NULL
);

CREATE INDEX upload_session__profile_id__idx
    ON upload_session(profile_id);

CREATE INDEX upload_session__created_at__idx
    ON upload_session(created_at);
