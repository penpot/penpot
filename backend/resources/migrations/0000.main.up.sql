CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- OCC

CREATE OR REPLACE FUNCTION handle_occ()
  RETURNS TRIGGER AS $occ$
  BEGIN
    IF (NEW.version != OLD.version) THEN
      RAISE EXCEPTION 'Version missmatch: expected % given %',
            OLD.version, NEW.version
            USING ERRCODE='P0002';
    ELSE
      NEW.version := NEW.version + 1;
    END IF;
    RETURN NEW;
  END;
$occ$ LANGUAGE plpgsql;

-- Modified At

CREATE OR REPLACE FUNCTION update_modified_at()
  RETURNS TRIGGER AS $updt$
  BEGIN
    NEW.modified_at := clock_timestamp();
    RETURN NEW;
  END;
$updt$ LANGUAGE plpgsql;
