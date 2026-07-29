ALTER TABLE file_change
 DROP CONSTRAINT file_change_file_id_fkey,
 DROP CONSTRAINT file_change_profile_id_fkey,
  ADD FOREIGN KEY (file_id) REFERENCES file(id) DEFERRABLE,
  ADD FOREIGN KEY (profile_id) REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE;
