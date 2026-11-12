-- Tasks: a piece of work on a file, assigned to someone and tied to a
-- layer. A task is born open, its assignee marks it done, someone
-- verifies it (or reopens it), and it may be dropped as "won't do":
--
--   open -> done -> verified
--   done -> reopened -> done
--   open | reopened -> wont-do
--
-- Anyone who can edit the file may move a task between states; every
-- move is recorded in `file_task_event` with who made it.
--
-- A task may come from a comment thread (`thread_id`, the thread stays
-- attached to it) and from a design review (`pull_request_id`). Neither
-- column has a foreign key to the pull request: the merge of a branch
-- moves its tasks into main and the objects GC later deletes the branch
-- file and its pull request rows. The task keeps the names it shows
-- (`review_title`, `branch_name`) and the merge that moved it
-- (`merge_id`, a `file_branch_merge` row), so the tasks of a merged
-- branch remain as its history.
--
-- `shape_id` is the layer the task points at, inside `frame_id` on
-- `page_id`. When that layer is deleted the task moves to the board
-- that held it and keeps the name of the lost layer in
-- `lost_shape_name`, until it is linked to another layer.

CREATE TABLE file_task (
  id                uuid PRIMARY KEY,

  file_id           uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  pull_request_id   uuid NULL,
  review_title      text NULL,
  thread_id         uuid NULL REFERENCES comment_thread(id) ON DELETE SET NULL DEFERRABLE,

  title             text NOT NULL,
  status            text NOT NULL DEFAULT 'open'
                      CHECK (status IN ('open', 'done', 'reopened', 'verified', 'wont-do')),

  assignee_id       uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  created_by        uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,

  page_id           uuid NOT NULL,
  frame_id          uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
  shape_id          uuid NULL,
  lost_shape_name   text NULL,

  merge_id          uuid NULL,
  branch_name       text NULL,

  status_changed_at timestamptz NULL,
  status_changed_by uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,

  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at        timestamptz NULL
);

CREATE INDEX file_task__file_id__idx
    ON file_task(file_id) WHERE deleted_at IS NULL;

CREATE INDEX file_task__pull_request_id__idx
    ON file_task(pull_request_id) WHERE pull_request_id IS NOT NULL;

CREATE INDEX file_task__thread_id__idx
    ON file_task(thread_id) WHERE thread_id IS NOT NULL;

CREATE INDEX file_task__assignee_id__idx
    ON file_task(assignee_id);

CREATE INDEX file_task__created_by__idx
    ON file_task(created_by);

CREATE INDEX file_task__status_changed_by__idx
    ON file_task(status_changed_by);

CREATE INDEX file_task__merge_id__idx
    ON file_task(merge_id) WHERE merge_id IS NOT NULL;

-- What happened to a task, in order: the lines a thread shows between its
-- comments ("Ana made this a task for you", "Marc marked it as done").
--
--   created        {}
--   assigned       {:assignee-id}
--   status         {:from :to}
--   renamed        {:title}
--   relinked       {:shape-id :shape-name}
--   layer-deleted  {:shape-id :shape-name :frame-id :frame-name :objects}
--   layer-restored {:shape-id :shape-name}
--   moved-to-main  {:merge-id :branch-name}

CREATE TABLE file_task_event (
  id          uuid PRIMARY KEY,
  task_id     uuid NOT NULL REFERENCES file_task(id) ON DELETE CASCADE DEFERRABLE,
  profile_id  uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  type        text NOT NULL,
  payload     jsonb NULL,
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX file_task_event__task_id__idx
    ON file_task_event(task_id, created_at);

CREATE INDEX file_task_event__profile_id__idx
    ON file_task_event(profile_id);
