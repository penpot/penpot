CREATE TABLE sso_provider (
  id uuid PRIMARY KEY,

  created_at timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now(),

  is_enabled boolean NOT NULL DEFAULT true,

  type text NOT NULL CHECK (type IN ('oidc')),
  domain text NOT NULL,

  client_id text NOT NULL,
  client_secret text NOT NULL,

  base_uri text NOT NULL,
  token_uri text NULL,
  auth_uri text NULL,
  user_uri text NULL,
  jwks_uri text NULL,
  logout_uri text NULL,

  roles_attr text NULL,
  email_attr text NULL,
  name_attr text NULL,
  user_info_source text NOT NULL DEFAULT 'token'
     CHECK (user_info_source IN ('token', 'userinfo', 'auto')),

  scopes text[] NULL,
  roles text[] NULL
);

CREATE UNIQUE INDEX sso_provider__domain__idx
    ON sso_provider(domain);
