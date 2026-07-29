ALTER TABLE file_change
  ADD COLUMN features text[] DEFAULT NULL;

ALTER TABLE file_change
  ALTER COLUMN features SET STORAGE external;

ALTER TABLE file
  ALTER COLUMN features SET STORAGE external;
