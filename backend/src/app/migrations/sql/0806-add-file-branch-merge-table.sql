-- The record of every merge of a branch into its source file (main): who
-- merged it, when, the comment they left and, when the merge accepted a
-- design review, that review. A merge deletes the branch, and the objects
-- GC later deletes its file, which takes the `file_branch` and
-- `file_pull_request` rows with it through their cascades. So this table
-- keeps its own copy of the names it shows and holds no foreign key to
-- either table: the record outlives both. It goes away with main.

CREATE TABLE file_branch_merge (
  id                 uuid PRIMARY KEY,

  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),

  -- main: the file the branch was merged into
  file_id            uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  -- the merged branch, by id and by the name it had when it was merged
  file_branch_id     uuid NOT NULL,
  branch_name        text NOT NULL,

  merged_by          uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  comment            text NULL,

  -- the design review the merge accepted, if any
  pull_request_id    uuid NULL,
  pull_request_title text NULL
);

CREATE INDEX file_branch_merge__file_id__created_at__idx
    ON file_branch_merge(file_id, created_at DESC);

CREATE INDEX file_branch_merge__merged_by__idx
    ON file_branch_merge(merged_by);
