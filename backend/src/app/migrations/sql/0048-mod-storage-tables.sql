--- Drop redundant index already covered by primary key
DROP INDEX storage_data__id__idx;

--- Replace not efficient index with more efficient one
DROP INDEX storage_object__id__deleted_at__idx;

CREATE INDEX storage_object__id__deleted_at__idx
    ON storage_object(deleted_at, id)
 WHERE deleted_at IS NOT NULL;
