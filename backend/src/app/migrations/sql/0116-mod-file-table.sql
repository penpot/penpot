ALTER TABLE file
 DROP CONSTRAINT file_project_id_fkey,
  ADD FOREIGN KEY (project_id) REFERENCES project(id) DEFERRABLE;
