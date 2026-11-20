-- ---------------------------------------------------------------------
-- Team storage usage: cached per-team media storage bytes.
--
-- The always-on audit-log consumer marks dirty teams and a bounded job
-- recomputes their totals, so uploads read one narrow row instead of
-- scanning `storage_object`. `dirty_at` set = pending recompute; null =
-- fresh. A missing row means never computed (fail open + mark dirty).
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS team_storage_usage (
  team_id     uuid         PRIMARY KEY REFERENCES team(id) ON DELETE CASCADE,
  total_bytes bigint       NOT NULL DEFAULT 0,
  dirty_at    timestamptz  NULL DEFAULT NULL,
  updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS team_storage_usage__dirty_at__idx
    ON team_storage_usage (dirty_at ASC, team_id ASC)
 WHERE dirty_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS team_storage_usage__updated_at__idx
    ON team_storage_usage (updated_at ASC)
 WHERE dirty_at IS NULL;
