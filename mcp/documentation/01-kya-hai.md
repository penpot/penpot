# Penpot MCP Kya Hai?

## Overview

**Penpot MCP** (Model Context Protocol) ek AI integration layer hai jo:
- Penpot design tool ko AI clients (jaise Claude) se connect karta hai
- AI ko Penpot files mein directly design karne deta hai
- Code-to-design aur design-to-code workflows enable karta hai

---

## Architecture

```
┌─────────────────┐         ┌──────────────────┐         ┌──────────────┐
│   AI Client     │   MCP   │   MCP Server     │WebSocket│  MCP Plugin  │
│ (Claude, etc.)  │◄───────►│  Port: 4401      │◄───────►│  Port: 4400  │
└─────────────────┘         └──────────────────┘         └──────────────┘
                                                                  │
                                                                  │ Plugin API
                                                                  ▼
                                                         ┌──────────────┐
                                                         │   Penpot     │
                                                         │  (Browser)   │
                                                         └──────────────┘
```

### 3 Main Components:

| Component | Port | Kya karta hai |
|-----------|------|---------------|
| **MCP Server** | 4401 | AI clients ke liye tools provide karta hai |
| **Plugin Server** | 4400 | Plugin files serve karta hai (manifest.json) |
| **WebSocket Server** | 4402 | Server aur Plugin ke beech real-time connection |

---

## Data Flow

1. **AI Client** (Claude) → MCP Server ko tool call bhejta hai
2. **MCP Server** → WebSocket se Plugin ko task bhejta hai
3. **Plugin** → Penpot Plugin API use karke design operation karta hai
4. **Plugin** → Response wapas MCP Server ko bhejta hai
5. **MCP Server** → Result AI Client ko deta hai

---

## Kya Kar Sakta Hai?

- ✅ Design file ka overview dekhna
- ✅ Shapes, text, components banana
- ✅ Colors, fills, strokes set karna
- ✅ Layers rename karna
- ✅ Components export karna
- ✅ Images import karna
- ✅ Custom JavaScript code execute karna Penpot context mein
- ✅ Design data query karna

---

## Version Information

- MCP Server Version: 2.16.0
- Tested with Penpot: 2.18.2
- Node.js Required: v22.x

> **Note**: Version mismatch warning safe hai — ignore kar sakte ho
