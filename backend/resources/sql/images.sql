-- :name create-image-collection :<! :1
insert into images_collections (id, "user", name)
values (:id, :user, :name)
returning *;

-- :name update-image-collection :<! :1
update images_collections
   set name = :name,
       version = :version
 where id = :id
   and "user" = :user
returning *;

-- :name get-image-collections :? :*
select *,
       (select count(*) from images where collection = ic.id) as num_images
  from images_collections as ic
 where (ic."user" = :user or
        ic."user" = '00000000-0000-0000-0000-000000000000'::uuid)
   and ic.deleted_at is null
 order by ic.created_at desc;

-- :name delete-image-collection :! :n
update images_collections
   set deleted_at = clock_timestamp()
 where id = :id
   and "user" = :user;

-- :name get-images-by-collection :? :*
select * from images
 where ("user" = :user or
        "user" = '00000000-0000-0000-0000-000000000000'::uuid)
   and deleted_at is null
   and collection = :collection
order by created_at desc;

-- :name get-images :? :*
select * from images
 where "user" = :user
   and deleted_at is null
   and collection is null
order by created_at desc;

-- :name get-image :? :1
select * from images
 where id = :id
   and deleted_at is null;

-- :name create-image :<! :1
insert into images ("user", name, collection, path, width, height, mimetype)
values (:user, :name, :collection, :path, :width, :height, :mimetype)
returning *;

-- :name update-image :<! :1
update images
   set name = :name,
       collection = :collection,
       version = :version
 where id = :id
   and "user" = :user
returning *;

-- :name delete-image :! :n
update images
   set deleted_at = clock_timestamp()
 where id = :id and "user" = :user
returning *;
