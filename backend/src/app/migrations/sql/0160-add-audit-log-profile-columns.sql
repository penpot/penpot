-- Snapshot of the actor (event profile-id) name and email when the event
-- happens. The admin console shows each event with the user as they were
-- at that moment; reading the profile later, when the consumer archives
-- the row, picked up any rename or email change made in between.
--
-- Only filled for the events the admin console archives, so the email is
-- not copied for rows that nothing reads it from. Nullable without a
-- default, so adding them does not rewrite the table.

ALTER TABLE audit_log
  ADD COLUMN IF NOT EXISTS profile_name text,
  ADD COLUMN IF NOT EXISTS profile_email text;
