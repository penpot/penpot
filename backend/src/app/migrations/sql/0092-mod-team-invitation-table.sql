ALTER TABLE team_invitation DROP CONSTRAINT team_invitation_pkey;
ALTER TABLE team_invitation ADD COLUMN id uuid DEFAULT uuid_generate_v4() PRIMARY KEY;
ALTER TABLE team_invitation ADD CONSTRAINT team_invitation_unique UNIQUE (team_id, email_to);
