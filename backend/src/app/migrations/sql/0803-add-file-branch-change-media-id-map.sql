-- A branch's media pairing, recorded by the writes that establish it.
--
-- Every `file_branch_change` row carries the media id map the save or
-- the update from main that wrote it applied (`media_id_map`): an
-- `update-media-references!` fix-up on a save copies a foreign media row
-- into the branch under a fresh id, and an update from main re-points
-- main's media rows at the branch's copies. The stored direction is the
-- one the write applied ({old-id -> new-id}, the branch's own id is the
-- new one), because that is what both writers hold; the comparison
-- readers (`files_branch.clj::branch-id-map` and the base normalization)
-- invert it to rebuild {branch-media-id -> main-media-id}, and
-- `files_branch.clj::media-pairs` stays as the fallback for rows written
-- before the column existed.

ALTER TABLE file_branch_change
  ADD COLUMN IF NOT EXISTS media_id_map jsonb;
