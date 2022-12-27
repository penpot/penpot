CREATE TABLE usage_quote (
  id uuid NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
  profile_id uuid NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  project_id uuid NULL REFERENCES project(id) ON DELETE CASCADE DEFERRABLE,
  team_id uuid NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  file_id uuid NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  quote bigint NOT NULL,
  target text NOT NULL
);

ALTER TABLE usage_quote
  ALTER COLUMN target SET STORAGE external;

CREATE INDEX usage_quote__profile_id__idx ON usage_quote(profile_id, target);
CREATE INDEX usage_quote__project_id__idx ON usage_quote(project_id, target);
CREATE INDEX usage_quote__team_id__idx ON usage_quote(project_id, target);
