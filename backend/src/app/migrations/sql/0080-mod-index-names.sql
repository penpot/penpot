ALTER INDEX team_font_variant_deleted_at_idx
RENAME TO team_font_variant__deleted_at__idx;

ALTER INDEX team_deleted_at_idx
RENAME TO team__deleted_at__idx;

ALTER INDEX profile_deleted_at_idx
RENAME TO profile__deleted_at__idx;

ALTER INDEX project_deleted_at_idx
RENAME TO project__deleted_at__idx;
