ALTER TABLE file_change
  ADD COLUMN created_by text NOT NULL DEFAULT 'system';
