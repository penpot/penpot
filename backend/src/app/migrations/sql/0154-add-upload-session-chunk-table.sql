--- Add the upload_session_chunk table, a deleted_at marker to upload_session,
--- and make the upload_session.profile_id foreign key non-deleting.

--- Each row maps one chunk of a chunked-upload session to the storage_object
--- row that holds its bytes. Both foreign keys are ON DELETE NO ACTION
--- DEFERRABLE on purpose: neither the session nor the storage object can be
--- removed while a mapping row exists, so every deletion path must first
--- remove the mapping (assemble-chunks on success, objects-gc for consumed
--- and stalled sessions). NO ACTION is identical to RESTRICT in normal
--- (immediate) operation; only the deferrability differs, which tooling
--- such as the backend test fixture relies on
--- (SET CONSTRAINTS ALL DEFERRED).

CREATE TABLE upload_session_chunk (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  created_at  timestamptz NOT NULL DEFAULT now(),

  session_id  uuid NOT NULL REFERENCES upload_session(id) ON DELETE NO ACTION DEFERRABLE,
  object_id   uuid NOT NULL REFERENCES storage_object(id) ON DELETE NO ACTION DEFERRABLE,
  chunk_index integer NOT NULL,

  UNIQUE (session_id, chunk_index)
);

CREATE INDEX upload_session_chunk__session_id__idx
    ON upload_session_chunk(session_id);

CREATE INDEX upload_session_chunk__object_id__idx
    ON upload_session_chunk(object_id);

--- The deleted_at column marks a session as consumed: assemble-chunks sets it
--- instead of deleting the row, and objects-gc physically purges consumed
--- sessions (as well as stalled ones that were never assembled).

ALTER TABLE upload_session
  ADD COLUMN deleted_at timestamptz NULL DEFAULT NULL;

CREATE INDEX upload_session__deleted_at_created_at__idx
    ON upload_session(deleted_at, created_at);

--- The profile foreign key moves from CASCADE to NO ACTION DEFERRABLE:
--- sessions must be purged procedurally (objects-gc drains the sessions of
--- profiles pending purge before the profile row is deleted), so a profile
--- can no longer disappear with live chunk mappings behind it.

ALTER TABLE upload_session
  DROP CONSTRAINT upload_session_profile_id_fkey;

ALTER TABLE upload_session
  ADD CONSTRAINT upload_session_profile_id_fkey
  FOREIGN KEY (profile_id) REFERENCES profile(id) ON DELETE NO ACTION DEFERRABLE;
