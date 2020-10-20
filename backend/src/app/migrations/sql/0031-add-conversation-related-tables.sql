CREATE TABLE comment_thread (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  owner_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  page_id uuid NOT NULL,

  participants jsonb NOT NULL,
  seqn integer NOT NULL DEFAULT 0,

  position point NOT NULL,

  is_resolved boolean NOT NULL DEFAULT false
);

CREATE INDEX comment_thread__owner_id__idx ON comment_thread(owner_id);
CREATE UNIQUE INDEX comment_thread__file_id__seqn__idx ON comment_thread(file_id, seqn);

CREATE TABLE comment_thread_status (
  thread_id uuid NOT NULL REFERENCES comment_thread(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (thread_id, profile_id)
);

CREATE TABLE comment (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  thread_id uuid NOT NULL REFERENCES comment_thread(id) ON DELETE CASCADE,
  owner_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  content text NOT NULL
);

CREATE INDEX comment__thread_id__idx ON comment(thread_id);
CREATE INDEX comment__owner_id__idx ON comment(owner_id);

ALTER TABLE file ADD COLUMN comment_thread_seqn integer DEFAULT 0;

