ALTER TABLE share_link
  ADD COLUMN who_comment text NOT NULL DEFAULT('team'),
  ADD COLUMN who_inspect text NOT NULL DEFAULT('team');

--- TODO: remove flags column in 1.15.x
