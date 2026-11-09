-- Remove file-library relations that violate the same-team invariant:
-- relations across teams and self-relations. Leftover from before the
-- binfile export, the view-only bundle, link-file-to-library and the
-- manifest writers enforced same-team libraries. Relations with both
-- endpoints in the same team are kept.
--
-- The sync rows go first: the second statement reads file_library_rel
-- while it still contains the forbidden rows.

WITH forbidden AS (
    SELECT flr.file_id, flr.library_file_id
      FROM file_library_rel flr
      JOIN file f ON f.id = flr.file_id
      JOIN project fp ON fp.id = f.project_id
      JOIN file l ON l.id = flr.library_file_id
      JOIN project lp ON lp.id = l.project_id
     WHERE fp.team_id <> lp.team_id
        OR flr.file_id = flr.library_file_id
)
DELETE FROM file_library_sync fls
USING forbidden
WHERE fls.file_id = forbidden.file_id
  AND fls.library_file_id = forbidden.library_file_id;

WITH forbidden AS (
    SELECT flr.file_id, flr.library_file_id
      FROM file_library_rel flr
      JOIN file f ON f.id = flr.file_id
      JOIN project fp ON fp.id = f.project_id
      JOIN file l ON l.id = flr.library_file_id
      JOIN project lp ON lp.id = l.project_id
     WHERE fp.team_id <> lp.team_id
        OR flr.file_id = flr.library_file_id
)
DELETE FROM file_library_rel flr
USING forbidden
WHERE flr.file_id = forbidden.file_id
  AND flr.library_file_id = forbidden.library_file_id;
