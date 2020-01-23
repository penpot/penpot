CREATE TABLE IF NOT EXISTS tasks (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  queue text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz NULL DEFAULT NULL,
  scheduled_at timestamptz NOT NULL,

  name text NOT NULL,
  props bytea NOT NULL,

  error_text text NULL DEFAULT NULL,

  retry_num smallint NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'new'
);

CREATE INDEX tasks__scheduled_at__idx
    ON tasks (scheduled_at, queue);
