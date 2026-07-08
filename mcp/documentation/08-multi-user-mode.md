# Multi-User Mode (Advanced)

> ⚠️ **Warning**: Multi-user mode abhi development mein hai — testing purposes ke liye hai.

---

## Kya Hai Multi-User Mode?

Normal mode mein: **1 user** → 1 MCP Server → 1 Plugin

Multi-user mode mein: **Multiple users** → 1 MCP Server (shared) → Multiple Plugins

Useful hai jab:
- Team ke saare members ek hi server use karein
- Remote server par deploy karna ho
- Cloud mein run karna ho

---

## Limitations

Multi-user mode mein yeh kaam **nahi** karta:
- ❌ Local file system read/write (import/export tools)
- ❌ File system wale tools

Jo kaam karta hai:
- ✅ Design operations (shapes, colors, text)
- ✅ Code execution
- ✅ Design data queries

---

## Multi-User Mode Start Karna

```bash
cd d:\penpot\mcp
npm run bootstrap:multi-user
```

---

## Authentication Token

Multi-user mode mein har user ko token chahiye:

### MCP Client URL mein:
```
http://localhost:4401/mcp?userToken=YOUR_TOKEN
```

### Plugin mein:
Plugin source code mein hard-coded hai (testing ke liye).
Future mein Penpot automatically token provide karega.

---

## Redis Support (Scaling)

Multiple MCP Server instances ke liye Redis use kar sakte ho:

```env
PENPOT_MCP_REDIS_URI=redis://localhost:6379
```

Isse:
- Multiple server instances ek saath run kar sakte hain
- Pub/sub se tasks route hote hain correct instance ko

---

## Remote Deployment Configuration

```env
PENPOT_MCP_REMOTE_MODE=true
PENPOT_MCP_SERVER_HOST=0.0.0.0
PENPOT_MCP_PLUGIN_SERVER_HOST=0.0.0.0
PENPOT_MCP_REDIS_URI=redis://your-redis-host:6379
```

Parallel export requests limit karna:
```env
PENPOT_MCP_EXPORT_SHAPE_MAX_PARALLEL_REQUESTS=5
```

---

## Status

| Feature | Status |
|---------|--------|
| Basic multi-user support | ✅ Available (beta) |
| Token authentication | ⚠️ Hard-coded (testing only) |
| Redis scaling | ✅ Available |
| File system tools | ❌ Not supported |
| Auto token from Penpot | 🔄 Coming soon |
