CREATE TABLE organization (
  id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  name text NOT NULL,

  PRIMARY KEY (id)
);

ALTER TABLE team
  ADD COLUMN organization_id uuid NULL REFERENCES organization(id) ON DELETE SET NULL;



CREATE TABLE organization_profile_rel (
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  role text NOT NULL DEFAULT 'user',

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (organization_id, profile_id)
);

CREATE INDEX team__organization_id__idx
    ON team(organization_id);

CREATE INDEX organization_profile_rel__organization_id__idx
    ON organization_profile_rel(organization_id);

CREATE INDEX organization_profile_rel__profile_id__idx
    ON organization_profile_rel(profile_id);


