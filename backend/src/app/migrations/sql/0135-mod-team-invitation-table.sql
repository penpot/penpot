ALTER TABLE team_invitation
  ADD COLUMN created_by uuid NULL REFERENCES profile(id) ON DELETE SET NULL;
