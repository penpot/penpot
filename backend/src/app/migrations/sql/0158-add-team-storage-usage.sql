-- Cached per-team media storage usage for the
-- media-storage-bytes-per-team quota. A background audit-log consumer
-- marks dirty teams and a bounded job recomputes their totals with the
-- quota query, so uploads read one narrow row instead of scanning
-- storage_object. `dirty_at` set = pending recompute; null = fresh.
-- A missing row means never computed (fail open + mark dirty).

CREATE TABLE team_storage_usage (
  team_id     uuid         PRIMARY KEY REFERENCES team(id) ON DELETE CASCADE,
  total_bytes bigint       NOT NULL DEFAULT 0,
  dirty_at    timestamptz  NULL DEFAULT NULL,
  updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX team_storage_usage__dirty_at__idx
    ON team_storage_usage (dirty_at ASC, team_id ASC)
 WHERE dirty_at IS NOT NULL;
