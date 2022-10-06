CREATE INDEX file__deleted_at__idx
    ON file (deleted_at, id)
 WHERE deleted_at IS NOT NULL;
