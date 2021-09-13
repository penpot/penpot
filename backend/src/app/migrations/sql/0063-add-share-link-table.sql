CREATE TABLE share_link (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  owner_id uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  pages uuid[],
  flags text[]
);

CREATE INDEX share_link_file_id_idx ON share_link(file_id);
CREATE INDEX share_link_owner_id_idx ON share_link(owner_id);
