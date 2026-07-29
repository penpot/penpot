ALTER TABLE file_data_fragment
  ADD COLUMN deleted_at timestamptz NULL;

--- Add index for deleted_at column, we include all related columns
--- because we expect the index to be small and expect use index-only
--- scans.
CREATE INDEX IF NOT EXISTS file_data_fragment__deleted_at__idx
    ON file_data_fragment (deleted_at, file_id, id)
 WHERE deleted_at IS NOT NULL;
