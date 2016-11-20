-- A table that will store the whole transaction log of the database.
CREATE TABLE IF NOT EXISTS txlog (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  payload bytea NOT NULL
);

CREATE OR REPLACE FUNCTION handle_txlog_notify()
  RETURNS TRIGGER AS $notify$
  BEGIN
    PERFORM pg_notify('uxbox.transaction', (NEW.id)::text);
    RETURN NEW;
  END;
$notify$ LANGUAGE plpgsql;

CREATE TRIGGER txlog_notify_tgr AFTER INSERT ON txlog
   FOR EACH ROW EXECUTE PROCEDURE handle_txlog_notify();
