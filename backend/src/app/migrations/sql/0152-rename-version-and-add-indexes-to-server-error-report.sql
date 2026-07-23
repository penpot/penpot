-- Add source column (keep version column as-is for backward compatibility)
ALTER TABLE server_error_report
  ADD COLUMN source integer;

-- Trigger function to sync version -> source (backward compatibility with old code)
CREATE OR REPLACE FUNCTION server_error_report__sync_version_to_source()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.version IS NOT NULL AND NEW.source IS NULL THEN
    NEW.source := NEW.version;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger fires on INSERT or UPDATE OF version column
CREATE TRIGGER server_error_report__sync_version_to_source__tgr
  BEFORE INSERT OR UPDATE OF version ON server_error_report
  FOR EACH ROW
  EXECUTE FUNCTION server_error_report__sync_version_to_source();

-- Backfill existing rows
UPDATE server_error_report SET source = version WHERE source IS NULL;

-- Drop old version index
DROP INDEX IF EXISTS server_error_report__version__idx;

-- Create new source index
CREATE INDEX server_error_report__source__idx
  ON server_error_report (source);

-- Content-based indexes
CREATE INDEX server_error_report__content_kind__idx
  ON server_error_report (COALESCE(content->>'~:kind', content->>'~:origin'));

CREATE INDEX server_error_report__content_tenant__idx
  ON server_error_report ((content->>'~:tenant'));

CREATE INDEX server_error_report__content_version__idx
  ON server_error_report ((content->>'~:version'));

-- Index for pagination
CREATE INDEX server_error_report__created_at_id__idx
  ON server_error_report (created_at DESC, id DESC);
