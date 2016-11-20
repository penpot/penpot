-- :name acquire-task :? :1
with recursive locked_tasks as (
  select (j).*, pg_try_advisory_lock((j).id) as locked
    from (
      select j
        from tasks as j
       where queue = :queue
         and status = 'pending'
         and created_at <= now()
       order by created_at, id
       limit 1
    ) as t1
  union all (
    select (j).*, pg_try_advisory_lock((j).id) as locked
      from (
        select (
          select j
            from tasks as j
           where queue = :queue
             and status = 'pending'
             and created_at <= now()
             and (created_at, id) > (locked_tasks.created_at, locked_tasks.id)
          order by created_at, id
          limit 1
        ) as j
        from locked_tasks
        where locked_tasks.id is not null
        limit 1
      ) as t1
  )
)
select id, status, error, created_at
  from locked_tasks
 where locked
 limit 1;

-- :name create-task :? :1
insert into tasks (queue)
values (:queue)
returning *;

-- :name mark-task-done
update tasks
   set status = 'completed',
       completed_at = clock_timestamp()
 where id = :id;

-- :name mark-task-failed
update tasks
   set status = 'failed',
       error = :error,
       completed_at = clock_timestamp()
 where id = :id;
