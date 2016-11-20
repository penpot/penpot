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
select distinct
       pr.*,
       ps.token as share_token,
       count(pg.id) over win as total_pages,
       first_value(pg.id) over win as page_id,
       first_value(pg.data) over win as page_data,
       first_value(pg.name) over win as page_name,
       first_value(pg.version) over win as page_version,
       first_value(pg.created_at) over win as page_created_at,
       first_value(pg.metadata) over win as page_metadata,
       first_value(pg.modified_at) over win as page_modified_at
  from projects as pr
 inner join project_shares as ps
         on (ps.project = pr.id)
  left join pages as pg
         on (pg.project = pr.id)
 where pr.deleted_at is null
   and pr."user" = :user
window win as (partition by pr.id
               order by pg.created_at
               range between unbounded preceding
                         and unbounded following)
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
