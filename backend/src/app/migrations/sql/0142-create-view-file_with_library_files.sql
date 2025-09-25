create or replace view file_with_library_files as
select f.id,
       coalesce(string_agg(flr.library_file_id::text, ','), '') as library_file_ids
from file f
left join file_library_rel flr on flr.file_id = f.id
group by f.id
order by f.id;
