ALTER TABLE file_library_rel
 DROP CONSTRAINT file_library_rel_library_file_id_fkey,
  ADD CONSTRAINT file_library_rel_library_file_id_fkey
         FOREIGN KEY (library_file_id) REFERENCES file(id) ON DELETE CASCADE DEFERRABLE;
