# MCP OAuth

- Start at `backend/src/app/http/mcp_oauth.clj` for endpoints, `app.mcp.oauth-state` for Redis state, `app.rpc.commands.access-token` for provisioning, and `mcp/packages/server/src/McpAuthentication.ts` for transport authentication. Client registration and metadata: `mem:mcp/oauth/clients`. Deployment configuration: `mcp/README.md`.

## Contract and deployment

- Registration does not grant access: the logged-in user must approve. Reuse the existing usable MCP user token; replace an expired token, one with an obsolete issuer, or one this instance cannot decrypt. Approval enables MCP but does not connect a plugin. There is no refresh token, `expires_in` or new token lifetime.
- MCP transport matches user tokens to connected plugins; it does not introspect them independently on every request. The logged-in Penpot instance remains the content-access boundary. Do not silently change token or resource-server semantics.
- OAuth requires multi-user mode. Backend Redis holds short-lived authorization state and is required even when the Node Redis bridge is disabled. Devenv OAuth is a manual opt-in; keep the single-user launcher available for agentic tooling.
- Public transport and discovery routes traverse different proxies: keep the OAuth well-known metadata path routed to the backend and the MCP stream routed to Node. The bearer header takes precedence over `?userToken=`; an invalid header must not silently fall back.

## Approval and exchange

- Preserve OAuth error bodies and protocol field names rather than the ordinary RPC error shape. Session auth applies to the approval request and decision; public metadata, registration and token routes allow noncredentialed CORS.
- Bind the pending request to the logged-in profile and hashed session using compare-and-replace. Re-read after binding: concurrent requests can race. Check the exact Origin, session binding and CSRF value before atomically consuming the decision. Denial creates no token. Never put the fixed token into frontend state or the browser redirect.
- Validate client ID, callback, resource, PKCE and client authentication before atomically consuming the authorization code: invalid credentials must not burn it, and only one valid exchange may succeed. Resolve remote client metadata again at exchange. Code state stores the approved token row ID, not the token; regeneration deletes that row, so an outstanding code cannot yield a replacement token.
- State creation must confirm Redis `SET NX EX`; consume and replace compare the raw validated record atomically and replacement preserves TTL. Redis errors fail closed. Provisioning and regeneration lock the profile row in a transaction, including when no token exists, to serialize concurrent changes.

## Verification and limits

- Cover registration and redirect rules, code replay and concurrency, session/CSRF binding and token reuse in `mcp_oauth_clients_test.clj`, `mcp_oauth_http_test.clj`, `mcp_oauth_state_test.clj` and `rpc_access_tokens_test.clj`, alongside MCP authentication/HTTP and frontend approval tests.
- Live OpenAI, Codex and Claude compatibility and marketplace acceptance remain unverified. Check token persistence without `expires_in` and acceptance of the current matching-only resource boundary; SDK tests alone do not prove client compatibility.
