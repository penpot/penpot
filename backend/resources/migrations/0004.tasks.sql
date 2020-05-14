CREATE TABLE task (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz NULL DEFAULT NULL,
  scheduled_at timestamptz NOT NULL,

  queue text NOT NULL,

  name text NOT NULL,
  props bytea NOT NULL,

  error text NULL DEFAULT NULL,

  retry_num smallint NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'new'
);

CREATE INDEX task__scheduled_at__queue__idx
    ON task (scheduled_at, queue);

CREATE TRIGGER task__modified_at__tgr
BEFORE UPDATE ON task
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TABLE scheduled_task (
  id text PRIMARY KEY,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  executed_at timestamptz NULL DEFAULT NULL,

  cron_expr text NOT NULL
);

CREATE TRIGGER scheduled_task__modified_at__tgr
BEFORE UPDATE ON scheduled_task
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
