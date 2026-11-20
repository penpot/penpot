-- The changes of a branch a person has marked as reviewed while comparing
-- it with main: one row per profile, branch, direction of the comparison
-- and change.
--
-- `change_key` names the change in the comparison
-- (`app.common.files.branch-merge/change-key`) and `fingerprint` is a
-- digest of what the change was when it was marked. The reader of the
-- comparison drops a mark whose fingerprint no longer matches, so a
-- layer that changes again asks to be reviewed again without anything
-- being invalidated when the branch is saved.
--
-- `direction` is `branch->main` for the changes of the branch and
-- `main->branch` for the ones main has: the same layer can change on
-- both sides, and each change is reviewed on its own.

CREATE TABLE file_branch_change_review (
  file_branch_id  uuid NOT NULL REFERENCES file_branch(id) ON DELETE CASCADE DEFERRABLE,
  profile_id      uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  direction       text NOT NULL,
  change_key      text NOT NULL,

  fingerprint     text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (file_branch_id, profile_id, direction, change_key)
);

CREATE INDEX file_branch_change_review__profile_id__idx
    ON file_branch_change_review(profile_id);
