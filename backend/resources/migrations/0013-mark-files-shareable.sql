ALTER TABLE file
  ADD COLUMN is_shared BOOLEAN NOT NULL DEFAULT false;

UPDATE file
   SET is_shared = true
 WHERE project_id IN (SELECT id
                        FROM project
                       WHERE team_id = uuid_nil());

