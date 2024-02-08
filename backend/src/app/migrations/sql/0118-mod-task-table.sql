-- Removes the partitioning.
CREATE TABLE new_task (LIKE task INCLUDING ALL);
INSERT INTO new_task SELECT * FROM task;
ALTER TABLE task RENAME TO old_task;
ALTER TABLE new_task RENAME TO task;
DROP TABLE old_task;
ALTER INDEX new_task_label_name_queue_idx RENAME TO task__label_name_queue__idx;
ALTER INDEX new_task_scheduled_at_queue_idx RENAME TO task__scheduled_at_queue__idx;
ALTER TABLE task DROP CONSTRAINT new_task_pkey;
ALTER TABLE task ADD PRIMARY KEY (id);
ALTER TABLE task ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE task ALTER COLUMN modified_at SET DEFAULT now();
