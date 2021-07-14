CREATE INDEX IF NOT EXISTS file__modified_at__with__data__idx
    ON file (modified_at, id)
 WHERE data IS NOT NULL;

ALTER TABLE file
  ADD COLUMN data_backend text NULL,
ALTER COLUMN data_backend SET STORAGE EXTERNAL;

DROP TRIGGER file_on_update_tgr ON file;
DROP FUNCTION handle_file_update ();
