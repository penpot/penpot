-- Renames the old, already deprecated backend name with new one on
-- all storage object rows.

UPDATE storage_object
   SET backend = 'assets-fs'
 WHERE backend = 'fs';

UPDATE storage_object
   SET backend = 'assets-s3'
 WHERE backend = 's3';
