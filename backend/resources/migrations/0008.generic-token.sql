CREATE TABLE generic_token (
  token text PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  valid_until timestamptz NOT NULL,
  content bytea NOT NULL
);
