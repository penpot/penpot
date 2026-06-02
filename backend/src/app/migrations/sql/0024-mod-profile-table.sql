ALTER TABLE profile ADD COLUMN is_active boolean NOT NULL DEFAULT false;

UPDATE profile SET is_active = true WHERE pending_email is null;

ALTER TABLE profile DROP COLUMN pending_email;
