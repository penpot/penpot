--- Delete previously token related tables

DROP TABLE password_recovery_token;

--- Create a new generic table for store tokens.

CREATE TABLE generic_token (
  token text PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  valid_until timestamptz NOT NULL,
  content bytea NOT NULL
);

COMMENT ON TABLE generic_token IS 'Table for generic tokens storage';
