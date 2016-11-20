-- :name insert-email :! :n
insert into email_queue (data, priority)
values (:data, :priority);

-- :name get-pending-emails :? :*
select eq.* from email_queue as eq
 where eq.status = 'pending'
   and eq.deleted_at is null
 order by eq.priority desc,
          eq.created_at desc;

-- :name get-immediate-emails :? :*
select eq.* from email_queue as eq
 where eq.status = 'pending'
   and eq.priority = 10
   and eq.deleted_at is null
 order by eq.priority desc,
          eq.created_at desc;

-- :name get-failed-emails :? :*
select eq.* from email_queue as eq
 where eq.status = 'failed'
   and eq.deleted_at is null
   and eq.retries < :max-retries
 order by eq.priority desc,
          eq.created_at desc;

-- :name mark-email-as-sent :! :n
update email_queue
   set status = 'ok'
 where id = :id
   and deleted_at is null;

-- :name mark-email-as-failed :! :n
update email_queue
   set status = 'failed',
       retries = retries + 1
 where id = :id
   and deleted_at is null;

-- :name delete-email :! :n
update email_queue
   set deleted_at = clock_timestamp()
 where id = :id
   and deleted_at is null;
