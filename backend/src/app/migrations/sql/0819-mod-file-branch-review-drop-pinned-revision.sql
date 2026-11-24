-- A branch review no longer pins a revision of its branch: reviewers see
-- the live branch, and a verdict is stale once the branch file's `revn`
-- passes the verdict's `verdict_revn`. The pinned snapshot of every open
-- review is released to the snapshot GC first, the way closing a review
-- released it, because nothing names it once the column goes. A closed
-- review released its snapshot already.

UPDATE file_change
   SET deleted_at = now(),
       updated_at = now()
 WHERE id IN (SELECT pinned_snapshot_id
                FROM file_branch_review
               WHERE status = 'open'
                 AND pinned_snapshot_id IS NOT NULL);

UPDATE file_data
   SET deleted_at = now()
 WHERE type = 'snapshot'
   AND id IN (SELECT pinned_snapshot_id
                FROM file_branch_review
               WHERE status = 'open'
                 AND pinned_snapshot_id IS NOT NULL);

-- Dropping `pinned_snapshot_id` drops its index
-- `file_branch_review__pinned_snapshot_id__idx` with it.
ALTER TABLE file_branch_review
  DROP COLUMN pinned_snapshot_id,
  DROP COLUMN pinned_revn,
  DROP COLUMN pinned_at;
