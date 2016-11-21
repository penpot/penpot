-- :name create-icon-collection :<! :1
insert into icons_collections (id, "user", name)
values (:id, :user, :name)
returning *;

-- :name update-icon-collection :<! :1
update icons_collections
   set name = :name,
       version = :version
 where id = :id
   and "user" = :user
returning *;

-- :name get-icon-collections :? :*
select *,
       (select count(*) from icons where collection = ic.id) as num_icons
  from icons_collections as ic
 where (ic."user" = :user or
        ic."user" = '00000000-0000-0000-0000-000000000000'::uuid)
   and ic.deleted_at is null
 order by ic.created_at desc;

-- :name delete-icon-collection :! :n
update icons_collections
   set deleted_at = clock_timestamp()
 where id = :id and "user" = :user;

-- :name get-icons-by-collection :? :*
select *
  from icons as i
 where (i."user" = :user or
        i."user" = '00000000-0000-0000-0000-000000000000'::uuid)
   and i.deleted_at is null
   and i."collection" = :collection
 order by i.created_at desc;

-- :name get-icons :? :*
select * from icons
 where "user" = :user
   and deleted_at is null
   and collection is null
order by created_at desc;

-- :name get-icon :? :1
select * from icons
 where id = :id
   and deleted_at is null
   and ("user" = :user or
        "user" = '00000000-0000-0000-0000-000000000000'::uuid);

-- :name create-icon :<! :1
insert into icons ("user", name, collection, metadata, content)
values (:user, :name, :collection, :metadata, :content)
returning *;

-- :name update-icon :<! :1
update icons
   set name = :name,
       collection = :collection,
       version = :version
 where id = :id
   and "user" = :user
returning *;

-- :name delete-icon :! :n
update icons
   set deleted_at = clock_timestamp()
 where id = :id
   and "user" = :user;
