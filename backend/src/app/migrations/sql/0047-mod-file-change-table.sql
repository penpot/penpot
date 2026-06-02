--- Helps on the lagged changes query on update-file rpc
CREATE INDEX file_change__file_id__revn__idx ON file_change (file_id, revn);

--- Drop redundant index
DROP INDEX page_change_file_id_idx;

--- Add profile_id field.
ALTER TABLE file_change
  ADD COLUMN profile_id uuid NULL REFERENCES profile (id) ON DELETE SET NULL;

CREATE INDEX file_change__profile_id__idx
    ON file_change (profile_id)
 WHERE profile_id IS NOT NULL;

--- Fix naming
ALTER INDEX file_change__created_at_idx RENAME TO file_change__created_at__idx;
