--- Normalize storage_object.metadata inside Transit (Phase 1).
---
--- Brings every row to a canonical shape WITHOUT changing the encoding
--- family (still Transit JSON, "~:" keys), so old and new code keep
--- reading while it runs. Each statement below documents its own rule.
---
--- NOTE: key-existence is tested with `-> ... IS [NOT] NULL` instead of
--- the `?` operator because the migration runner prepares statements
--- and a bare `?` is parsed as a bind placeholder.
---
--- Idempotent: re-running changes zero rows.
--- NOTE for large instances (e.g. our production, ~33M rows): DO NOT run
--- this inline. Mark the migration as executed (fake) and run the batched
--- equivalent by hand: .agents/plans/2026-09-11-storage-normalize-phase1.sql

-- Drop leftovers from the old chunked-upload metadata: no code reads
-- "~:upload-id" / "~:chunk-index" anymore (#11651 moved chunks to
-- upload_session_chunk and objects-gc purges the orphan rows), and the
-- Phase 1 readers already ignore them on decode.
UPDATE storage_object
   SET metadata = metadata - '~:upload-id' - '~:chunk-index'
 WHERE (metadata -> '~:upload-id') IS NOT NULL
    OR (metadata -> '~:chunk-index') IS NOT NULL;

-- Rows that carry only the legacy "~:reference": promote it to "~:bucket"
-- with the same value. The value comes as a Transit keyword ("~:xxx"), so
-- the "~:" prefix is stripped; plain strings are kept as they are.
UPDATE storage_object
   SET metadata = (metadata - '~:reference')
                  || jsonb_build_object('~:bucket',
                       CASE WHEN metadata->>'~:reference' LIKE '~:%'
                            THEN substr(metadata->>'~:reference', 3)
                            ELSE metadata->>'~:reference'
                       END)
 WHERE (metadata -> '~:reference') IS NOT NULL
   AND (metadata -> '~:bucket') IS NULL;

-- Rows that carry both keys: "~:bucket" wins and the residual
-- "~:reference" is dropped.
UPDATE storage_object
   SET metadata = metadata - '~:reference'
 WHERE (metadata -> '~:reference') IS NOT NULL
   AND (metadata -> '~:bucket') IS NOT NULL;

-- Rows with neither key get the historical default bucket
-- "file-media-object" (the value `lookup-bucket` used to fall back to).
-- NULL columns match this rule too (`->` on NULL is NULL) and are
-- backfilled by coalesce.
UPDATE storage_object
   SET metadata = coalesce(metadata, '{}') || '{"~:bucket": "file-media-object"}'
 WHERE (metadata -> '~:bucket') IS NULL
   AND (metadata -> '~:reference') IS NULL;
