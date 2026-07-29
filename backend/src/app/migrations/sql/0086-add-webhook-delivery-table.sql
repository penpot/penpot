CREATE TABLE webhook_delivery (
  webhook_id uuid NOT NULL REFERENCES webhook(id) ON DELETE CASCADE DEFERRABLE,
  created_at timestamptz NOT NULL DEFAULT now(),

  error_code text NULL,

  req_data jsonb NULL,
  rsp_data jsonb NULL,

  PRIMARY KEY (webhook_id, created_at)
);

ALTER TABLE webhook_delivery
  ALTER COLUMN error_code SET STORAGE external,
  ALTER COLUMN req_data SET STORAGE external,
  ALTER COLUMN rsp_data SET STORAGE external;
