-- :name get-image-collection :? :1
select *
  from images_collections as cc
 where cc.id = :id
   and cc."user" = '00000000-0000-0000-0000-000000000000'::uuid;

-- :name create-image :<! :1
insert into images ("user", id, name, collection, path, width, height, mimetype)
values ('00000000-0000-0000-0000-000000000000'::uuid,
        :id, :name, :collection, :path, :width, :height, :mimetype)
returning *;

-- :name delete-image :! :n
delete from images
 where id = :id
   and "user" = '00000000-0000-0000-0000-000000000000'::uuid;

-- :name create-images-collection
insert into images_collections (id, "user", name)
values (:id, '00000000-0000-0000-0000-000000000000'::uuid, :name)
    on conflict (id)
    do update set name = :name
returning *;

-- :name get-image
select * from images as i
 where i.id = :id
   and i."user" = '00000000-0000-0000-0000-000000000000'::uuid;

-- :name create-icons-collection
insert into icons_collections (id, "user", name)
values (:id, '00000000-0000-0000-0000-000000000000'::uuid, :name)
    on conflict (id)
    do update set name = :name
returning *;

-- :name get-icon
select * from icons as i
 where i.id = :id
   and i."user" = '00000000-0000-0000-0000-000000000000'::uuid;

-- :name create-icon :<! :1
insert into icons ("user", id, name, collection, metadata, content)
values ('00000000-0000-0000-0000-000000000000'::uuid,
        :id, :name, :collection, :metadata, :content)
    on conflict (id)
    do update set name = :name,
                  content = :content,
                  metadata = :metadata,
                  collection = :collection,
                  "user" = '00000000-0000-0000-0000-000000000000'::uuid
returning *;

