-- The durable op log of a branch: one row per branch save (the change
-- vector the editor produced, blob-encoded) plus the ops a
-- update-branch-from-main integration produced. The branch file's
-- :data is DERIVED by replaying these rows over the merge-base snapshot
-- (`file_branch.base_snapshot_id`), so the branch file itself stores no
-- data.
--
-- `file_change` rows remain the transient source for the lagged-changes
-- machinery; this table is what keeps the branch reconstructible after
-- those rows are GC'd (team deletion delay). Rows live until the branch
-- is deleted or the op log is squashed (update-branch-from-main).

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
