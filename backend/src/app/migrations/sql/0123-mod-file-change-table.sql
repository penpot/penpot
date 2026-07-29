CREATE INDEX IF NOT EXISTS file_change__created_at__label__idx
    ON file_change (created_at, label);
