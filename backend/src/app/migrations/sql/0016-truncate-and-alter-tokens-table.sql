delete from generic_token;
alter table generic_token drop column content;
alter table generic_token add column content jsonb not null;
