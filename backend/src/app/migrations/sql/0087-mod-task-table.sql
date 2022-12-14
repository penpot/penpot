ALTER TABLE task
  ADD COLUMN label text NULL;

ALTER TABLE task
  ALTER COLUMN label SET STORAGE external;

CREATE INDEX task__label__idx
    ON task (label, name, queue)
 WHERE status = 'new';
