-- The number of changes an op log row carries, so the depth gate can sum
-- the log without decoding it.
--
-- The gate that bounds an operation by `branching-max-oplog-changes` runs
-- ahead of every branch integration, and the derive in the same request
-- replays the rows anyway, so reading the count off the row keeps the gate
-- from decoding a log the derive decodes a moment later. The count is not
-- recoverable from the row's bytes: the change vector is one compressed
-- blob, and its length in bytes says nothing about the number of changes
-- beside a squash that emits tens of thousands for one page.
--
-- The migration that follows this one fills the column from the stored
-- blobs and then makes it NOT NULL, so a writer that omits the count fails
-- instead of recording an empty log. The live rows' sum is the log's
-- depth, the quantity `files_branch.clj::check-oplog-depth-limit!` limits.

ALTER TABLE file_branch_change
  ADD COLUMN IF NOT EXISTS changes_count integer;
