DELETE FROM share_link 
  WHERE created_at not in 
    (SELECT max(created_at) FROM share_link GROUP BY file_id);
