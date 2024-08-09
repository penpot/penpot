ALTER TABLE file ADD COLUMN data_ref_id uuid NULL;

CREATE INDEX IF NOT EXISTS file__data_ref_id__idx
    ON file (data_ref_id);
