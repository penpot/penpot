-- The activity of a design review: one row per save of its branch while
-- the review is open, with who saved and what the save did to each
-- layer it touched.
--
-- The branch op log (`file_branch_change`) cannot answer that: it keeps
-- no author and an update from main squashes it into one row. This table
-- is written by the branch save alone (`files_update.clj`), so the work
-- an update brings in from main never counts as anybody's change.
--
-- `entries` is a blob-encoded vector, one map per touched layer
-- (`app.common.files.review-activity/entries`): its id, page, board,
-- name and type, the kind of change (`:added`, `:modified`,
-- `:deleted`) and, for a modified layer, the value before and after of
-- each property a person reads.

CREATE TABLE file_pull_request_activity (
  id               uuid PRIMARY KEY,

  pull_request_id  uuid NOT NULL REFERENCES file_pull_request(id) ON DELETE CASCADE DEFERRABLE,
  profile_id       uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,

  -- the branch file's revn the save produced
  revn             bigint NOT NULL,
  entries          bytea NOT NULL,

  created_at       timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX file_pull_request_activity__pull_request_id__idx
    ON file_pull_request_activity(pull_request_id, created_at);

CREATE INDEX file_pull_request_activity__profile_id__idx
    ON file_pull_request_activity(profile_id);

-- A branch save asks whether its branch is under review: find the open
-- pull request by its source (the branch file).
CREATE INDEX file_pull_request__source_file_id__open__idx
    ON file_pull_request(source_file_id)
 WHERE status = 'open' AND deleted_at IS NULL;
