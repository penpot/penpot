--- This setting allow to optimize the table for heavy write workload
--- leaving space on the page for HOT updates
ALTER TABLE task SET (FILLFACTOR=60);
