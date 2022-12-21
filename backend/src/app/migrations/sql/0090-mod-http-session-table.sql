ALTER TABLE http_session DROP CONSTRAINT http_session_pkey;
ALTER TABLE http_session ADD CONSTRAINT http_session_pkey PRIMARY KEY (id);
