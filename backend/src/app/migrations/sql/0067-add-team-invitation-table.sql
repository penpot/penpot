CREATE TABLE team_invitation (  
  team_id uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE,
  email_to text NOT NULL,
  role text NOT NULL,
  valid_until timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(team_id, email_to)
);

ALTER TABLE team_invitation
  ALTER COLUMN email_to SET STORAGE external,
  ALTER COLUMN role SET STORAGE external;
