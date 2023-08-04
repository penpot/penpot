ALTER TABLE file_change
  ADD COLUMN label text NULL;

ALTER TABLE file_change
  ALTER COLUMN label SET STORAGE external;

CREATE INDEX file_change__label__idx
    ON file_change (file_id, label)
 WHERE label is not null;
