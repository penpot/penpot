# Backend Session Expiration

## Config

- `:auth-token-cookie-name` / `PENPOT_AUTH_TOKEN_COOKIE_NAME` (default `auth-token`): cookie name.
- `:auth-token-cookie-max-age` / `PENPOT_AUTH_TOKEN_COOKIE_MAX_AGE` (default 7d): idle window; drives the sliding cookie `Expires` and the GC idle threshold.
- `:auth-token-cookie-max-age-absolute` / `PENPOT_AUTH_TOKEN_COOKIE_MAX_AGE_ABSOLUTE` (default 30d): hard cap from `created-at`; drives the token `:exp` and the GC absolute threshold.
- Durations decode as `<n><unit>` (`7d`, `168h`, `30m`) via `ct/schema:duration`.
- Session lifetime defaults live only as code constants in `session.clj` (`default-cookie-max-age`, `default-cookie-max-age-absolute`); do not duplicate them in the `config.clj` default map. Call sites always pass the constant as the `cf/get` fallback.

## Token and session model

- Sessions live only in `http_session_v2`. Legacy v1 support (`http_session`, string ids, `:ver 0` tokens) was removed and the table dropped (`0153-drop-http-session-table`).
- `assign-token` emits header `{:kid 1 :ver 1}` with claims `:sid`, `:iat` (= `modified-at`) and `:exp` = `created-at + absolute-max-age` (omitted only when `created-at` is nil, which neither manager produces today; the branch is defensive).
- `:exp` is anchored to `created-at`, never `modified-at`, so renewal cannot extend the absolute maximum.
- Tokens issued before `:exp` existed carry no `:exp`; they are still bounded by the GC's `created_at` condition and acquire `:exp` on their next renewal (self-healing, no operator action).
- `wrap-authz` resolves the session by `(:sid claims)` only; there is no fallback to reading a session by the raw token string.
- `middleware/wrap-auth` attaches `::http/auth-data` only for `kid=1`/`ver=1` tokens with a configured decoder; anything else stays unauthenticated.
- Renewal fires when `modified-at` is older than 6h (`default-renewal-max-age`, not configurable). It `UPDATE`s the same row (`modified-at` only) and issues a new token that keeps the original `:exp`.
- `read-session` does not check age. Idle expiration is enforced only by the GC, so a copied token stays valid until the next GC run deletes its row (up to ~24h of grace).

## GC (`::tasks/gc`, cron `session-gc`, daily)

- Deletes from `http_session_v2` where `modified_at < now - :auth-token-cookie-max-age` **or** `created_at < now - :auth-token-cookie-max-age-absolute`.
- The two thresholds are separate task params (`::tasks/max-age`, `::tasks/max-age-absolute`) built in `ig/expand-key`; both must be durations.
- Do not collapse the two conditions: `modified_at` alone never collects active sessions; `created_at` alone logs out active users at the idle window.

## Related lifetimes

- Organization SSO entries in session `:props` (`:sso {org-id exp}`) last 4h, hardcoded in `app.auth.oidc`.
- Access tokens (`app.rpc.commands.access-token`) have a user-chosen `:expires-at` (Never/30/60/90/180d), independent from HTTP sessions.
- Cookie `SameSite`/`secure` depend on `:cors`, `:strict-session-cookies`, `:secure-session-cookies`; `parse-flags` auto-adds `:disable-secure-session-cookies` for non-localhost HTTP `public-uri`.
- Without `PENPOT_SECRET_KEY`, derived subsystem keys change on restart and all sessions/invitations are invalidated.
- Read-only DB pool: sessions fall back to `inmemory-manager`, lost on restart, no GC.
- HTTP middleware and cookie/header precedence details: `mem:backend/http-storage-filedata-subtleties`.
