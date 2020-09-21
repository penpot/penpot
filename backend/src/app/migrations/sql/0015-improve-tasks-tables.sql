DROP TABLE task;

CREATE TABLE task (
  id uuid DEFAULT uuid_generate_v4(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz NULL DEFAULT NULL,
  scheduled_at timestamptz NOT NULL,
  priority smallint DEFAULT 100,

  queue text NOT NULL,

  name text NOT NULL,
  props jsonb NOT NULL,

  error text NULL DEFAULT NULL,
  retry_num smallint NOT NULL DEFAULT 0,
  max_retries smallint NOT NULL DEFAULT 3,
  status text NOT NULL DEFAULT 'new',

  PRIMARY KEY (id, status)
) PARTITION BY list(status);

CREATE TABLE task_completed partition OF task FOR VALUES IN ('completed', 'failed');
CREATE TABLE task_default partition OF task default;

CREATE INDEX task__scheduled_at__queue__idx
    ON task (scheduled_at, queue)
 WHERE status = 'new' or status = 'retry';
