-- Legacy v1 HTTP sessions have been removed from the backend; the
-- http_session table is no longer read or written by any code path.

DROP TABLE http_session;
