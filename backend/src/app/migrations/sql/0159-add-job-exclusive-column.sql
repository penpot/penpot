-- Exclusive jobs: at most one live job per key.
--
-- A job submitted with `::jobs/skip-if-active` is marked as exclusive,
-- and the database admits a single live row (new, scheduled, running or
-- retry) for each (tenant, name, queue, label). The submit no longer
-- counts the live jobs before inserting: the unique index decides, so
-- the exclusion holds across backends and does not depend on a check
-- racing an insert.
--
-- `exclusive` is false by default, so the rows that only want to be
-- deduplicated (a webhook delivery, a notification) keep sharing their
-- label: the index does not cover them, and they do not block an
-- exclusive submit either. The label stays a label.
--
-- The index is partial over the live statuses: a terminal row leaves
-- it, so the next submit of the same key is admitted. Every row that
-- exists today has `exclusive` false, so the index starts empty and no
-- cleanup of duplicates is needed.

ALTER TABLE job
    ADD COLUMN exclusive boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX job__live_exclusive__uniq
    ON job (tenant, name, queue, label)
    WHERE exclusive
      AND status IN ('new', 'scheduled', 'running', 'retry');
