# Configuration — Environment Variables

MCP Server ko environment variables se configure kar sakte ho.

---

## .env File Kaise Banayein

```bash
# d:\penpot\mcp\ folder mein .env file banao
cp .env.example .env
```

Phir `.env` file edit karo:

```env
# Server Configuration
PENPOT_MCP_SERVER_HOST=localhost
PENPOT_MCP_SERVER_PORT=4401
PENPOT_MCP_WEBSOCKET_PORT=4402
PENPOT_MCP_REPL_PORT=4403

# Logging
PENPOT_MCP_LOG_LEVEL=info
PENPOT_MCP_LOG_DIR=./logs

# Remote Mode (production ke liye)
PENPOT_MCP_REMOTE_MODE=false
```

---

## Server Configuration Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PENPOT_MCP_SERVER_HOST` | `localhost` | MCP Server listen address |
| `PENPOT_MCP_SERVER_PORT` | `4401` | HTTP/SSE server port |
| `PENPOT_MCP_WEBSOCKET_PORT` | `4402` | WebSocket server port |
| `PENPOT_MCP_REPL_PORT` | `4403` | REPL/debug port |
| `PENPOT_MCP_REMOTE_MODE` | `false` | `true` = file system access disable |
| `PENPOT_MCP_DEVENV` | `false` | `true` = Penpot devenv tools enable |

---

## Logging Configuration

| Variable | Default | Options |
|----------|---------|---------|
| `PENPOT_MCP_LOG_LEVEL` | `info` | `trace`, `debug`, `info`, `warn`, `error` |
| `PENPOT_MCP_LOG_DIR` | (unset) | Log files ka directory path |

### Debug Mode Enable Karna

```env
PENPOT_MCP_LOG_LEVEL=debug
PENPOT_MCP_LOG_DIR=./logs
```

---

## Plugin Server Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PENPOT_MCP_PLUGIN_SERVER_HOST` | (local only) | Plugin server ka address |

---

## Port Conflicts Solve Karna

Agar koi port already use ho raha hai:

```env
# Example: Doosre ports use karo
PENPOT_MCP_SERVER_PORT=5401
PENPOT_MCP_WEBSOCKET_PORT=5402
PENPOT_MCP_PLUGIN_SERVER_PORT=5400
```

Phir Claude Desktop config bhi update karo:
```json
"args": ["-y", "mcp-remote", "http://localhost:5401/mcp", "--allow-http"]
```

---

## Multi-User / Remote Mode

Remote server par deploy karne ke liye:

```env
PENPOT_MCP_REMOTE_MODE=true
PENPOT_MCP_SERVER_HOST=0.0.0.0
PENPOT_MCP_PLUGIN_SERVER_HOST=0.0.0.0
```

> ⚠️ **Warning**: `0.0.0.0` use karne se sab interfaces par bind hoga.
> Untrusted networks mein caution se use karo!

Zyada details ke liye: [Multi-User Mode →](./08-multi-user-mode.md)
