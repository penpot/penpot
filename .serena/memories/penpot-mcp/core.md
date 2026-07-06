# Penpot MCP Server — Core Memory

## TL;DR
Local Penpot + local MCP server setup. Everything runs on localhost. No remote URLs needed.

**Learnings log:** `mem:penpot-mcp/design-patterns` — API gotchas, design tricks, nayi discoveries yahan save hoti hain.

---

## Architecture

```
[Antigravity / AI IDE]
        │  mcp_config.json → http://localhost:4401/mcp
        ▼
[MCP Server]  ← node dist/index.js  (port 4401)
        │  WebSocket
        ▼
[Penpot MCP Plugin]  ← served at http://localhost:4400
        │  Plugin API
        ▼
[Local Penpot]  ← http://localhost:9001  (Docker)
```

---

## All Running Ports

| Service | Port | URL |
|---|---|---|
| Local Penpot (Docker) | 9001 | http://localhost:9001 |
| MCP Server (HTTP/Streamable) | 4401 | http://localhost:4401/mcp |
| MCP Server (Legacy SSE) | 4401 | http://localhost:4401/sse |
| Plugin Web Server | 4400 | http://localhost:4400 |
| Plugin Manifest | 4400 | http://localhost:4400/manifest.json |
| WebSocket (Plugin↔Server) | 4402 | ws://localhost:4402 |
| REPL (debug) | 4403 | http://localhost:4403 |

---

## MCP Config Location

Two config files (both should have same content):
- `d:\penpot\.agents\mcp_config.json`  ← workspace config
- `C:\Users\VICTUS\.gemini\config\mcp_config.json`  ← global config

**Correct content:**
```json
{
  "mcpServers": {
    "penpot": {
      "serverUrl": "http://localhost:4401/mcp"
    }
  }
}
```

---

## Source Code Location

- **MCP root:** `d:\penpot\mcp\`
- **MCP Server package:** `d:\penpot\mcp\packages\server\`
- **MCP Plugin package:** `d:\penpot\mcp\packages\plugin\`
- **MCP Common package:** `d:\penpot\mcp\packages\common\`
- **Build output (server):** `d:\penpot\mcp\packages\server\dist\index.js`
- **Build output (plugin):** `d:\penpot\mcp\packages\plugin\dist\`

---

## How to Start MCP Server (Every Session)

### Step 1: Check if already running
```powershell
# Check if port 4401 is listening
netstat -ano | findstr "4401"
```

### Step 2: Build (only if dist/index.js doesn't exist)
```cmd
cmd /c "npx -y pnpm@11.9.0 --filter mcp-server run build"
cmd /c "npx -y pnpm@11.9.0 --filter mcp-plugin run build"
```

### Step 3: Start servers
```cmd
cmd /c "npx -y pnpm@11.9.0 --filter mcp-server --filter mcp-plugin -r --parallel run start"
```
Run this from: `d:\penpot\mcp\`

> **Note:** `pnpm` is not globally installed. Always use `npx -y pnpm@11.9.0` via `cmd /c`.
> PowerShell blocks `.ps1` scripts — always use `cmd /c "..."` for npm/npx/pnpm commands.

### Step 4: Connect Plugin in Penpot
1. Open browser → `http://localhost:9001`
2. Login: `test@test.com` / `Test1234!`
3. Open any design file
4. Click Plugins button (🧩 icon in toolbar)
5. Plugin URL: `http://localhost:4400/manifest.json` → Install
6. Open plugin → Click "Connect to MCP server"
7. Status should show: **"● Connected"**

---

## Penpot Local Credentials

- **URL:** http://localhost:9001
- **Email:** test@test.com
- **Password:** Test1234!
- **Penpot version:** 2.18.2 (running in Docker)

> Note: There is a version mismatch warning (MCP 2.16.0 vs Penpot 2.18.2).
> This is just a warning — MCP still works. Most tools function correctly.

---

## Available MCP Tools

MCP server name in IDE config: **`penpot`**

| Tool | Description |
|---|---|
| `execute_code` | Run JavaScript using Penpot Plugin API on canvas |
| `high_level_overview` | Get overview of Penpot types/concepts |
| `penpot_api_info` | Get API docs for specific type (e.g. `type: "Rectangle"`) |
| `export_shape` | Export a shape as image |
| `import_image` | Import an image into Penpot |

---

## Using execute_code Tool

**IMPORTANT — API rules:**
- Use `rect.resize(width, height)` — NOT `rect.width = ...` (getter only, no setter)
- Use `rect.x = n` and `rect.y = n` directly (these are settable)
- `rect.name = "..."` is settable
- `rect.fills = [{ fillType: 'solid', fillColor: '#RRGGBB', fillOpacity: 1 }]`

**Example — Create rectangle:**
```javascript
const rect = penpot.createRectangle();
rect.name = 'My Shape';
rect.x = 100;
rect.y = 100;
rect.resize(200, 100);
rect.fills = [{ fillType: 'solid', fillColor: '#7238B2', fillOpacity: 1 }];
return 'Done: ' + rect.name;
```

**Example — Read current page shapes:**
```javascript
const page = penpot.currentPage;
const shapes = page.findAll(() => true);
return shapes.map(s => s.name + ' (' + s.type + ')').join('\n');
```

---

## Checking MCP Tools Work

Run this to verify end-to-end:
```javascript
// In execute_code tool:
return 'MCP working! Page: ' + penpot.currentPage.name;
```

---

## Common Issues & Fixes

| Problem | Fix |
|---|---|
| `pnpm` not found | Use `cmd /c "npx -y pnpm@11.9.0 ..."` |
| PowerShell blocks npm | Always use `cmd /c "..."` wrapper |
| Port 4401 not listening | Run start command from `d:\penpot\mcp\` |
| Plugin shows "Not connected" | Click "Connect to MCP server" in plugin UI |
| `dist/index.js` missing | Run build command first |
| Version mismatch warning | Safe to ignore — still works |
| `Cannot set property width` | Use `rect.resize(w, h)` instead of `rect.width = w` |
| `demo-client` build fails | Ignore — only server + plugin needed |

---

## Verify Full Pipeline

1. Check server running: `netstat -ano | findstr "4401"`
2. Call `high_level_overview` tool with `{"type": "shape"}` — should return type info
3. Call `execute_code` with `{"code": "return penpot.currentPage.name;"}`
4. If step 3 returns page name → full pipeline working ✅

---

## mem: references
- This is the top-level memory for the MCP setup
- No sub-memories needed currently
