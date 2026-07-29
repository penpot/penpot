CREATE TABLE webhook (
  id uuid PRIMARY KEY,
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  uri text NOT NULL,
  mtype text NOT NULL,

  error_code text NULL,
  error_count smallint DEFAULT 0,

  is_active boolean DEFAULT true,
  secret_key text NULL
);

ALTER TABLE webhook
  ALTER COLUMN uri SET STORAGE external,
  ALTER COLUMN mtype SET STORAGE external,
  ALTER COLUMN error_code SET STORAGE external,
  ALTER COLUMN secret_key SET STORAGE external;


CREATE INDEX webhook__team_id__idx ON webhook (team_id);
