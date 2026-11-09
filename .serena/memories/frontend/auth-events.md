# Auth Events and the Session Origin

`signin` is the frontend `identify` event emitted once per session from
`logged-in` (`app.main.data.auth`). It carries the profile plus a closed
vocabulary that says why the session started.

## Vocabulary

- `source`: `login` | `register`. Always present.
- `auth-backend`: `penpot` | `ldap` | `google` | `github` | `gitlab` | `oidc`.
  Same key the profile already uses. Always present.
- `activation`: `immediate` | `email-verification`. Only on signups.
- `from-invitation`: boolean. Always present in `signin`
  (`(some? (:invitation-token profile))`); `auth-origin` omits it when `nil`.

`auth-origin` (public, validated against `schema:auth-origin`) drops the nil
fields and checks with `(assert (sm/check ...))`: it throws in development and
tests, and `:elide-asserts true` removes it from the release build, so a bad
value can never break a real login. Emitters never call it directly.

## Where each value comes from

- `source` and `activation` travel as profile metadata (`::ev/source`,
  `::ev/activation`), written by `origin-meta`. `login-from-token` derives both
  from the token issuer with `iss->origin`: `:auth` → `login`; `:verify-email` →
  `register` + `email-verification`. An unknown `:iss` produces no metadata, so
  the assertion fires in development and the event goes without `:source` in
  production.
- `auth-backend` and `from-invitation` are read from the profile.
- `profile-origin` composes the four and `logged-in` merges them into the event;
  the emitters only declare the fact.

| Flow | `source` | `activation` | `from-invitation` |
|---|---|---|---|
| Password login | login | | false |
| Password login from an invitation link | login | | true |
| LDAP login | login | | false |
| Social login, existing active profile | login | | false |
| Signup, email verification disabled | register | immediate | false |
| Signup, email verification enabled | register | email-verification | false |
| Social signup, active profile | register | immediate | false |
| Social signup, inactive profile | register | email-verification | false |
| Signup from an invitation link, email verification disabled | register | immediate | true |
| Signup from an invitation link, email verification enabled | register | email-verification | true |
| Social login from an invitation link | login | | true |
| Social signup from an invitation link, email verification enabled | register | email-verification | true |
| LDAP login from an invitation link | login | | true |

LDAP is the explicit exception: the directory provisions the account on the
first login, but there is no user signup, so the flow is always `login` and
never carries `activation`. Do not "fix" it by inferring a signup.

## Invitations go through `logged-in`

- `login-from-register` accepts an optional `:invitation-token`;
  `with-register-origin` decorates the profile and `logged-in` does the rest.
  The four places that used to navigate by hand to `:auth-verify-token`
  (`ui/auth/register.cljs` twice, `ui/static.cljs`, `ui/auth/login_modal.cljs`)
  emit `login-from-register` with the token. The modal used to live in
  `ui/viewer/login.cljs`; it was extracted to the shared
  `ui/auth/login_modal.cljs` (`login-register-modal*`, also used by
  `ui/mcp_authorize.cljs`).
- The invitation navigates through `get-invitation-nav`, which does not wait for
  `::dtm/teams-fetched`: `fetch-teams` has no error handling, so depending on it
  would leave the invitation unaccepted when `get-teams` fails. For invited
  profiles `get-redirect-events` emits `rx/empty`, so the teams path cannot move
  the user away from the token verification screen.
- The OIDC callback with an invitation mints an `:auth` token that carries the
  invitation JWE in its claims (`backend/src/app/auth/oidc.clj`), so the
  frontend follows the regular path (`login-from-token` → `logged-in`). That
  flow visits the token verification screen twice.
- `ui/auth/login_modal.cljs` (the register modal: `:is-active` branch calls
  `on-close`, or `on-register` when the caller passes one, as in the MCP
  authorize flow) and `ui/static.cljs` (`rt/reload true`) `:is-active`
  branches still bypass `logged-in`; they are a separate decision, not an
  oversight.

## Backend side

- The `register-profile` success branches
  (`backend/src/app/rpc/commands/auth.clj`) put `activation` in the event props
  and emit **no** `action`: the name already says it is a signup, and a constant
  action would only add noise. The retry branches
  (`register-profile-retry`) keep `action` in `context`
  (`ignore-because-blocked`, `ignore`, `resend-email-verification`,
  `ignore-because-complaints`), where it distinguishes outcomes.
- The LDAP invitation branch of `login-with-ldap` returns the full profile plus
  `:invitation-token`; a partial map would break the `auth-origin` assertion.
- `register-profile` does not accept the invitation: it mints the session and
  returns the token. The acceptance is `accept-team-invitation`, emitted from
  `verify-token`.
- `filter-telemetry-props` keeps `source` and `activation` in the anonymised
  row; `:action` is not on the allowlist.

## Analytics

- `register-profile` is a track event, so the props adapter forwards
  `activation` with no extra work.
- `signin` is an identify event built from a closed list of profile keys. It
  only reaches analytics if the identify branch of Nexus forwards `source`,
  `activation` and `from_invitation` (`external/nexus`, another repository).

## `::ev/source` is no longer dead metadata

It is read by `profile-origin`. Do not remove it as unused, and keep the
vocabulary in sync with `schema:auth-origin` and the table above.
