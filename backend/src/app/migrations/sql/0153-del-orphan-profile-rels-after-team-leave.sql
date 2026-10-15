-- Remove team-scoped role rows whose profile is no longer a member of
-- the team that owns the project/file. Leftover from before
-- leave-team/delete-team-member cleaned them up (GHSA-v9r9-h77c-55m2).
-- Rows with a live team membership are kept.

DELETE FROM file_profile_rel AS fpr
USING file AS f
INNER JOIN project AS p ON (p.id = f.project_id)
WHERE fpr.file_id = f.id
  AND NOT EXISTS (SELECT 1
                    FROM team_profile_rel AS tpr
                   WHERE tpr.team_id = p.team_id
                     AND tpr.profile_id = fpr.profile_id);

DELETE FROM project_profile_rel AS ppr
USING project AS p
WHERE ppr.project_id = p.id
  AND NOT EXISTS (SELECT 1
                    FROM team_profile_rel AS tpr
                   WHERE tpr.team_id = p.team_id
                     AND tpr.profile_id = ppr.profile_id);

DELETE FROM team_project_profile_rel AS tppr
WHERE NOT EXISTS (SELECT 1
                    FROM team_profile_rel AS tpr
                   WHERE tpr.team_id = tppr.team_id
                     AND tpr.profile_id = tppr.profile_id);
