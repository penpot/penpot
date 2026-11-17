-- Every task lives in a message of a comment thread, so its thread can go
-- on: a task created on the canvas gets a thread whose first message is
-- its title, as if it had been a comment turned into a task.
--
-- Until now such a task had no thread. Give one to each of them: the
-- thread points at the layer of the task (at its spot of the canvas when
-- it has none), belongs to its design review and takes the next number of
-- its file. A task whose author is gone keeps no thread: a thread needs an
-- owner. The spot of a task on a layer was optional; the thread falls back
-- to the origin of the canvas, and its mark follows the layer anyway.

CREATE TEMPORARY TABLE task_thread ON COMMIT DROP AS
SELECT t.id AS task_id,
       gen_random_uuid() AS thread_id,
       gen_random_uuid() AS comment_id,
       t.file_id,
       t.created_by,
       t.page_id,
       t.frame_id,
       t.shape_id,
       t.pull_request_id,
       t.title,
       t.created_at,
       coalesce(t.position, point(0, 0)) AS position,
       f.comment_thread_seqn
         + row_number() OVER (PARTITION BY t.file_id ORDER BY t.created_at) AS seqn
  FROM file_task AS t
  JOIN file AS f ON (f.id = t.file_id)
 WHERE t.thread_id IS NULL
   AND t.created_by IS NOT NULL
   AND t.deleted_at IS NULL;

INSERT INTO comment_thread
       (id, file_id, owner_id, created_at, modified_at, page_id, participants,
        seqn, position, frame_id, pull_request_id, anchor_type, shape_id)
SELECT thread_id, file_id, created_by, created_at, created_at, page_id,
       jsonb_build_object('~#set', jsonb_build_array('~u' || created_by::text)),
       seqn, position, frame_id, pull_request_id,
       CASE WHEN shape_id IS NULL THEN 'canvas' ELSE 'layer' END,
       shape_id
  FROM task_thread;

INSERT INTO comment (id, thread_id, owner_id, created_at, modified_at, content)
SELECT comment_id, thread_id, created_by, created_at, created_at, title
  FROM task_thread;

-- the author has read the thread they wrote
INSERT INTO comment_thread_status (thread_id, profile_id, created_at, modified_at)
SELECT thread_id, created_by, created_at, created_at
  FROM task_thread;

UPDATE file AS f
   SET comment_thread_seqn = tt.seqn
  FROM (SELECT file_id, max(seqn) AS seqn
          FROM task_thread
         GROUP BY file_id) AS tt
 WHERE f.id = tt.file_id;

UPDATE file_task AS t
   SET thread_id = tt.thread_id,
       comment_id = tt.comment_id
  FROM task_thread AS tt
 WHERE t.id = tt.task_id;
