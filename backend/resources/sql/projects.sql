-- :name create-project :<! :1
insert into projects (id, "user", name)
values (:id, :user, :name)
returning *;

-- :name update-project :<! :1
update projects
   set name = :name,
       version = :version
 where id = :id
   and "user" = :user
   and deleted_at is null
returning *;

-- :name delete-project :! :n
update projects
   set deleted_at = clock_timestamp()
 where id = :id
   and "user" = :user
   and deleted_at is null;

-- :name get-project-by-id :? :1
select p.*
  from projects as p
 where p.id = :id
   and p.deleted_at is null;

-- :name get-projects :? :*
select pr.*,
       ps.token as share_token
  from projects as pr
 inner join project_shares as ps
         on (ps.project = pr.id)
 where pr.deleted_at is null
   and pr."user" = :user
 order by pr.created_at asc;

-- :name get-project-by-share-token :? :*
select p.*
  from projects as p
 inner join project_shares as ps
         on (p.id = ps.project)
  where ps.token = :token;

-- :name get-share-tokens-for-project
select s.*
  from project_shares as s
 where s.project = :project
 order by s.created_at desc;
