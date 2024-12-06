ALTER TABLE webhook
  ADD COLUMN profile_id uuid NULL REFERENCES profile (id) ON DELETE SET NULL;

CREATE INDEX webhook__profile_id__idx
    ON webhook (profile_id)
 WHERE profile_id IS NOT NULL;