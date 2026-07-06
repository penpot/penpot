# Plugin ko Penpot se Connect Karna

## Important: Browser Restrictions ke Baare Mein

> ⚠️ **Chrome v142+ Users**: Browser private network access (PNA) restrict karta hai.
> Popup aayega local network access ke liye — **Allow** karo.
> 
> **Brave Browser**: "Shield" disable karo Penpot website ke liye.
> 
> **Firefox**: Koi restriction nahi — best browser for this!

---

## Step-by-Step Plugin Install

### Step 1: Penpot Open Karo

Browser mein kholо:
- Local: `http://localhost:9001`
- Cloud: `https://design.penpot.app`

### Step 2: Design File Kholо

Koi bhi file open karo ya nayi file banao.

### Step 3: Plugin Menu Kholо

Toolbar mein **Plugins** icon dhundho (ya Main menu → Plugins).

### Step 4: Plugin URL Daalo

**"Write a plugin URL"** field mein type karo:

```
http://localhost:4400/manifest.json
```

Phir **INSTALL** button dabao.

### Step 5: Plugin Launch Karo

Installed plugins mein se Penpot MCP plugin launch karo.

### Step 6: Connect Karo

Plugin UI mein **"Connect to MCP server"** button dabao.

**Status change hogi:**
```
❌ Not connected  →  ✅ Connected to MCP server
```

---

## Connection Verify Karna

### Plugin UI mein:
- Green status: "Connected to MCP server" ✅
- Orange warning: Version mismatch (safe to ignore) ⚠️
- Red: Connection failed ❌

### Browser Console mein (F12):
```
WebSocket connection established: ws://localhost:4402
```

### MCP Server Terminal mein:
```
New plugin connection established
```

---

## ⚠️ Important Rules

> **Plugin UI band mat karo!**
> Agar plugin window close ho gayi, MCP server ka connection toot jayega.
> Saara kaam plugin UI ke open rehne par depend karta hai.

> **Tab Active Rakhо!**
> Chrome inactive tabs ko suspend kar deta hai.
> Settings → Performance → "Always keep these sites active" mein Penpot add karo.

---

## Next Steps

- [MCP Client (Claude) Configure Karo →](./04-mcp-clients.md)
