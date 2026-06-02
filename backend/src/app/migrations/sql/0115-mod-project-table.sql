ALTER TABLE project
 DROP CONSTRAINT project_team_id_fkey,
  ADD FOREIGN KEY (team_id) REFERENCES team(id) DEFERRABLE;
