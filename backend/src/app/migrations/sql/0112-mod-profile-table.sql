ALTER TABLE profile
 DROP CONSTRAINT profile_photo_id_fkey,
  ADD FOREIGN KEY (photo_id) REFERENCES storage_object(id) DEFERRABLE,
 DROP CONSTRAINT profile_default_project_id_fkey,
  ADD FOREIGN KEY (default_project_id) REFERENCES project(id) DEFERRABLE,
 DROP CONSTRAINT profile_default_team_id_fkey,
  ADD FOREIGN KEY (default_team_id) REFERENCES team(id) DEFERRABLE;

--- Add deletion protection
CREATE OR REPLACE TRIGGER deletion_protection__tgr
BEFORE DELETE ON profile FOR EACH STATEMENT
  WHEN ((current_setting('rules.deletion_protection', true) IN ('on', '')) OR
        (current_setting('rules.deletion_protection', true) IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();

