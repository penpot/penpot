CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Modified At

CREATE FUNCTION update_modified_at()
  RETURNS TRIGGER AS $updt$
  BEGIN
    NEW.modified_at := clock_timestamp();
    RETURN NEW;
  END;
$updt$ LANGUAGE plpgsql;
