CREATE TABLE IF NOT EXISTS file_pull_request (
  id                 uuid PRIMARY KEY,

  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),

  file_branch_id     uuid NOT NULL REFERENCES file_branch(id) ON DELETE CASCADE DEFERRABLE,

  -- denormalized from file_branch: source is the BRANCH file, target is main
  source_file_id     uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  target_file_id     uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  title              text NOT NULL,
  description        text NULL,

  created_by         uuid NULL REFERENCES profile(id) ON DELETE SET NULL,

  status             text NOT NULL DEFAULT 'open'
                       CHECK (status IN ('open', 'merged', 'closed')),

  -- pinned snapshot of the BRANCH file that reviewers see
  review_snapshot_id uuid NULL,
  -- the branch file's revn when the review snapshot was (re)positioned;
  -- branch_revn > review_revn is the cheap "outdated" gate
  review_revn        bigint NOT NULL DEFAULT 0,
  review_updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),

  closed_at          timestamptz NULL,
  closed_by          uuid NULL REFERENCES profile(id) ON DELETE SET NULL,
  deleted_at         timestamptz NULL,

  CHECK (source_file_id != target_file_id)
);

-- at most one live pull request per branch
CREATE UNIQUE INDEX IF NOT EXISTS file_pull_request__file_branch_id__open__idx
    ON file_pull_request(file_branch_id)
 WHERE status = 'open' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS file_pull_request__target_file_id__idx
    ON file_pull_request(target_file_id) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS file_pull_request__review_snapshot_id__idx
    ON file_pull_request(review_snapshot_id) WHERE review_snapshot_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS file_pull_request_review (
  pull_request_id    uuid NOT NULL REFERENCES file_pull_request(id) ON DELETE CASCADE DEFERRABLE,
  profile_id         uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,

  state              text NOT NULL DEFAULT 'pending'
                       CHECK (state IN ('pending', 'approved', 'changes-requested')),

  -- review_revn of the pull request when the verdict was submitted; a
  -- verdict is stale when it no longer matches the pr's review_revn
  reviewed_revn      bigint NULL,
  comment            text NULL,

  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (pull_request_id, profile_id)
);

CREATE INDEX IF NOT EXISTS file_pull_request_review__profile_id__idx
    ON file_pull_request_review(profile_id);
