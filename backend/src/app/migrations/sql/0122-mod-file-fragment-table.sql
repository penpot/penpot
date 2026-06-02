ALTER TABLE file_data_fragment
  ADD COLUMN data_backend text NULL,
  ADD COLUMN data_ref_id uuid NULL;

CREATE INDEX IF NOT EXISTS file_data_fragment__data_ref_id__idx
    ON file_data_fragment (data_ref_id);
