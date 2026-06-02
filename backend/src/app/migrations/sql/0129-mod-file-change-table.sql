ALTER TABLE file_change
  ADD COLUMN data_backend text NULL,
  ADD COLUMN data_ref_id uuid NULL;

CREATE INDEX IF NOT EXISTS file_change__data_ref_id__idx
    ON file_change (data_ref_id);
