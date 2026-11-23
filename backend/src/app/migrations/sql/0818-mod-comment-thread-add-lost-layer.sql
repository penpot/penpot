-- A comment thread whose layer did not reach the target of a merge keeps
-- the name of that layer in `lost_shape_name`, as a task does
-- (`file_task.lost_shape_name`), so the thread can say which layer it
-- lost and offer to link it to another one. Linking the thread to a
-- layer again clears it.
--
-- A review thread that the merge leaves behind on the branch can be
-- moved to the target later, once the branch document is gone. For such
-- a thread whose layer the target lacks, the merge stores what the later
-- move needs to land it: the ancestors of the layer in the branch
-- document, nearest first and without the root frame
-- (`landing_ancestors`), and the rectangle of the canvas the layer
-- covered (`landing_area`, the `x`, `y`, `width` and `height` of the
-- layer). The later move lands the thread on the first of those
-- ancestors that the target has, else on the area, and clears both
-- columns. `landing_area` is not the `area` column: `area` is what an
-- `area` thread points at, and the thread still points at its layer.

ALTER TABLE comment_thread
  ADD COLUMN lost_shape_name text NULL,
  ADD COLUMN landing_ancestors uuid[] NULL,
  ADD COLUMN landing_area jsonb NULL;
