CREATE TABLE file_library_rel (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  library_file_id uuid NOT NULL REFERENCES file(id) ON DELETE RESTRICT,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (file_id, library_file_id)
);

COMMENT ON TABLE file_library_rel
     IS 'Relation between files and the shared library files they use (NM)';

CREATE INDEX file_library_rel__file_id__idx
    ON file_library_rel(file_id);

