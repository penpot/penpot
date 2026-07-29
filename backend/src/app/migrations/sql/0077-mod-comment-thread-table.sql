--- Add frame_id field.
ALTER TABLE comment_thread
  ADD COLUMN frame_id uuid NULL DEFAULT '00000000-0000-0000-0000-000000000000';
