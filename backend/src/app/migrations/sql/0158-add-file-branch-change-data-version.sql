-- The data version each op-log row was written at: the name of the last
-- file data migration (`app.common.files.migrations/available-migrations`)
-- of the backend that applied the row's changes. A data version is a
-- prefix of that list, so its last name identifies it. The branch derive
-- (`app.binfile.common/branch-file-data`) replays the log in segments: it
-- migrates the document to a row's version before it applies the row, the
-- point where an ordinary file migrates across an upgrade.
--
-- The merge base needs no column of its own: its snapshot row
-- (`file_change.migrations`) records the migrations its data holds.
--
-- NULL marks a row written before this column existed. The derive replays
-- such a row at the version the document holds, which is the base's
-- version because those rows precede every stamped row of their log. That
-- is how the derive replayed every row before this column existed.

ALTER TABLE file_branch_change
  ADD COLUMN IF NOT EXISTS data_version text NULL;
