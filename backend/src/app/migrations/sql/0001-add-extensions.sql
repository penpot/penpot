CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE FUNCTION update_modified_at()
  RETURNS TRIGGER AS $updt$
  BEGIN
    NEW.modified_at := clock_timestamp();
    RETURN NEW;
  END;
$updt$ LANGUAGE plpgsql;

CREATE TABLE pending_to_delete (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  type text NOT NULL,
  data jsonb NOT NULL
);

CREATE FUNCTION handle_delete()
  RETURNS TRIGGER AS $pagechange$
  BEGIN
    INSERT INTO pending_to_delete (type, data)
    VALUES (TG_TABLE_NAME, row_to_json(OLD));
    RETURN OLD;
  END;
$pagechange$ LANGUAGE plpgsql;
