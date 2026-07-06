# MCP Clients Configuration

MCP Server se connect karne ke liye alag-alag clients use kar sakte ho.

---

## Option 1: Claude Desktop (Windows/macOS)

### Download
https://claude.ai/download

### Configuration File Location
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
  - Full path: `C:\Users\<username>\AppData\Roaming\Claude\claude_desktop_config.json`
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`

### Config File Content

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

### Steps:
1. File kholо (agar nahi hai toh banao)
2. Upar wala JSON paste karo
3. File save karo
4. Claude Desktop **completely quit karo** (window close nahi, File → Quit)
5. Claude Desktop dobara start karo
6. Prompt input mein "Search and tools" icon mein Penpot dikhega ✅

> **Note**: Claude Desktop sirf stdio transport support karta hai,
> isliye `mcp-remote` proxy use karna padta hai.

---

## Option 2: Claude Code (CLI)

Ek command se add karo:

```bash
claude mcp add penpot -t http http://localhost:4401/mcp
```

Verify karo:
```bash
claude mcp list
```

---

## Option 3: Direct HTTP/SSE (Advanced Clients)

Agar aapka MCP client HTTP directly support karta hai:

| Endpoint | URL |
|----------|-----|
| Modern Streamable HTTP | `http://localhost:4401/mcp` |
| Legacy SSE | `http://localhost:4401/sse` |

---

## Option 4: Antigravity IDE (Current Tool)

Ye app (jo aap abhi use kar rahe ho) already Penpot MCP se connected hai!

Available tools:
- `execute_code` — JavaScript code run karo Penpot mein
- `high_level_overview` — File overview dekho
- `penpot_api_info` — API info
- `export_shape` — Shape export karo
- `import_image` — Image import karo

---

## Test Karo — Kaam Kar Raha Hai?

Claude mein type karo:
```
"Penpot mein ek blue rectangle banao 200x200"
```

Agar design file mein rectangle ban gaya — sab kuch connected hai! 🎉

---

## Next Steps

- [Available Tools Reference →](./05-tools-reference.md)
- [Configuration Options →](./06-configuration.md)
