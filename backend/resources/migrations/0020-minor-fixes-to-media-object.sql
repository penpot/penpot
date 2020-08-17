alter table media_object drop column modified_at;
alter index image_pkey rename to media_object_pkey;
alter index image__file_id__idx rename to media_bject__file_id__idx;
alter table media_object rename constraint image_file_id_fkey to media_object_file_id_fkey;
alter trigger image__on_delete__tgr on media_object rename to media_object__on_delete__tgr;
drop trigger image__modified_at__tgr on media_object;


