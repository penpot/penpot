CREATE TYPE task_status
  AS ENUM ('pending', 'canceled', 'completed', 'failed');

CREATE TABLE task (
  id           uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz DEFAULT NULL,
  queue        text NOT NULL DEFAULT '',
  status       task_status NOT NULL DEFAULT 'pending',
  error        text NOT NULL DEFAULT ''
) WITH (OIDS=FALSE);
