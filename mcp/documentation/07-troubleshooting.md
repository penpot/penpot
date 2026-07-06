# Troubleshooting — Common Problems aur Solutions

---

## ❌ Problem 1: Plugin Connect Nahi Ho Raha

**Symptoms**: Plugin UI mein "Not connected" status

**Causes aur Solutions**:

### A) MCP Server chal nahi raha
```bash
# Check karo server running hai
# Terminal mein yeh command check karo:
netstat -an | findstr 4401
netstat -an | findstr 4402

# Agar nahi chal raha toh start karo:
cd d:\penpot\mcp
pnpm run bootstrap
```

### B) Browser ne local network access block kiya
- Chrome popup aaya tha "Allow local network access?" — **Allow** karo
- Brave browser: Shield disable karo Penpot site ke liye
- Firefox try karo (koi restriction nahi)

### C) Galat URL
Plugin URL hona chahiye exactly:
```
http://localhost:4400/manifest.json
```
`https://` nahi, `http://` — aur port `4400`!

---

## ❌ Problem 2: `Cannot set property width` Error

**Error message**:
```
Cannot set property width of #<$G__28039$$> which has only a getter
```

**Solution**:
```javascript
// ❌ Galat
shape.width = 200;

// ✅ Sahi
shape.resize(200, shape.height);  // Width change, height same rakhо
shape.resize(200, 300);            // Dono change karo
```

---

## ❌ Problem 3: Version Mismatch Warning

**Warning**: Orange text in plugin UI showing version mismatch

**Solution**: Yeh warning safe hai — ignore karo.
- MCP Server 2.16.0 aur Penpot 2.18.2 ke beech minor version mismatch hai
- Most tools correctly kaam karte hain
- Agar koi specific tool fail ho: `penpot_api_info` se latest API check karo

---

## ❌ Problem 4: Claude Desktop Mein Penpot Nahi Dikh Raha

**Solution**:
1. Config file sahi jagah hai? `%APPDATA%\Claude\claude_desktop_config.json`
2. JSON valid hai? [JSON Validator](https://jsonlint.com/) se check karo
3. Claude Desktop **completely quit** kiya? (File → Quit, sirf window close nahi)
4. Dobara start karo

**Config check**:
```json
{
    "mcpServers": {
        "penpot": {
            "command": "npx",
            "args": ["-y", "mcp-remote", "http://localhost:4401/mcp", "--allow-http"]
        }
    }
}
```

---

## ❌ Problem 5: Tab Suspend Ho Gayi / Connection Lost

**Problem**: Browser ne Penpot tab suspend kar di, MCP connection toot gayi

**Solution**:

Chrome mein:
1. Settings → Performance
2. "Always keep these sites active" mein Penpot URL add karo
3. Ya Penpot tab pin karo (right-click → Pin Tab)

---

## ❌ Problem 6: `pnpm run bootstrap` Error

**Error**: `pnpm` command not found

```bash
# pnpm install karo
npm install -g pnpm

# Phir try karo
pnpm run bootstrap
```

**Error**: Script permission denied (Windows)
```bash
# Git Bash use karo (PowerShell nahi)
# Ya:
node scripts/bootstrap.js
```

---

## 🔍 Debug Tips

### Browser Console Logs Dekhna (F12 → Console)
```
WebSocket connection established    ← Plugin connected
WebSocket disconnected              ← Plugin disconnect hua
```

### MCP Server Logs
```bash
# Verbose logging enable karo
PENPOT_MCP_LOG_LEVEL=debug pnpm run bootstrap
```

### Port Check Karna (Windows PowerShell)
```powershell
netstat -an | Select-String "4400", "4401", "4402"
```

---

## Still Stuck?

1. MCP Server restart karo
2. Browser refresh karo  
3. Plugin reinstall karo (URL se dobara)
4. [GitHub Issues](https://github.com/penpot/penpot/issues) dekhna
