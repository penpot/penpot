ALTER TABLE profile
  ADD COLUMN default_project_id uuid NULL REFERENCES project(id) ON DELETE SET NULL DEFERRABLE,
  ADD COLUMN default_team_id uuid NULL REFERENCES team(id) ON DELETE SET NULL DEFERRABLE;

CREATE INDEX profile__default_project__idx ON profile(default_project_id);
CREATE INDEX profile__default_team__idx ON profile(default_team_id);

with profiles as (
  select p.id,
         tpr.team_id as default_team_id,
         ppr.project_id as default_project_id
    from profile as p
    join team_profile_rel as tpr
      on (tpr.profile_id = p.id and
          tpr.is_owner is true)
    join project_profile_rel as ppr
      on (ppr.profile_id = p.id and
          ppr.is_owner is true)
    join project as pj
      on (pj.id = ppr.project_id)
    join team as tm
      on (tm.id = tpr.team_id)
   where pj.is_default is true
     and tm.is_default is true
     and pj.team_id = tm.id
)
update profile
   set default_team_id = p.default_team_id,
       default_project_id = p.default_project_id
  from profiles as p
 where profile.id = p.id;
