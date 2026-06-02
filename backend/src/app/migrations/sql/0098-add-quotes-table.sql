CREATE TABLE usage_quote (
  id uuid NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
  target text NOT NULL,
  quote bigint NOT NULL,

  profile_id uuid NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  project_id uuid NULL REFERENCES project(id) ON DELETE CASCADE DEFERRABLE,
  team_id uuid NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  file_id uuid NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE
);

ALTER TABLE usage_quote
  ALTER COLUMN target SET STORAGE external;

CREATE INDEX usage_quote__profile_id__idx ON usage_quote(profile_id, target);
CREATE INDEX usage_quote__project_id__idx ON usage_quote(project_id, target);
CREATE INDEX usage_quote__team_id__idx ON usage_quote(team_id, target);

-- DROP TABLE IF EXISTS usage_quote_test;
-- CREATE TABLE usage_quote_test (
--   id bigserial NOT NULL PRIMARY KEY,
--   target text NOT NULL,
--   quote bigint NOT NULL,

--   profile_id bigint NULL,
--   team_id bigint NULL,
--   project_id bigint NULL,
--   file_id bigint NULL
-- );

-- ALTER TABLE usage_quote_test
--   ALTER COLUMN target SET STORAGE external;

-- CREATE INDEX usage_quote_test__profile_id__idx ON usage_quote_test(profile_id, target);
-- CREATE INDEX usage_quote_test__project_id__idx ON usage_quote_test(project_id, target);
-- CREATE INDEX usage_quote_test__team_id__idx ON usage_quote_test(team_id, target);
-- -- CREATE INDEX usage_quote_test__target__idx ON usage_quote_test(target);

-- DELETE FROM usage_quote_test;

-- INSERT INTO usage_quote_test (target, quote, profile_id, team_id, project_id)
-- SELECT 'files-per-project', 50*RANDOM(), 2000*RANDOM(), null, null
--   FROM generate_series(1, 5000);

-- INSERT INTO usage_quote_test (target, quote, profile_id, team_id, project_id)
-- SELECT 'files-per-project', 200*RANDOM(), 300*RANDOM(), 300*RANDOM(), null
--   FROM generate_series(1, 1000);

-- INSERT INTO usage_quote_test (target, quote, profile_id, team_id, project_id)
-- SELECT 'files-per-project', 100*RANDOM(), 300*RANDOM(), null, 300*RANDOM()
--   FROM generate_series(1, 1000);

-- INSERT INTO usage_quote_test (target, quote, profile_id, team_id, project_id)
-- SELECT 'files-per-project', 100*RANDOM(), 300*RANDOM(), 300*RANDOM(), 300*RANDOM()
--   FROM generate_series(1, 1000);

-- INSERT INTO usage_quote_test (target, quote, profile_id, team_id, project_id)
-- SELECT 'files-per-project', 30*RANDOM(), null, 2000*RANDOM(), null
--   FROM generate_series(1, 5000);

-- INSERT INTO usage_quote_test (target, quote, profile_id, team_id, project_id)
-- SELECT 'files-per-project', 10*RANDOM(), null, null, 2000*RANDOM()
--   FROM generate_series(1, 5000);

-- VACUUM ANALYZE usage_quote_test;

-- select * from usage_quote_test
--  where target = 'files-per-project'
--    and profile_id = 1
--    and team_id is null
--    and project_id is null;

-- select * from usage_quote_test
--  where target = 'files-per-project'
--    and ((team_id = 1 and (profile_id = 1 or profile_id is null)) or
--         (profile_id = 1 and team_id is null and project_id is null));

-- select * from usage_quote_test
--  where target = 'files-per-project'
--    and ((project_id = 1 and (profile_id = 1 or profile_id is null)) or
--         (team_id = 1 and (profile_id = 1 or profile_id is null)) or
--         (profile_id = 1 and team_id is null and project_id is null));
