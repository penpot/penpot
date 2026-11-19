-- Snapshot of the actor (event profile-id) name and email when the event
-- happens. The admin console shows each event with the user as they were
-- at that moment; reading the profile later, when the consumer archives
-- the row, picked up any rename or email change made in between.
--
-- Filled on every full row while audit events are persisted, never on
-- the telemetry shadows. Nullable without a default, so adding them does
-- not rewrite the table; rows written before this step keep them null.

ALTER TABLE audit_log
  ADD COLUMN IF NOT EXISTS profile_name text,
  ADD COLUMN IF NOT EXISTS profile_email text;
