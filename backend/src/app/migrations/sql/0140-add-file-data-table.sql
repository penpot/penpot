CREATE TABLE file_data (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  id uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz NULL,

  type text NULL,
  backend text NULL,

  metadata jsonb NULL,
  data bytea NULL,

  PRIMARY KEY (file_id, id)

) PARTITION BY HASH (file_id, id);

CREATE TABLE file_data_0 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE file_data_1 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE file_data_2 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE file_data_3 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE file_data_4 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE file_data_5 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE file_data_6 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE file_data_7 PARTITION OF file_data FOR VALUES WITH (MODULUS 8, REMAINDER 7);

CREATE INDEX IF NOT EXISTS file_data__deleted_at__idx
    ON file_data (deleted_at, file_id, id)
 WHERE deleted_at IS NOT NULL;
