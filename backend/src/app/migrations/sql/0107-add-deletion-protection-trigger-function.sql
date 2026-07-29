CREATE OR REPLACE FUNCTION raise_deletion_protection()
  RETURNS TRIGGER AS $$
  BEGIN
    RAISE EXCEPTION 'unable to proceed to delete row on "%"', TG_TABLE_NAME
          USING HINT = 'disable deletion protection with "SET rules.deletion_protection TO off"';
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;
