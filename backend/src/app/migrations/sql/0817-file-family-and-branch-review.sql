-- A file and every branch cut from it, directly or through another
-- branch, form a family. The family owns what must outlive a branch:
-- its design reviews with their verdicts and activity, the comment
-- threads, the tasks, the merge records, and the thread numbers. A file
-- row stays the unit of storage and permission, so the objects GC can
-- delete a branch file without taking a review or a thread with it: the
-- columns that name a file now let go of it (ON DELETE SET NULL), and
-- only the death of the family deletes them.
--
-- The family id is the id of its root file, so a family is found by eye
-- from a file id and keeps that name after the root file row is gone. A
-- family dies with the last of its files (the triggers at the end).
--
-- The same step renames the branching vocabulary the database stores:
-- a pull request is a branch review, its reviews are reviewers with a
-- verdict, main is the source of a branch, a spot of the canvas is a
-- point, and the compare marks are marks.

---------------------------------------------------------------------------
-- The family table, and the key of every file
---------------------------------------------------------------------------

CREATE TABLE file_family (
  id          uuid PRIMARY KEY,
  -- the last thread number given in the family
  thread_seqn integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);

ALTER TABLE file
  ADD COLUMN family_id uuid NULL REFERENCES file_family(id) ON DELETE CASCADE DEFERRABLE;

-- Every file maps to the root of its chain of branch edges. A file that
-- is the branch of no edge is a root. The edge is followed whatever its
-- state: a merged, archived, or detached branch stays in its family.
CREATE TEMPORARY TABLE file_family_map ON COMMIT DROP AS
WITH RECURSIVE up(id, root) AS (
  SELECT f.id, f.id
    FROM file AS f
   WHERE NOT EXISTS (SELECT 1 FROM file_branch AS b WHERE b.branch_file_id = f.id)
  UNION
  SELECT b.branch_file_id, up.root
    FROM file_branch AS b
    JOIN up ON (b.source_file_id = up.id)
)
SELECT id, root FROM up;

-- Only a cycle of edges leaves a file without a root, and no code writes
-- one; such a file founds its own family rather than stop the boot.
INSERT INTO file_family_map (id, root)
SELECT f.id, f.id
  FROM file AS f
 WHERE NOT EXISTS (SELECT 1 FROM file_family_map AS m WHERE m.id = f.id);

-- The root's thread counter becomes the family's, so a number freed by a
-- deleted thread is never given again.
INSERT INTO file_family (id, thread_seqn, created_at)
SELECT f.id, coalesce(f.comment_thread_seqn, 0), f.created_at
  FROM file AS f
 WHERE f.id IN (SELECT root FROM file_family_map);

UPDATE file AS f
   SET family_id = m.root
  FROM file_family_map AS m
 WHERE m.id = f.id;

ALTER TABLE file ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX file__family_id__idx ON file(family_id);

---------------------------------------------------------------------------
-- Renames: tables, columns, constraints, and indexes
---------------------------------------------------------------------------

-- A table rename keeps the names of its constraints and indexes, so each
-- is renamed after its table. NOT NULL constraints keep their names.

ALTER TABLE file_pull_request RENAME TO file_branch_review;
ALTER TABLE file_branch_review RENAME COLUMN review_snapshot_id TO pinned_snapshot_id;
ALTER TABLE file_branch_review RENAME COLUMN review_revn TO pinned_revn;
ALTER TABLE file_branch_review RENAME COLUMN review_updated_at TO pinned_at;
ALTER INDEX file_pull_request_pkey RENAME TO file_branch_review_pkey;
ALTER TABLE file_branch_review RENAME CONSTRAINT file_pull_request_status_check TO file_branch_review_status_check;
ALTER TABLE file_branch_review RENAME CONSTRAINT file_pull_request_created_by_fkey TO file_branch_review_created_by_fkey;
ALTER TABLE file_branch_review RENAME CONSTRAINT file_pull_request_closed_by_fkey TO file_branch_review_closed_by_fkey;
ALTER INDEX file_pull_request__file_branch_id__open__idx RENAME TO file_branch_review__file_branch_id__open__idx;
ALTER INDEX file_pull_request__review_snapshot_id__idx RENAME TO file_branch_review__pinned_snapshot_id__idx;

ALTER TABLE file_pull_request_review RENAME TO file_branch_reviewer;
ALTER TABLE file_branch_reviewer RENAME COLUMN pull_request_id TO review_id;
ALTER TABLE file_branch_reviewer RENAME COLUMN reviewed_revn TO verdict_revn;
ALTER INDEX file_pull_request_review_pkey RENAME TO file_branch_reviewer_pkey;
ALTER TABLE file_branch_reviewer RENAME CONSTRAINT file_pull_request_review_pull_request_id_fkey TO file_branch_reviewer_review_id_fkey;
ALTER TABLE file_branch_reviewer RENAME CONSTRAINT file_pull_request_review_profile_id_fkey TO file_branch_reviewer_profile_id_fkey;
ALTER INDEX file_pull_request_review__profile_id__idx RENAME TO file_branch_reviewer__profile_id__idx;

ALTER TABLE file_pull_request_activity RENAME TO file_branch_review_activity;
ALTER TABLE file_branch_review_activity RENAME COLUMN pull_request_id TO review_id;
ALTER INDEX file_pull_request_activity_pkey RENAME TO file_branch_review_activity_pkey;
ALTER TABLE file_branch_review_activity RENAME CONSTRAINT file_pull_request_activity_pull_request_id_fkey TO file_branch_review_activity_review_id_fkey;
ALTER TABLE file_branch_review_activity RENAME CONSTRAINT file_pull_request_activity_profile_id_fkey TO file_branch_review_activity_profile_id_fkey;
ALTER INDEX file_pull_request_activity__pull_request_id__idx RENAME TO file_branch_review_activity__review_id__idx;
ALTER INDEX file_pull_request_activity__profile_id__idx RENAME TO file_branch_review_activity__profile_id__idx;

-- The compare marks keep their branch key and its cascade on purpose.
ALTER TABLE file_branch_change_review RENAME TO file_branch_change_mark;
ALTER INDEX file_branch_change_review_pkey RENAME TO file_branch_change_mark_pkey;
ALTER TABLE file_branch_change_mark RENAME CONSTRAINT file_branch_change_review_file_branch_id_fkey TO file_branch_change_mark_file_branch_id_fkey;
ALTER TABLE file_branch_change_mark RENAME CONSTRAINT file_branch_change_review_profile_id_fkey TO file_branch_change_mark_profile_id_fkey;
ALTER INDEX file_branch_change_review__profile_id__idx RENAME TO file_branch_change_mark__profile_id__idx;

-- main's revn when the base was (re)positioned: the source's revn now
ALTER TABLE file_branch RENAME COLUMN base_revn TO base_source_revn;

ALTER TABLE file_branch_merge RENAME COLUMN pull_request_id TO review_id;
ALTER TABLE file_branch_merge RENAME COLUMN pull_request_title TO review_title;

ALTER TABLE comment_thread RENAME COLUMN pull_request_id TO review_id;
ALTER INDEX comment_thread__pull_request_id__idx RENAME TO comment_thread__review_id__idx;

ALTER TABLE file_task RENAME COLUMN pull_request_id TO review_id;
ALTER INDEX file_task__pull_request_id__idx RENAME TO file_task__review_id__idx;

---------------------------------------------------------------------------
-- A reviewer's verdict: none until they give one
---------------------------------------------------------------------------

ALTER TABLE file_branch_reviewer ADD COLUMN verdict text NULL;

UPDATE file_branch_reviewer SET verdict = nullif(state, 'pending');

-- drops file_pull_request_review_state_check with the column
ALTER TABLE file_branch_reviewer DROP COLUMN state;

ALTER TABLE file_branch_reviewer
  ADD CONSTRAINT file_branch_reviewer_verdict_check
  CHECK (verdict IS NULL OR verdict IN ('approved', 'changes-requested'));

---------------------------------------------------------------------------
-- Every row the family owns names it
---------------------------------------------------------------------------

ALTER TABLE file_branch_review
  ADD COLUMN family_id uuid NULL REFERENCES file_family(id) ON DELETE CASCADE DEFERRABLE;

UPDATE file_branch_review AS r
   SET family_id = f.family_id
  FROM file_branch AS b
  JOIN file AS f ON (f.id = b.source_file_id)
 WHERE b.id = r.file_branch_id;

ALTER TABLE file_branch_review ALTER COLUMN family_id SET NOT NULL;

ALTER TABLE comment_thread
  ADD COLUMN family_id uuid NULL REFERENCES file_family(id) ON DELETE CASCADE DEFERRABLE;

UPDATE comment_thread AS ct
   SET family_id = f.family_id
  FROM file AS f
 WHERE f.id = ct.file_id;

ALTER TABLE comment_thread ALTER COLUMN family_id SET NOT NULL;

ALTER TABLE file_task
  ADD COLUMN family_id uuid NULL REFERENCES file_family(id) ON DELETE CASCADE DEFERRABLE;

UPDATE file_task AS t
   SET family_id = f.family_id
  FROM file AS f
 WHERE f.id = t.file_id;

ALTER TABLE file_task ALTER COLUMN family_id SET NOT NULL;

-- A merge record belongs to the family of the file it merged into.
ALTER TABLE file_branch_merge
  ADD COLUMN family_id uuid NULL REFERENCES file_family(id) ON DELETE CASCADE DEFERRABLE;

UPDATE file_branch_merge AS m
   SET family_id = f.family_id
  FROM file AS f
 WHERE f.id = m.file_id;

ALTER TABLE file_branch_merge ALTER COLUMN family_id SET NOT NULL;

---------------------------------------------------------------------------
-- The bare review and merge columns become real keys
---------------------------------------------------------------------------

-- A review the objects GC deleted with its branch file left these ids
-- dangling. Clear them first; repeating the pass changes nothing.

UPDATE comment_thread AS ct
   SET review_id = NULL
 WHERE ct.review_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM file_branch_review AS r WHERE r.id = ct.review_id);

UPDATE file_task AS t
   SET review_id = NULL
 WHERE t.review_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM file_branch_review AS r WHERE r.id = t.review_id);

UPDATE file_task AS t
   SET merge_id = NULL
 WHERE t.merge_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM file_branch_merge AS m WHERE m.id = t.merge_id);

UPDATE file_branch_merge AS m
   SET review_id = NULL
 WHERE m.review_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM file_branch_review AS r WHERE r.id = m.review_id);

ALTER TABLE comment_thread
  ADD CONSTRAINT comment_thread__review_id__fkey
  FOREIGN KEY (review_id) REFERENCES file_branch_review(id) ON DELETE SET NULL DEFERRABLE;

ALTER TABLE file_task
  ADD CONSTRAINT file_task__review_id__fkey
  FOREIGN KEY (review_id) REFERENCES file_branch_review(id) ON DELETE SET NULL DEFERRABLE;

ALTER TABLE file_task
  ADD CONSTRAINT file_task__merge_id__fkey
  FOREIGN KEY (merge_id) REFERENCES file_branch_merge(id) ON DELETE SET NULL DEFERRABLE;

ALTER TABLE file_branch_merge
  ADD CONSTRAINT file_branch_merge__review_id__fkey
  FOREIGN KEY (review_id) REFERENCES file_branch_review(id) ON DELETE SET NULL DEFERRABLE;

---------------------------------------------------------------------------
-- Rows let go of a deleted file instead of going with it
---------------------------------------------------------------------------

-- A thread or a task keeps a file only while the file exists; the family
-- keeps it reachable. The tasks drop the names they copied: the review
-- and the merge record are kept now, and they carry the title and the
-- branch name.

ALTER TABLE comment_thread DROP CONSTRAINT comment_thread_file_id_fkey;
ALTER TABLE comment_thread ALTER COLUMN file_id DROP NOT NULL;
ALTER TABLE comment_thread
  ADD CONSTRAINT comment_thread__file_id__fkey
  FOREIGN KEY (file_id) REFERENCES file(id) ON DELETE SET NULL DEFERRABLE;

ALTER TABLE file_task DROP CONSTRAINT file_task_file_id_fkey;
ALTER TABLE file_task ALTER COLUMN file_id DROP NOT NULL;
ALTER TABLE file_task
  ADD CONSTRAINT file_task__file_id__fkey
  FOREIGN KEY (file_id) REFERENCES file(id) ON DELETE SET NULL DEFERRABLE;

ALTER TABLE file_task
  DROP COLUMN review_title,
  DROP COLUMN branch_name;

-- The edge of a merged branch outlives the branch file: a review finds
-- its target through it.
ALTER TABLE file_branch DROP CONSTRAINT file_branch_branch_file_id_fkey;
ALTER TABLE file_branch ALTER COLUMN branch_file_id DROP NOT NULL;
ALTER TABLE file_branch
  ADD CONSTRAINT file_branch__branch_file_id__fkey
  FOREIGN KEY (branch_file_id) REFERENCES file(id) ON DELETE SET NULL DEFERRABLE;

ALTER TABLE file_branch_review DROP CONSTRAINT file_pull_request_file_branch_id_fkey;
ALTER TABLE file_branch_review ALTER COLUMN file_branch_id DROP NOT NULL;
ALTER TABLE file_branch_review
  ADD CONSTRAINT file_branch_review__file_branch_id__fkey
  FOREIGN KEY (file_branch_id) REFERENCES file_branch(id) ON DELETE SET NULL DEFERRABLE;

-- A review's source and target come from its branch edge. Dropping the
-- columns drops their foreign keys, their check, and their two indexes.
ALTER TABLE file_branch_review
  DROP COLUMN source_file_id,
  DROP COLUMN target_file_id;

---------------------------------------------------------------------------
-- Thread numbers per family
---------------------------------------------------------------------------

DROP INDEX comment_thread__file_id__seqn__idx;

CREATE INDEX comment_thread__file_id__idx
    ON comment_thread(file_id) WHERE file_id IS NOT NULL;

-- The threads of a root file keep their numbers, so a family with no
-- branch (every file before branching) changes nothing. The threads of
-- the branches are numbered once after the root's counter, in the order
-- they were written, and never again.
WITH base AS (
  SELECT f.id AS family_id,
         greatest(coalesce(f.comment_thread_seqn, 0), coalesce(max(r.seqn), 0)) AS seqn
    FROM file AS f
    LEFT JOIN comment_thread AS r ON (r.file_id = f.id)
   WHERE f.id IN (SELECT family_id FROM comment_thread WHERE file_id <> family_id)
   GROUP BY f.id, f.comment_thread_seqn
), numbered AS (
  SELECT t.id, t.family_id,
         row_number() OVER (PARTITION BY t.family_id ORDER BY t.created_at, t.id) AS n
    FROM comment_thread AS t
   WHERE t.file_id <> t.family_id
)
UPDATE comment_thread AS ct
   SET seqn = b.seqn + n.n
  FROM numbered AS n
  JOIN base AS b ON (b.family_id = n.family_id)
 WHERE ct.id = n.id;

-- one number per family, not per file
CREATE UNIQUE INDEX comment_thread__family_id__seqn__idx
    ON comment_thread(family_id, seqn);

UPDATE file_family AS ff
   SET thread_seqn = s.seqn
  FROM (SELECT family_id, max(seqn) AS seqn
          FROM comment_thread
         GROUP BY family_id) AS s
 WHERE s.family_id = ff.id
   AND s.seqn > ff.thread_seqn;

ALTER TABLE file DROP COLUMN comment_thread_seqn;

---------------------------------------------------------------------------
-- Stored values that follow the renames
---------------------------------------------------------------------------

-- A spot of the canvas is a point. The anchor check also requires what
-- each kind of anchor points at.
ALTER TABLE comment_thread DROP CONSTRAINT comment_thread_anchor_type_check;
ALTER TABLE comment_thread ALTER COLUMN anchor_type SET DEFAULT 'point';

UPDATE comment_thread SET anchor_type = 'point' WHERE anchor_type = 'canvas';

ALTER TABLE comment_thread
  ADD CONSTRAINT comment_thread__anchor__check
  CHECK ((anchor_type = 'layer' AND shape_id IS NOT NULL)
         OR (anchor_type = 'area' AND area IS NOT NULL)
         OR anchor_type = 'point')
  NOT VALID;

ALTER TABLE comment_thread VALIDATE CONSTRAINT comment_thread__anchor__check;

-- A merge no longer moves a task into main: it merges it.
UPDATE file_task_event SET type = 'merged' WHERE type = 'moved-to-main';

-- The two sides of a comparison are the branch and its source.
UPDATE file_branch_change_mark
   SET direction = CASE direction
                     WHEN 'branch->main' THEN 'branch->source'
                     WHEN 'main->branch' THEN 'source->branch'
                   END
 WHERE direction IN ('branch->main', 'main->branch');

ALTER TABLE file_branch_change_mark
  ADD CONSTRAINT file_branch_change_mark_direction_check
  CHECK (direction IN ('branch->source', 'source->branch'));

---------------------------------------------------------------------------
-- Indexes of the family read model
---------------------------------------------------------------------------

CREATE INDEX file_task__family_id__idx
    ON file_task(family_id) WHERE deleted_at IS NULL;

CREATE INDEX file_branch_review__family_id__idx
    ON file_branch_review(family_id, status) WHERE deleted_at IS NULL;

CREATE INDEX file_branch_review__file_branch_id__idx
    ON file_branch_review(file_branch_id) WHERE deleted_at IS NULL;

CREATE INDEX file_branch_merge__family_id__idx
    ON file_branch_merge(family_id, created_at DESC);

-- One task per message of a thread.
DROP INDEX file_task__comment_id__idx;

CREATE UNIQUE INDEX file_task__comment_id__unique__idx
    ON file_task(comment_id) WHERE comment_id IS NOT NULL AND deleted_at IS NULL;

---------------------------------------------------------------------------
-- The life of a family
---------------------------------------------------------------------------

-- A file inserted without a family founds its own, named after it. Every
-- path that creates a file (creation, duplication, import, templates)
-- gets a family that way; branch creation then moves the branch file
-- into the family of its source.
CREATE FUNCTION create_file_family()
  RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO file_family (id) VALUES (NEW.id) ON CONFLICT (id) DO NOTHING;
  NEW.family_id := NEW.id;
  RETURN NEW;
END $$;

CREATE TRIGGER file__create_family__tgr
  BEFORE INSERT ON file
  FOR EACH ROW
  WHEN (NEW.family_id IS NULL)
  EXECUTE FUNCTION create_file_family();

-- A file that moves to another family deletes the one it left once no
-- file remains in it. The code that moves a file moves the rows of its
-- family with it first: what stays behind goes with the family.
CREATE FUNCTION delete_left_file_family()
  RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  DELETE FROM file_family AS ff
   WHERE ff.id = OLD.family_id
     AND NOT EXISTS (SELECT 1 FROM file AS f WHERE f.family_id = OLD.family_id);
  RETURN NULL;
END $$;

CREATE TRIGGER file__leave_family__tgr
  AFTER UPDATE OF family_id ON file
  FOR EACH ROW
  WHEN (OLD.family_id IS DISTINCT FROM NEW.family_id)
  EXECUTE FUNCTION delete_left_file_family();

-- When the last file of a family is hard-deleted, the family goes, and
-- with it every review, verdict, activity row, thread, task, and merge
-- record it owns. A trigger rather than a step of the objects GC: every
-- path that hard-deletes files is covered, and none can leak a family
-- nobody reaches.
CREATE FUNCTION delete_empty_file_families()
  RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  DELETE FROM file_family AS ff
   WHERE ff.id IN (SELECT DISTINCT family_id FROM old_rows)
     AND NOT EXISTS (SELECT 1 FROM file AS f WHERE f.family_id = ff.id);
  RETURN NULL;
END $$;

CREATE TRIGGER file__delete_empty_family__tgr
  AFTER DELETE ON file
  REFERENCING OLD TABLE AS old_rows
  FOR EACH STATEMENT
  EXECUTE FUNCTION delete_empty_file_families();

-- Migration 0814 gave a thread to every task that had none. Those
-- threads are not told apart from a thread the code made for a task:
-- both hold the task's title as their only message, owned by the task's
-- author. They stay.
