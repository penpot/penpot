ALTER TABLE share_link
  ADD COLUMN who_comment text NOT NULL DEFAULT('team'),
  ADD COLUMN who_inspect text NOT NULL DEFAULT('team'),
  DROP COLUMN flags;
