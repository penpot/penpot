ALTER TABLE file
  ALTER COLUMN data SET STORAGE external,
  ALTER COLUMN name SET STORAGE external;

ALTER TABLE file_change
  ALTER COLUMN data SET STORAGE external,
  ALTER COLUMN changes SET STORAGE external;

ALTER TABLE profile
  ALTER COLUMN fullname SET STORAGE external,
  ALTER COLUMN email SET STORAGE external,
  ALTER COLUMN password SET STORAGE external,
  ALTER COLUMN lang SET STORAGE external,
  ALTER COLUMN theme SET STORAGE external,
  ALTER COLUMN props SET STORAGE external;

ALTER TABLE project
  ALTER COLUMN name SET STORAGE external;

ALTER TABLE team
  ALTER COLUMN name SET STORAGE external;

ALTER TABLE comment
  ALTER COLUMN content SET STORAGE external;

ALTER TABLE comment_thread
  ALTER COLUMN participants SET STORAGE external,
  ALTER COLUMN page_name SET STORAGE external;

ALTER TABLE http_session
  ALTER COLUMN id SET STORAGE external,
  ALTER COLUMN user_agent SET STORAGE external;

ALTER TABLE file_share_token
  ALTER COLUMN token SET STORAGE external;

ALTER TABLE file_media_object
  ALTER COLUMN name SET STORAGE external,
  ALTER COLUMN mtype SET STORAGE external;

ALTER TABLE storage_object
  ALTER COLUMN backend SET STORAGE external,
  ALTER COLUMN metadata SET STORAGE external;

ALTER TABLE storage_data
  ALTER COLUMN data SET STORAGE external;
