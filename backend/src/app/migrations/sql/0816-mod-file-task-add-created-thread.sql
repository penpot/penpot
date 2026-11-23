-- A task created on the canvas gets a thread whose first message is its
-- title. A task made from a message of a thread someone already wrote
-- keeps that thread and that message. The code derived the difference from
-- the message being the first of the thread, which is equally true of a
-- task made from the first message of an existing thread: relinking such
-- a task moved a thread its author never gave it, and a rename would
-- rewrite a message its author did not write.
--
-- Record the fact instead. No backfill: the databases of this line are
-- recreated from the migrations.

ALTER TABLE file_task
  ADD COLUMN created_thread boolean NOT NULL DEFAULT false;
