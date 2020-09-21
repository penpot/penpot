DROP TABLE scheduled_task;

CREATE TABLE scheduled_task (
  id text PRIMARY KEY,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  cron_expr text NOT NULL
);

CREATE TABLE scheduled_task_history (
  id uuid DEFAULT uuid_generate_v4(),
  task_id text NOT NULL REFERENCES scheduled_task(id),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  is_error boolean NOT NULL DEFAULT false,
  reason text NULL DEFAULT NULL,

  PRIMARY KEY (id, created_at)
);

CREATE INDEX scheduled_task_history__task_id__idx
    ON scheduled_task_history(task_id);
