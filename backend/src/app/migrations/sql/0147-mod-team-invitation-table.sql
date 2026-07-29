ALTER TABLE team_invitation
 ADD COLUMN org_id uuid NULL;

ALTER TABLE team_invitation
 ALTER COLUMN team_id DROP NOT NULL;

ALTER TABLE team_invitation
 ADD CONSTRAINT team_invitation_team_or_org_not_null
 CHECK (team_id IS NOT NULL OR org_id IS NOT NULL);

CREATE UNIQUE INDEX team_invitation_org_unique
    ON team_invitation (org_id, email_to)
    WHERE team_id IS NULL;
