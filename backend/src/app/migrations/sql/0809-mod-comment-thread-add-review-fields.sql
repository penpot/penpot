-- A comment thread can belong to a design review (a pull request) and can
-- point at more than a spot of the canvas.
--
-- `pull_request_id` names the design review the thread was written in.
-- It carries no foreign key: the merge of a branch moves the threads of
-- its tasks into main (`files_task.clj::move-branch-tasks!`) and the
-- objects GC later deletes the branch file, which takes the pull request
-- row with it. The thread keeps saying it came from a review.
--
-- `anchor_type` says what the thread points at:
--   * `canvas`: a spot of a board, as every thread did before (`position`
--     and `frame_id`);
--   * `layer`: a layer, named by `shape_id`; `position` is where it was,
--     the fallback when the layer goes away;
--   * `area`: a rectangle of a board, `area` holds its `x`, `y`, `width`
--     and `height` in canvas coordinates.

ALTER TABLE comment_thread
  ADD COLUMN pull_request_id uuid NULL,
  ADD COLUMN anchor_type text NOT NULL DEFAULT 'canvas'
    CHECK (anchor_type IN ('canvas', 'layer', 'area')),
  ADD COLUMN shape_id uuid NULL,
  ADD COLUMN area jsonb NULL;

CREATE INDEX comment_thread__pull_request_id__idx
    ON comment_thread(pull_request_id)
 WHERE pull_request_id IS NOT NULL;
