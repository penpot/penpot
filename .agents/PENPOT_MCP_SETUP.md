# Penpot MCP Setup — Quick Reference

## Ek Line Mein

Local Penpot (Docker, port 9001) + MCP Server (Node.js, port 4401) + MCP Plugin (port 4400) = Antigravity directly Penpot canvas pe kaam kar sakta hai.

---

## Setup Status (Last Verified: 2026-07-06)

| Component | Status | URL |
|---|---|---|
| Local Penpot | ✅ Docker mein chal raha hai | http://localhost:9001 |
| MCP Server | ✅ Built + Running | http://localhost:4401/mcp |
| Plugin Server | ✅ Running | http://localhost:4400 |
| Plugin Connection | ✅ Connected | Browser mein open hai |
| IDE MCP Config | ✅ Configured | `mcp_config.json` |

---

## Nayi Chat Mein Kya Karna Hai

### 1. Check karo server chal raha hai ya nahi
```cmd
cmd /c "netstat -ano | findstr 4401"
```
Agar output aata hai → server already chal raha hai, Step 3 pe jao.
Agar kuch nahi aata → Step 2 pe jao.

### 2. Server start karo
```cmd
cmd /c "npx -y pnpm@11.9.0 --filter mcp-server --filter mcp-plugin -r --parallel run start"
```
*(Run from: `d:\penpot\mcp\`)*

### 3. Browser mein plugin connect karo
- `http://localhost:9001` → Login: `test@test.com` / `Test1234!`
- File open karo → Plugins (🧩) → Plugin already installed hogi → Open → "Connect to MCP server"

### 4. IDE refresh karo
- MCP servers panel refresh karo → `penpot` server connected dikhega

---

## MCP Tools Use Karna (Antigravity ke liye)

```javascript
// Shape banana
const rect = penpot.createRectangle();
rect.name = 'Test';
rect.x = 100; rect.y = 100;
rect.resize(200, 100);  // width aur height sirf resize() se set karo
rect.fills = [{ fillType: 'solid', fillColor: '#7238B2', fillOpacity: 1 }];
```

---

## Full Memory Path
`.serena/memories/penpot-mcp/core.md` — detailed reference
