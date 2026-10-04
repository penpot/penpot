-- The durable op log of a branch: one row per branch save (the change
-- vector the editor produced, blob-encoded). An update-branch-from-main
-- replaces the whole log with one row holding the branch's squashed net
-- changes against the repositioned merge base (no row when that net is
-- empty); the integration vector itself goes to `file_change`. The
-- branch file's :data is DERIVED by replaying these rows over the
-- merge-base snapshot (`file_branch.base_snapshot_id`), so the branch
-- file itself stores no data.
--
-- `file_change` rows remain the transient source for the lagged-changes
-- machinery; this table is what keeps the branch reconstructible after
-- those rows are GC'd (each is GC-eligible one hour after its insert).
-- Rows live until the op log is squashed (update-branch-from-main) or
-- dropped (materialize-file-branch), or until the objects GC hard-deletes
-- the branch file row and the foreign keys cascade; a logical branch
-- deletion leaves them in place.
--
-- `data_version` is the data version each row was written at: the name
-- of the last file data migration
-- (`app.common.files.migrations/available-migrations`) of the backend
-- that applied the row's changes. A data version is a prefix of that
-- list, so its last name identifies it. The branch derive
-- (`app.binfile.common/branch-file-data`) replays the log in segments:
-- it migrates the document to a row's version before it applies the
-- row, the point where an ordinary file migrates across an upgrade. The
-- merge base needs no column of its own: its snapshot row
-- (`file_change.migrations`) records the migrations its data holds.

CREATE TABLE file_branch_change (
  id          uuid PRIMARY KEY,

  branch_id   uuid NOT NULL REFERENCES file_branch(id) ON DELETE CASCADE DEFERRABLE,
  file_id     uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  -- the BRANCH file's revn the save produced; replay order is by revn
  revn        bigint NOT NULL,

  changes     bytea NOT NULL,

  -- the data version the changes were written at
  data_version text NOT NULL,

  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at  timestamptz NULL,

  UNIQUE (file_id, revn)
);

CREATE INDEX file_branch_change__branch_id__idx
    ON file_branch_change(branch_id) WHERE deleted_at IS NULL;
