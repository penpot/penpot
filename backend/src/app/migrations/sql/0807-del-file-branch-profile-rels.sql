-- A branch takes its permissions from the team and project roles alone:
-- every editor of main can open and edit it. Branch creation used to
-- grant its creator the owner role on the branch file, which lifted a
-- team editor to owner of that branch. Drop those grants. A branch that
-- was materialised is an ordinary file now (`is_branch` false) and
-- keeps its roles.

DELETE FROM file_profile_rel AS fpr
 USING file AS f
 WHERE f.id = fpr.file_id
   AND f.is_branch IS TRUE;
