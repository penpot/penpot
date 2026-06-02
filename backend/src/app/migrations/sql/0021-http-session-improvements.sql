alter table http_session drop constraint http_session_pkey;
alter table http_session add primary key (id, profile_id);
alter table http_session drop column modified_at;
drop index http_session__profile_id__idx;
