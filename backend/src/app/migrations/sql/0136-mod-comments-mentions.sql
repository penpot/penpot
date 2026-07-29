ALTER TABLE comment ADD COLUMN mentions uuid[] NULL DEFAULT '{}';

ALTER TABLE comment_thread ADD COLUMN mentions uuid[] NULL DEFAULT '{}';
