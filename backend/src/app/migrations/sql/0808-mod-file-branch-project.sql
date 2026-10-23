-- A branch lives in the project of its source file: its permissions come
-- from that project and its team. Moving a file used to leave its
-- branches behind in the old project, so the members of the new team
-- could not open them. Put every branch back next to its source file.

UPDATE file AS bf
   SET project_id = sf.project_id
  FROM file_branch AS fb
  JOIN file AS sf ON (sf.id = fb.source_file_id)
 WHERE bf.id = fb.branch_file_id
   AND bf.project_id <> sf.project_id;

-- A moved branch may link libraries of its old team: drop those links,
-- as moving a file does.
DELETE FROM file_library_rel AS rel
 USING file AS f, project AS p, file AS lf, project AS lp
 WHERE rel.file_id = f.id
   AND f.is_branch IS TRUE
   AND p.id = f.project_id
   AND lf.id = rel.library_file_id
   AND lp.id = lf.project_id
   AND lp.team_id <> p.team_id;
