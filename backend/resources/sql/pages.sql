-- :name create-page :<! :1
insert into pages (id, "user", project, name, data, metadata)
values (:id, :user, :project, :name, :data, :metadata)
returning *;

-- :name update-page :<! :1
update pages
   set name = :name,
       data = :data,
       version = :version,
       metadata = :metadata
 where id = :id
   and "user" = :user
   and deleted_at is null
returning *;

-- :name update-page-metadata :<! :1
update pages
   set name = :name,
       version = :version,
       metadata = :metadata
 where id = :id
   and "user" = :user
   and deleted_at is null
returning *;

-- :name delete-page :! :n
update pages
   set deleted_at = clock_timestamp()
 where id = :id
   and "user" = :user
   and deleted_at is null;

-- :name get-pages :? :*
select pg.*
  from pages as pg
 where pg.user = :user
   and pg.deleted_at is null
 order by created_at asc;

-- :name get-page-by-id :? :1
select pg.* from pages as pg
 where pg.id = :id
   and pg.deleted_at is null;

-- :name get-pages-for-user-and-project :? :*
select pg.*
  from pages as pg
 where pg.user = :user
   and pg.project = :project
   and pg.deleted_at is null
 order by pg.created_at asc;

-- :name get-pages-for-project :? :*
select pg.*
  from pages as pg
 where pg.project = :project
   and pg.deleted_at is null
 order by created_at asc;

-- :name create-page-history :! :n
insert into page_history (id, "user", page, pinned, label, data, version);
values (:id, :user, :page, :pinned :label, :data, :version);

-- :name get-page-history :? :*
select ph.*
  from pages_history as ph
 where ph.user = :user
   and ph.page = :page
   and ph.version < :since
--~ (when (:pinned params) "and ph.pinned = true")
 order by ph.version desc
 limit :max;

-- :name get-page-history-for-project :? :*
select ph.*
  from pages_history as ph
 inner join pages as p
       on (p.id = ph.page)
 where p.project = :project;

-- :name update-page-history :? :*
update pages_history
   set label = :label,
       pinned = :pinned
 where id = :id
   and "user" = :user
returning *;
