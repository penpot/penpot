CREATE TABLE storage_object (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  created_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz NULL DEFAULT NULL,

  size bigint NOT NULL DEFAULT 0,
  backend text NOT NULL,

  metadata jsonb NULL DEFAULT NULL
);

CREATE INDEX storage_object__id__deleted_at__idx
    ON storage_object(id, deleted_at)
 WHERE deleted_at IS NOT null;

CREATE TABLE storage_data (
  id uuid PRIMARY KEY REFERENCES storage_object (id) ON DELETE CASCADE,
  data bytea NOT NULL
);

CREATE INDEX storage_data__id__idx ON storage_data(id);

-- Table used for store inflight upload ids, for later recheck and
-- delete possible staled files that exists on the physical storage
-- but does not exists in the 'storage_object' table.

CREATE TABLE storage_pending (
  id uuid NOT NULL,

  backend text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (created_at, id)
);

