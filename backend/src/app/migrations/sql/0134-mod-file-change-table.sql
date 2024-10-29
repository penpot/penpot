ALTER TABLE file_change
  ADD COLUMN updated_at timestamptz DEFAULT now(),
  ADD COLUMN deleted_at timestamptz DEFAULT NULL,
ALTER COLUMN created_at SET DEFAULT now();

DROP INDEX file_change__created_at__idx;
DROP INDEX file_change__created_at__label__idx;
DROP INDEX file_change__label__idx;

CREATE INDEX file_change__deleted_at__idx
    ON file_change (deleted_at, id)
 WHERE deleted_at IS NOT NULL;

CREATE INDEX file_change__system_snapshots__idx
    ON file_change (file_id, created_at)
 WHERE data IS NOT NULL
   AND created_by = 'system'
   AND deleted_at IS NULL;
