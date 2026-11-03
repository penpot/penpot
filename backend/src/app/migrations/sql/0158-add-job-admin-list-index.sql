-- Admin jobs list index (`get-jobs`).
--
-- The list pages newest-first (`created_at DESC, id DESC`) with keyset
-- pagination (`(created_at, id) < (?, ?)`), scoped to the current
-- instance tenant and optionally filtered by `status` and `name`.
-- Leading with `(tenant, status)` serves the equality filters, and the
-- trailing `(created_at DESC, id DESC)` delivers the page order for
-- free whenever `status` is filtered (the common case: looking at one
-- state such as failed). The unfiltered view still sorts, but only
-- over the tenant's own rows through the same index.
--
-- Plain CREATE INDEX on purpose: migrations run inside a
-- transaction, so CONCURRENTLY is not available. Same trade-off as
-- every other index in this folder.
--
-- Deploy note: a plain CREATE INDEX takes a write-blocking lock on
-- the table while the index builds. On PRO-sized job tables, deploy
-- this in a low-traffic window.

CREATE INDEX job__admin_list__idx
    ON job (tenant, status, created_at DESC, id DESC);
