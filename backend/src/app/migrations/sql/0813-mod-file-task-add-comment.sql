-- The message of a comment thread a task comes from: each message may be
-- a task, so a thread may hold several. A task has no message when it
-- comes from no thread or when its message was deleted; it then keeps its
-- thread.

ALTER TABLE file_task
  ADD COLUMN comment_id uuid NULL REFERENCES comment(id) ON DELETE SET NULL DEFERRABLE;

CREATE INDEX file_task__comment_id__idx
    ON file_task(comment_id) WHERE comment_id IS NOT NULL;

-- Until now a task came from a whole thread, with the title of its first
-- message.
--
-- The update has no `AND t.comment_id IS NULL` guard: it runs once, when
-- the column is new and empty. A squash that replays this step after
-- tasks hold messages must add the guard, or it rewrites their messages.
UPDATE file_task AS t
   SET comment_id = (SELECT c.id
                       FROM comment AS c
                      WHERE c.thread_id = t.thread_id
                      ORDER BY c.created_at ASC
                      LIMIT 1)
 WHERE t.thread_id IS NOT NULL;
