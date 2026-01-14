CREATE TABLE http_session_v2 (
  id uuid PRIMARY KEY,

  created_at timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now(),

  profile_id uuid REFERENCES profile(id) ON DELETE CASCADE,
  user_agent text NULL,

  sso_provider_id uuid NULL REFERENCES sso_provider(id) ON DELETE CASCADE,
  sso_session_id text NULL
);

CREATE INDEX http_session_v2__profile_id__idx
    ON http_session_v2(profile_id);

CREATE INDEX http_session_v2__sso_provider_id__idx
    ON http_session_v2(sso_provider_id)
 WHERE sso_provider_id IS NOT NULL;

CREATE INDEX http_session_v2__sso_session_id__idx
    ON http_session_v2(sso_session_id)
 WHERE sso_session_id IS NOT NULL;
