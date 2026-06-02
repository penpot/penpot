ALTER TABLE file_library_rel 
  ADD COLUMN synced_at timestamptz NOT NULL DEFAULT clock_timestamp();

