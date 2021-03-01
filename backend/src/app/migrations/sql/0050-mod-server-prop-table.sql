ALTER TABLE server_prop
  ADD COLUMN preload boolean DEFAULT false;

UPDATE server_prop SET preload = true;
