CREATE INDEX profile_deleted_at_idx
    ON profile(deleted_at, id)
 WHERE deleted_at IS NOT NULL;

CREATE INDEX project_deleted_at_idx
    ON project(deleted_at, id)
 WHERE deleted_at IS NOT NULL;

CREATE INDEX team_deleted_at_idx
    ON team(deleted_at, id)
 WHERE deleted_at IS NOT NULL;

CREATE INDEX team_font_variant_deleted_at_idx
    ON team_font_variant(deleted_at, id)
 WHERE deleted_at IS NOT NULL;
