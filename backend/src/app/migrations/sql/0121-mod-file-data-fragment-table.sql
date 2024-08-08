ALTER TABLE file_data_fragment
  ADD COLUMN data bytea NULL;

UPDATE file_data_fragment
   SET data = content;

ALTER TABLE file_data_fragment
 DROP COLUMN content;
