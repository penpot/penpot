--- Normalize storage_object.metadata inside Transit (Phase 1).
---
--- Brings every row to a canonical shape WITHOUT changing the encoding
--- family (still Transit JSON, "~:" keys), so old and new code keep
--- reading while it runs:
---   1. Rows with "~:reference" and no "~:bucket" get "~:bucket" with the
---      same value (reference values come as Transit keywords "~:xxx",
---      hence the prefix strip; plain strings are kept as they are),
---      and "~:reference" is dropped.
---   2. Rows with both keep "~:bucket"; the residual "~:reference" is dropped.
---   3. Rows with neither get "~:bucket" = "file-media-object"
---      (historic default from lookup-bucket).
---
--- Chunk leftovers ("~:upload-id" / "~:chunk-index") are dropped: no
--- code reads them anymore (#11651 moved chunks to upload_session_chunk
--- and objects-gc purges the orphan rows), and the Phase 1 readers
--- already ignore them on decode.
---
--- NOTE: key-existence is tested with `-> ... IS [NOT] NULL` instead of
--- the `?` operator because the migration runner prepares statements
--- and a bare `?` is parsed as a bind placeholder.
---
--- Idempotent: re-running changes zero rows.
--- NOTE for large instances (e.g. our production, ~33M rows): DO NOT run
--- this inline. Mark the migration as executed (fake) and run the batched
--- equivalent by hand: .agents/plans/2026-09-11-storage-normalize-phase1.sql

UPDATE storage_object
   SET metadata = metadata - '~:upload-id' - '~:chunk-index'
 WHERE (metadata -> '~:upload-id') IS NOT NULL
    OR (metadata -> '~:chunk-index') IS NOT NULL;

UPDATE storage_object
   SET metadata = (metadata - '~:reference')
                  || jsonb_build_object('~:bucket',
                       CASE WHEN metadata->>'~:reference' LIKE '~:%'
                            THEN substr(metadata->>'~:reference', 3)
                            ELSE metadata->>'~:reference'
                       END)
 WHERE (metadata -> '~:reference') IS NOT NULL
   AND (metadata -> '~:bucket') IS NULL;

UPDATE storage_object
   SET metadata = metadata - '~:reference'
 WHERE (metadata -> '~:reference') IS NOT NULL
   AND (metadata -> '~:bucket') IS NOT NULL;

UPDATE storage_object
   SET metadata = metadata || '{"~:bucket": "file-media-object"}'
 WHERE (metadata -> '~:bucket') IS NULL
   AND (metadata -> '~:reference') IS NULL;
