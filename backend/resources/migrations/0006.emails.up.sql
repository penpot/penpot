CREATE TYPE email_status AS ENUM ('pending', 'ok', 'failed');

CREATE TABLE IF NOT EXISTS email_queue (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  data bytea NOT NULL,

  priority smallint NOT NULL DEFAULT 10
                    CHECK (priority BETWEEN 0 and 10),

  status email_status NOT NULL DEFAULT 'pending',
  retries integer NOT NULL DEFAULT -1
);

-- Triggers

CREATE TRIGGER email_queue_modified_at_tgr BEFORE UPDATE ON email_queue
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

-- Indexes

CREATE INDEX email_status_idx
    ON email_queue (status);
