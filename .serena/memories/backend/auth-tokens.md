# Backend access tokens

- Start at `app.http.access-token` for token authentication and `app.rpc/wrap-authentication` for command restrictions. For session and management shared-key handling see `mem:backend/http-rpc`; MCP OAuth provisioning is `mem:mcp/oauth`.
- Personal and MCP tokens have distinct issuers and database types. Authentication requires issuer and row type to agree and the token's `uid` to match the row's profile; expired, undecryptable or unknown-issuer tokens do not authenticate.
- Personal tokens require `:access-tokens`. MCP tokens require `:mcp` or `:access-tokens` and a route that opts into `::actoken/allow-mcp`; assets do not. An MCP token can call only RPCs declaring `::rpc/token-type "mcp"`, which also reject sessions and personal tokens.
- Blocking a profile invalidates sessions, not access tokens. A token-only command that requires an active profile must check that itself; see `send-mcp-feedback`. Authentication does not replace resource authorization: `mem:backend/permissions`.
