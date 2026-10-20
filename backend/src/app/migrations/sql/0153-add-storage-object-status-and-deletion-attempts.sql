--- Add a status column and a deletion attempts counter to storage_object.

--- The status column tracks the write-ahead lifecycle of newly created
--- objects. A row is inserted as 'pending' before its blob is written to
--- the underlying storage subsystem and promoted to 'valid' once the
--- write succeeds. Rows in 'pending' state are excluded from the normal
--- lifecycle (deduplication, gc, reads) until they become valid; a
--- periodic task (:storage-pending-gc) reclaims pending rows that were
--- never promoted (e.g. after a crash).

ALTER TABLE storage_object
  ADD COLUMN status text NOT NULL DEFAULT 'valid'
  CHECK (status IN ('valid', 'pending'));

CREATE INDEX storage_object__status_created_at__idx
    ON storage_object (status, created_at)
 WHERE status = 'pending';

--- The deletion_attempts counter tracks how many times the gc_deleted
--- task has attempted to physically delete the blob. After max attempts
--- the row is removed and the blob is left as an orphan.

ALTER TABLE storage_object
  ADD COLUMN deletion_attempts bigint NOT NULL DEFAULT 0;
