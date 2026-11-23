-- The presence of a row means fragment references were calculated. An
-- empty array is a result, not a placeholder. Do not backfill here.
CREATE TABLE file_change_refs (
  file_id        uuid NOT NULL,
  file_change_id uuid NOT NULL,
  fragment_ids   uuid[] NOT NULL,
  PRIMARY KEY (file_id, file_change_id),
  CHECK (array_position(fragment_ids, NULL) IS NULL),
  -- Derived rows have no lifecycle of their own. Even an older objects-gc
  -- worker that deletes a file_change row must remove its index entry.
  FOREIGN KEY (file_id, file_change_id)
    REFERENCES file_change (file_id, id) ON DELETE CASCADE DEFERRABLE
) PARTITION BY HASH (file_id);

-- Match the bounds of file_change and file_data for partitionwise joins.
CREATE TABLE file_change_refs_00 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 0);
CREATE TABLE file_change_refs_01 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 1);
CREATE TABLE file_change_refs_02 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 2);
CREATE TABLE file_change_refs_03 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 3);
CREATE TABLE file_change_refs_04 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 4);
CREATE TABLE file_change_refs_05 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 5);
CREATE TABLE file_change_refs_06 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 6);
CREATE TABLE file_change_refs_07 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 7);
CREATE TABLE file_change_refs_08 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 8);
CREATE TABLE file_change_refs_09 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 9);
CREATE TABLE file_change_refs_10 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 10);
CREATE TABLE file_change_refs_11 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 11);
CREATE TABLE file_change_refs_12 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 12);
CREATE TABLE file_change_refs_13 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 13);
CREATE TABLE file_change_refs_14 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 14);
CREATE TABLE file_change_refs_15 PARTITION OF file_change_refs
  FOR VALUES WITH (MODULUS 16, REMAINDER 15);
