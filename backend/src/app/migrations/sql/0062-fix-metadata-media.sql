-- Fix problem with content-type incoherence

UPDATE storage_object so
SET metadata = jsonb_set(metadata, '{~:content-type}', to_jsonb(fmo.mtype))
FROM file_media_object fmo
WHERE so.id = fmo.media_id and
      so.metadata->>'~:content-type' != fmo.mtype;

