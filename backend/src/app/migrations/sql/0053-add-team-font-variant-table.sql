CREATE TABLE team_font_variant (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  team_id    uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  profile_id uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,

  created_at timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz NULL DEFAULT NULL,

  font_id uuid NOT NULL,
  font_family text NOT NULL,
  font_weight smallint NOT NULL,
  font_style text NOT NULL,

  otf_file_id   uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL DEFERRABLE,
  ttf_file_id   uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL DEFERRABLE,
  woff1_file_id uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL DEFERRABLE,
  woff2_file_id uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL DEFERRABLE
);

CREATE INDEX team_font_variant_team_id_font_id_idx
    ON team_font_variant (team_id, font_id);

CREATE INDEX team_font_variant_profile_id_idx
    ON team_font_variant (profile_id);

CREATE INDEX team_font_variant_otf_file_id_idx
    ON team_font_variant (otf_file_id);

CREATE INDEX team_font_variant_ttf_file_id_idx
    ON team_font_variant (ttf_file_id);

CREATE INDEX team_font_variant_woff1_file_id_idx
    ON team_font_variant (woff1_file_id);

CREATE INDEX team_font_variant_woff2_file_id_idx
    ON team_font_variant (woff2_file_id);

ALTER TABLE team_font_variant
  ALTER COLUMN font_family SET STORAGE external,
  ALTER COLUMN font_style SET STORAGE external;

