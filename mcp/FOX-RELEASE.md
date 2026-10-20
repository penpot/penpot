# Penpot MCP 2.18 transport compatibility image

This image is based on the official Penpot **2.18.0** source and runtime, not the development branch. It retains the release's plugin protocol and dependencies.

## Optional JSON-RPC POST replies

Set `PENPOT_MCP_JSON_RESPONSE=true` to use the MCP SDK's supported `application/json` replies for Streamable HTTP POST requests. Unset/false preserves the release's default SSE replies. Legacy SSE endpoints are unchanged.

This is a transport mitigation for reverse proxies/clients that prematurely close a POST SSE stream, not a claim that every historical disconnect has one root cause. It does not keep an absent browser plugin alive, renew editor ownership, retry a mutation, or remove authentication. A lost reply still requires exact-state reconciliation before retrying a write.

## Build and verification

```
docker build -f Dockerfile.fox-mcp -t foxsnow/penpot-mcp:2.18.0-json-20261020 .
```

The Docker build fails unless the server test suite, TypeScript checks, formatting and bundle compilation pass. `JsonResponse.test.ts` exercises the real HTTP transport with a synthetic WebSocket plugin, 200 sequential requests, correlation IDs and unauthenticated denial. That isolated fixture is not acceptance of an actual Penpot document; test the real plugin, current file, save and reload independently.

The server now retains and closes its HTTP listener and session transports during `stop()`, allowing bounded integration-test cleanup. No frontend/backend or database migration is included. To roll back, restore `penpotapp/mcp:2.18.0` and remove the optional environment variable; keep existing database and asset volumes.
