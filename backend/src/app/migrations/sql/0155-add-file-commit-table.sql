--- Remember which commits update-file has applied, so a save the client
--- sends again under the same commit id is answered without applying its
--- changes twice. A row is written in the transaction of the save, so it
--- exists exactly when the changes do. objects-gc deletes rows older than
--- commit-retention (app.rpc.commands.files-update), which outlasts how long
--- a client keeps sending a failed save.

CREATE TABLE file_commit (
  file_id    uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  commit_id  uuid NOT NULL,
  revn       bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (file_id, commit_id)
);

CREATE INDEX file_commit__created_at__idx
    ON file_commit(created_at);
