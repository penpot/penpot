--- Add deletion protection
CREATE OR REPLACE TRIGGER deletion_protection__tgr
BEFORE DELETE ON team FOR EACH STATEMENT
  WHEN ((current_setting('rules.deletion_protection', true) IN ('on', '')) OR
        (current_setting('rules.deletion_protection', true) IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();

ALTER TABLE team
 DROP CONSTRAINT team_photo_id_fkey,
  ADD FOREIGN KEY (photo_id) REFERENCES storage_object(id) DEFERRABLE;
