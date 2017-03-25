DROP TRIGGER page_on_update_tgr ON pages;

CREATE OR REPLACE FUNCTION handle_page_update()
  RETURNS TRIGGER AS $pagechange$
  BEGIN
    --- Update projects modified_at attribute when a
    --- page of that project is modified.
    UPDATE projects SET modified_at = clock_timestamp()
      WHERE id = OLD.project;

    RETURN NEW;
  END;
$pagechange$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION handle_page_history()
  RETURNS TRIGGER AS $pagehistory$
  BEGIN
    INSERT INTO pages_history (page, "user", created_at,
                               modified_at, data, version)
      VALUES (NEW.id, NEW."user", NEW.modified_at,
              NEW.modified_at, NEW.data, NEW.version);

    RETURN NEW;
  END;
$pagehistory$ LANGUAGE plpgsql;


CREATE TRIGGER page_on_insert_tgr
  AFTER INSERT ON pages
  FOR EACH ROW
  EXECUTE PROCEDURE handle_page_history();

CREATE TRIGGER page_on_update_tgr
  AFTER UPDATE ON pages
  FOR EACH ROW
  EXECUTE PROCEDURE handle_page_update();

CREATE TRIGGER page_on_update_history_tgr
  AFTER UPDATE ON pages
  FOR EACH ROW
  WHEN (OLD.data IS DISTINCT FROM NEW.data)
  EXECUTE PROCEDURE handle_page_history();
