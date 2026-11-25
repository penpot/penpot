ALTER TABLE file_change ADD COLUMN retained boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN file_change.retained IS
  'Whether this revision follows the deletion and restore lifecycle of its file.';
