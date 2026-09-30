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

CREATE TABLE IF NOT EXISTS file_branch_change (
  id          uuid PRIMARY KEY,

  branch_id   uuid NOT NULL REFERENCES file_branch(id) ON DELETE CASCADE DEFERRABLE,
  file_id     uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  -- the BRANCH file's revn the save produced; replay order is by revn
  revn        bigint NOT NULL,

  changes     bytea NOT NULL,

  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at  timestamptz NULL,

  UNIQUE (file_id, revn)
);

CREATE INDEX IF NOT EXISTS file_branch_change__branch_id__idx
    ON file_branch_change(branch_id) WHERE deleted_at IS NULL;
