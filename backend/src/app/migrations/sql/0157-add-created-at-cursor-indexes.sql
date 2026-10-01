-- Cursor indexes for the admin panel lists (`get-profiles`,
-- `get-teams`, `get-projects`, `get-files`).
--
-- All four lists share one order (`created_at DESC, id DESC`) with
-- keyset pagination (`(created_at, id) < (?, ?)`). Without these
-- indexes every page rebuilds a full sort of the table, which stops
-- working on PRO-sized tables (hundreds of thousands of rows).
--
-- Plain CREATE INDEX on purpose: migrations run inside a
-- transaction, so CONCURRENTLY is not available. Same trade-off as
-- every other index in this folder; the lists are admin-only and the
-- build is a one-off at deploy.
--
-- The pre-existing partial `*_deleted_at_idx` indexes
-- (WHERE deleted_at IS NOT NULL) keep serving the "only deleted"
-- filter; these full indexes serve all three deleted-filter modes
-- with the deleted check applied per row during the scan.
--
-- Deploy note: a plain CREATE INDEX takes a write-blocking lock on
-- each table while its index builds. On PRO-sized tables, deploy
-- this in a low-traffic window.

CREATE INDEX profile__admin_list__idx
    ON profile (created_at DESC, id DESC);

CREATE INDEX team__admin_list__idx
    ON team (created_at DESC, id DESC);

CREATE INDEX project__admin_list__idx
    ON project (created_at DESC, id DESC);

CREATE INDEX file__admin_list__idx
    ON file (created_at DESC, id DESC);
