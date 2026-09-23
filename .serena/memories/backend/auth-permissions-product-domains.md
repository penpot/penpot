# Backend Auth, Permissions, and Product Domain Subtleties

## Auth and sessions

- Main auth RPC commands live in `app.rpc.commands.auth`; LDAP and OIDC provider logic live in `app.auth.ldap` and `app.auth.oidc`, with LDAP-specific RPC checks in `app.rpc.commands.ldap`.
- Public auth endpoints must explicitly set `::rpc/auth false`; RPC auth defaults to enabled. Session cookie creation/deletion is usually attached as an RPC response transform.
- Basic Penpot registration is token staged: prepare/register creates or verifies temporary tokens, then profile creation/session setup is reused by other auth backends. The frontend `/auth/verify-token` flow is a hub for registration confirmation, email change, and invitation tokens.
- OIDC-compatible providers share a generic flow: redirect to provider, validate callback/request token, fetch identity data, then login an existing profile or register a new one. Known providers may have hardcoded endpoints; generic OIDC can use discovery/configured endpoints.
- Generic OIDC authorization stores a trusted alternate `Origin` (request `:app.http/trusted-origin`, see `mem:backend/subtleties`) as the `:origin` claim in the signed state; the callback re-applies it to the config public URI so the token-exchange `redirect_uri` and redirect targets stay on the alternate domain. Organization SSO mirrors this through `build-organization-sso-auth-redirect-uri :origin`.
- LDAP login validates credentials against the external directory, fetches identity data, then logs in or registers a matching Penpot profile. LDAP registration is not a separate Penpot signup flow.
- LDAP session identity MUST come from the directory-returned email (`info.email`): the profile matching the typed email can differ (aliases, UPNs, multi-valued `mail` attributes) and is used only for lockout checks, never to bind the session.
- Account lockout (flag `:account-lockout`, `app.auth.login-lockout`) is Redis-backed and keyed per profile id. Password and LDAP flows check/increment on the profile derived from the typed email and clear on the profile that actually logs in.
- Lockout and RPC rate limits share the `app.http.errors/handle-error :rate-limit` HTTP path: status 429, body `{:type :rate-limit :code ... :hint ... :ttl ...}`, and any supplied `::http/headers` preserved. Account lockout raises `:code :account-locked` and, when it carries a non-nil `:ttl` (seconds), the handler adds `retry-after`. The RPC limiter (`app.rpc.rlimit`) raises `:code :request-blocked` and sets `retry-after` itself in `::http/headers` (seconds until the longest rejecting limit resets), alongside `x-rate-limit-remaining`/`x-rate-limit-reset`. CORS (`app.http.middleware/with-cors-headers`) exposes `content-type`, `retry-after`, and both `x-rate-limit-*` headers. External flags: `enable-account-lockout`, `enable-rpc-rlimit`.
- Logout may return an OIDC provider redirect URI when the session claims include provider/session data and the provider has a logout URI.
- Invitation tokens are verified through token issuers and only accepted when the token member id/email matches the authenticated profile; otherwise login proceeds without consuming the invitation.
- HTTP/session parsing details such as cookie/header precedence, JWT session token versions, and SameSite behavior are in `mem:backend/subtleties`.

## Permission model

- `app.rpc.permissions` provides predicate/check factories. Failed permission checks intentionally raise `:not-found` / `:object-not-found`, not an authorization-specific error, to avoid leaking object existence.
- Team role flags are normalized as owner > admin > editor > viewer. Owner/admin imply edit; any membership row implies read.
- File/project/comment checks are implemented in the owning command namespaces, often via helpers imported from `files`, `teams`, or `projects`; do not bypass those helpers with direct DB lookups unless preserving their not-found semantics.
- Comment permission includes both logged-in state and the file/team comment policy. Shared viewer paths may pass `share-id`; preserve that path when changing comment queries.

## Teams, projects, and invitations

- Team/project commands mix DB changes, email, message bus notifications, media/storage cleanup, feature flags, quotas, and audit metadata. Keep mutations transactional when the existing command does so.
- Invitation flows validate muted/bounced emails before sending and use tokenized invitation state. Accepting an invitation is tied to the invited member identity, not just possession of a token.
- Logical deletion is used for many product objects; prefer existing logical-deletion helpers over hard deletes unless the command already performs permanent cleanup.
- Bounced/spam-complaint emails can mute/block a profile for login/registration and email sending. Devenv Mailpit is the normal local path for registration/email-flow testing.

## Comments, webhooks, and audit

- Comment thread queries join file/project/profile state and exclude deleted files/projects. Unread comment counts depend on `comment_thread_status.modified-at` and profile notification preferences.
- Webhook edits are allowed for team editors/admins or the webhook creator. Webhook validation performs a synchronous HEAD request with a short timeout; validation errors are mapped through `app.loggers.webhooks`.
- Audit events are prepared from RPC metadata, result metadata, params, request context, and selected auth identifiers. Webhook event batching can be controlled through audit/webhook metadata on commands or results.
- Webhook and audit logging are cross-cutting side effects of product commands; when adding a command, check nearby command metadata and result metadata patterns before inventing a new event shape.

## Local testing notes

- Enable LDAP login locally with frontend flag `enable-login-with-ldap`; the devenv includes a configured test LDAP service.
- OIDC testing requires external provider app credentials plus matching backend/frontend config.
- Backend domain tests usually live under `backend/test/backend_tests/rpc/commands/*_test.clj` or nearby backend test namespaces. Use focused `clojure -M:dev:test --focus ...` from `backend/` when possible.
- For auth/session or HTTP behavior, combine backend tests with the HTTP/session notes in `mem:backend/subtleties` because RPC-level tests may not exercise cookie/header transforms.
