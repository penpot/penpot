# Penpot MCP — Design Patterns & Learnings

> Yahan nayi learnings add hoti rahegi.
> Format: `## [Date] — [Topic]`

---

## 2026-07-06 — Plugin API: Property Setters vs Getters

**Seekha:** Penpot Plugin API mein kuch properties getter-only hain.

| Property | Writable? | Sahi Tarika |
|---|---|---|
| `shape.width` | ❌ Getter only | `shape.resize(w, h)` |
| `shape.height` | ❌ Getter only | `shape.resize(w, h)` |
| `shape.x` | ✅ Direct set | `shape.x = 100` |
| `shape.y` | ✅ Direct set | `shape.y = 100` |
| `shape.name` | ✅ Direct set | `shape.name = "..."` |
| `shape.fills` | ✅ Direct set | `shape.fills = [...]` |
| `shape.rotation` | ❌ | `shape.rotate(angle)` |

**Error jo aata hai agar galat karo:**
```
Cannot set property width of #<$G__28039$$> which has only a getter
```

---

## 2026-07-06 — Fill Format

```javascript
// Sahi fill format
shape.fills = [{
  fillType: 'solid',      // 'solid' | 'linear' | 'radial' | 'image'
  fillColor: '#7238B2',   // Hex color
  fillOpacity: 1          // 0 to 1
}];
```

---

## 2026-07-06 — Version Mismatch Warning

MCP Server 2.16.0 aur local Penpot 2.18.2 mein version mismatch hai.
- Warning aati hai plugin UI mein (orange text)
- **Safe to ignore** — most tools work fine
- Agar koi tool fail ho toh `penpot_api_info` se latest API check karo

---

## Template — Nayi Learning Add Karne Ka Format

```markdown
## YYYY-MM-DD — [Topic ka naam]

**Seekha:** [Kya seekha ek line mein]

**Details:**
[Code ya explanation]

**Kab use karo:**
[Use case]
```

---

*Naya seekhne pe mujhse kaho: "Yeh learning save karo" ya "/learn" command use karo*
