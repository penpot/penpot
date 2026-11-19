--- The `file_object_thumbnail` table was replaced by
--- `file_tagged_object_thumbnail` and nothing references it anymore: no query
--- in the codebase touches it and it is empty in production.
---
--- NOTE: the storage bucket named "file-object-thumbnail" (the one used by
--- `file_tagged_object_thumbnail`) has nothing to do with this table and stays.
DROP TABLE IF EXISTS file_object_thumbnail;
