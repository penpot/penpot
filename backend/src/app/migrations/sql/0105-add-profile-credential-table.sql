CREATE TABLE profile_passkey (
  id uuid NOT NULL DEFAULT uuid_generate_v4() PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  credential_id bytea NOT NULL,
  attestation bytea NOT NULL,
  sign_count bigint NOT NULL
);

CREATE INDEX profile__passkey__profile_id ON profile_passkey (credential_id, profile_id);

CREATE TABLE profile_challenge (
  profile_id uuid PRIMARY KEY REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  data bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
)
