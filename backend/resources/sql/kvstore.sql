-- :name update-kvstore :<! :1
insert into kvstore (key, value, "user")
values (:key, :value, :user)
    on conflict ("user", key)
    do update set value = :value, version = :version
returning *;

-- :name retrieve-kvstore :? :1
select kv.*
  from kvstore as kv
 where kv."user" = :user
   and kv.key = :key;

-- :name delete-kvstore :! :n
delete from kvstore
 where "user" = :user
   and key = :key;
