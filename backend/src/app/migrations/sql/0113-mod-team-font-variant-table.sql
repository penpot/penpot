--- Remove ON DELETE SET NULL from foreign constraint on
--- storage_object table
ALTER TABLE team_font_variant
 DROP CONSTRAINT team_font_variant_otf_file_id_fkey,
  ADD FOREIGN KEY (otf_file_id) REFERENCES storage_object(id) DEFERRABLE,
 DROP CONSTRAINT team_font_variant_ttf_file_id_fkey,
  ADD FOREIGN KEY (ttf_file_id) REFERENCES storage_object(id) DEFERRABLE,
 DROP CONSTRAINT team_font_variant_woff1_file_id_fkey,
  ADD FOREIGN KEY (woff1_file_id) REFERENCES storage_object(id) DEFERRABLE,
 DROP CONSTRAINT team_font_variant_woff2_file_id_fkey,
  ADD FOREIGN KEY (woff2_file_id) REFERENCES storage_object(id) DEFERRABLE,
 DROP CONSTRAINT team_font_variant_team_id_fkey,
  ADD FOREIGN KEY (team_id) REFERENCES team(id) DEFERRABLE;

--- Add deletion protection
CREATE OR REPLACE TRIGGER deletion_protection__tgr
BEFORE DELETE ON team_font_variant FOR EACH STATEMENT
  WHEN ((current_setting('rules.deletion_protection', true) IN ('on', '')) OR
        (current_setting('rules.deletion_protection', true) IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();
