-- Where the mark of a task goes when it points at no layer: a task on the
-- canvas itself, or one made from a comment thread on the canvas. A task
-- on a layer keeps the spot too, as the fallback when the layer and its
-- board are gone.

ALTER TABLE file_task
  ADD COLUMN position point NULL;
