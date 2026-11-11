# MCP OAuth

## Contract and scope

- MCP-specific authorization; existing Penpot login identifies the user on the approval page. No application vetting or persistent consent policy. Access requires user approval; registration alone grants nothing.
- Return the existing fixed MCP user token. On approval, enable `:mcp-enabled` and create the token only if no usable one exists; preserve an existing usable token. A token is unusable, and replaced, when expired (settings can give MCP tokens an expiry), when its JWT issuer is not `urn:penpot:mcp-token` (tokens issued before MCP tokens had their own issuer), or when this instance cannot decrypt it (e.g. secret key changed); the backend rejects all three. No refresh token, `expires_in`, or new token lifetime.
- No new client database table or schema migration. Client IDs must remain usable across separate authorization flows without server-side registration storage. Do not replace them with expiring Redis registrations.
- MCP transport continues matching user tokens to connected Penpot plugins; no new per-request token introspection or independent semantic validation. The logged-in Penpot instance remains the content-access boundary. Do not silently add resource-server validation or change token semantics.
- README scope: user/developer usage instructions; document `PENPOT_MCP_AUTH_URI` in its environment-variable table. Keep OAuth architecture and protocol details here and non-obvious invariants in source comments.

## Deployment and discovery

- MCP process: `PENPOT_MCP_AUTH_URI=https://penpot.example/api/mcp/oauth` with `--multi-user`. Unset/empty disables OAuth; configured OAuth requires multi-user. Absolute HTTPS, or HTTP on loopback for development; no userinfo, query, or fragment. Invalid configuration fails startup; startup logs OAuth status.
- Devenv: `mcp/scripts/start-mcp-devenv-oauth` starts multi-user mode with `PENPOT_MCP_AUTH_URI` derived from `PENPOT_PUBLIC_URI`. Manual opt-in only: agentic tooling needs the single-user `start-mcp-devenv`.
- Public MCP transport: `<Penpot public URI>/mcp/stream`, proxied to Node `/mcp`. Missing credentials with OAuth enabled: 401 plus `WWW-Authenticate` referencing `<auth base>/resource` and scope `mcp`.
- Bearer header takes precedence over legacy `?userToken=`. Malformed headers do not fall back to the query token. Existing query-token clients remain supported.
- Protected-resource metadata: `/api/mcp/oauth/resource`; issuer: `/api/mcp/oauth`; authorization-server metadata: `/.well-known/oauth-authorization-server/api/mcp/oauth`. All reach the backend. Bundled devenv/production nginx configs forward the well-known path; custom proxies must do likewise. Keep Penpot public URI and external URLs consistent.
- Node implementation: `mcp/packages/server/src/McpAuthentication.ts`, integrated by `PenpotMcpServer.ts`; OAuth-enabled tools advertise `_meta.securitySchemes`.

## Client identification

- `backend/src/app/mcp/oauth_clients.clj`: dynamic registration at `/api/mcp/oauth/register`, HTTPS client metadata documents (CIMD), and registrations obtained in advance via `(app.mcp.oauth-clients/register! {} metadata)`.
- DCR returns `penpot-mcp-v1.` plus unpadded base64url JSON: normalized callback URLs, optional name, authentication method, random nonce, optional secret hash. Fixed grant/response/scope values are reconstructed. Nonce distinguishes registrations with otherwise identical metadata.
- IDs are public, unsigned, unencrypted, non-expiring metadata; validate on every resolution. Client secrets are generated randomly and returned separately; only the hash enters the ID. No signing/encryption key or client record in PostgreSQL/Redis. Old experimental database-backed UUID IDs require registration again.
- An edited payload changes the ID. Authorization-code exchange compares the exact ID string against the approved request before resolving metadata. Do not replace this with comparison of decoded fields; code ownership and PKCE binding must survive metadata edits.
- Supported authentication: `none`, `client_secret_basic`, `client_secret_post`; no `private_key_jwt`. CIMD selects public-client `none` from the document's supported methods; other listed methods do not imply support here. Scope `mcp`; grant `authorization_code`; response `code`. CIMD may advertise additional grants (e.g. Codex lists `refresh_token`); require `authorization_code` support and normalize to the grant Penpot implements, rather than reject broader client capabilities.
- Entire ID capped at 2,048 ASCII characters. DCR returns `invalid_client_metadata` if encoded metadata exceeds this; shorter/fewer callback URLs reduce size. OAuth defines no fixed ID-length maximum (RFC 6749 §2.2); stateless IDs are explicitly described by RFC 7591 §A.5.2. Installed MCP client SDK 2.3.1 accepts a 2,048-character ID; this does not establish Codex/Claude compatibility.
- Redirect matching is exact except loopback HTTP port changes for native clients (`127.0.0.1`, `[::1]`, exact `localhost`). Host, path and query must still match; reject fragments/userinfo and lookalike localhost domains. Allow HTTPS, loopback HTTP, and reverse-domain native schemes.
- CIMD: fetch with existing SSRF-checked `app.http.client/req`, no redirect following, require matching document `client_id`, bounded 64-KiB body and request/body-read timeouts. Cache max 256 entries/one minute, further limited by HTTP max-age; no-store/no-cache/no max-age means no reuse.

## Approval and exchange

- `backend/src/app/http/mcp_oauth.clj`: separate router branch from ordinary RPC middleware; preserve OAuth snake_case JSON and form encoding. Session auth applies to approval request/decision only. Public metadata/register/token endpoints allow noncredentialed CORS; MCP transport CORS was not added.
- Errors: every failure answers with an OAuth `{"error": ...}` body, never the shared Penpot error shape. Failures without `:oauth-error` are also passed to `app.http.errors/handle` for logging only; its response is discarded. `wrap-errors` also wraps the session middleware of `/request` and `/decision`. Safe to log because these routes never set request `:params`.
- `/authorize`: validate client/callback, `code`, PKCE S256, scope/resource/state; store pending request for 600 seconds; redirect to `?screen=mcp-authorize&request=<opaque handle>`.
- `frontend/src/app/main/ui/mcp_authorize.cljs`: existing login with return URL; show application and account details; submit explicit Allow/Cancel. No fixed MCP token in frontend state or browser redirect. Approval refreshes profile and broadcasts MCP enablement; does not connect a plugin automatically.
- `/request`: first authenticated read binds request to profile plus hashed login-session ID and CSRF value, using compare-and-replace. Re-read after the attempt: concurrent GETs may win/lose binding; only the matching session may proceed.
- `/decision`: check exact Origin, session binding and CSRF, then atomically consume the request. Denial creates no token/enables nothing. Approval calls transactional `access-token/ensure-mcp-access!`, then stores code for 120 seconds. Code state holds token row ID, never the fixed token. Callback echoes OAuth `state` and RFC 9207 `iss`.
- `/token`: validate grant, exact client ID/callback/resource and PKCE; resolve current client metadata/authentication method and verify any secret; atomically consume validated code; fetch the unexpired token by original row ID/profile/type. Regeneration deletes the old token row, so a pending code cannot silently yield the replacement token. Return fixed `access_token`, `token_type: Bearer`, scope `mcp`.
- `backend/src/app/mcp/oauth_state.clj` and Lua: tenant/kind/hash-prefixed keys; random handles; SET NX EX must succeed before returning handle. Reads retain raw JSON for atomic comparison. Consume/delete and bind/replace compare the validated raw record in Lua; replacement preserves TTL. Redis failures fail closed. Backend Redis is required independently of Node's optional Redis plugin bridge.
- `backend/src/app/rpc/commands/access_token.clj`: `ensure-mcp-access!` decodes the stored token with `ex/ignoring` to apply the usable-token rule above. Lock profile row in both MCP generation and OAuth provisioning, including when no token exists. Serializes simultaneous approvals and settings regeneration. Provisioning must run within a transaction.

## Verification and limits

- Tests: `mcp_oauth_clients_test.clj`, `mcp_oauth_http_test.clj`, `mcp_oauth_state_test.clj`, OAuth provisioning in `rpc_access_tokens_test.clj`; MCP authentication/HTTP tests; frontend approval tests. Cover no-storage registration, size boundaries, edited-ID rejection, code replay/concurrency, session/CSRF binding, and fixed-token reuse.
- Live OpenAI/Codex/Claude compatibility and marketplace acceptance remain unverified. In particular, verify token persistence without `expires_in` and acceptance of the existing matching-only resource boundary; do not claim protocol conformance from SDK tests alone.
- Protocol references: https://www.rfc-editor.org/rfc/rfc6749.html#section-2.2 ; https://www.rfc-editor.org/rfc/rfc7591.html#appendix-A.5.2 ; https://www.rfc-editor.org/rfc/rfc7636.html ; https://www.rfc-editor.org/rfc/rfc9207.html .
