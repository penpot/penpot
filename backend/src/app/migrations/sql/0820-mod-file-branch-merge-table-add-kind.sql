-- What an integration of a branch into main did with the branch: a
-- `merge` deleted it, an `apply` kept it open on top of the new main
-- (`files_branch.clj::apply-file-branch`). Every row written before this
-- column existed is a merge, so the default covers them; ADD COLUMN with
-- a constant default rewrites no row.

ALTER TABLE file_branch_merge
  ADD COLUMN kind text NOT NULL DEFAULT 'merge'
      CHECK (kind IN ('merge', 'apply'));
