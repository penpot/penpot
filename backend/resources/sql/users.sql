-- :name create-profile :<! :1
insert into users (id, fullname, username, email, password, metadata, photo)
values (:id, :fullname, :username, :email, :password, :metadata, '')
returning *;

-- :name get-profile :? :1
select * from users
 where id = :id
   and deleted_at is null;

-- :name get-profile-by-username :? :1
select * from users
 where (username = :username or email = :username)
   and deleted_at is null;

-- :name user-with-username-exists?
select exists
  (select * from users
    where username = :username
--~ (when (:id params) "and id != :id")
    ) as val;

-- :name user-with-email-exists?
select exists
  (select * from users
    where email = :email
--~ (when (:id params) "and id != :id")
    ) as val;

-- :name update-profile :<! :1
update users
   set username = :username,
       email = :email,
       fullname = :fullname,
       metadata = :metadata
 where id = :id
   and deleted_at is null
returning *;

-- :name update-profile-password :! :n
update users
   set password = :password
 where id = :id
   and deleted_at is null

-- :name update-profile-photo :! :n
update users
   set photo = :photo
 where id = :id
   and deleted_at is null

-- :name create-recovery-token :! :n
insert into user_pswd_recovery ("user", token)
values (:user, :token);

-- :name get-recovery-token
select * from user_pswd_recovery
 where used_at is null
   and token = :token;

-- :name recovery-token-exists? :? :1
select exists (select * from user_pswd_recovery
                where used_at is null
                  and token = :token) as token_exists;

-- :name mark-recovery-token-used :! :n
update user_pswd_recovery
   set used_at = clock_timestamp()
 where token = :token;
