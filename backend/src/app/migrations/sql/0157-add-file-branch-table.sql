CREATE TABLE file_branch (
  id               uuid PRIMARY KEY,

  created_at       timestamptz NOT NULL DEFAULT now(),
  modified_at      timestamptz NOT NULL DEFAULT now(),
  merged_at        timestamptz NULL,

  core_file_id     uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  file_id          uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  profile_id       uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,

  name             text NOT NULL,
  status           text NOT NULL DEFAULT 'open',

  base_snapshot_id uuid NOT NULL,
  base_revn        bigint NOT NULL,

  --- Maps the ids the branch copy remapped (file id, media ids) back to
  --- the core file ids
  id_index         jsonb NOT NULL,

  UNIQUE (file_id)
);

CREATE INDEX file_branch__core_file_id__idx
    ON file_branch(core_file_id);
