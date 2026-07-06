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

## 2026-07-06 — createBoard vs createFrame

**Seekha:** Frame/Screen banana ho toh `penpot.createBoard()` use karo, `createFrame` exist nahi karta.

**Details:**
```javascript
// ❌ Galat — ERROR aayega
const f = penpot.createFrame(); // Error: createFrame is not a function

// ✅ Sahi
const board = penpot.createBoard();
board.resize(390, 844);
board.name = 'My Screen';
```

---

## 2026-07-06 — Shapes Board ke ANDAR Banana

**Seekha:** Agar shape board ke andar `appendChild` se nahi daali, toh wo page (Root Frame) par hoti hai. Prototype mein board empty dikhta hai.

**Details:**
```javascript
// ❌ Galat — shape page par hogi, board ke andar NAHI
const rect = penpot.createRectangle();
rect.x = 100; rect.y = 200;

// ✅ Sahi — pehle appendChild, PHIR coordinates
const rect = penpot.createRectangle();
board.appendChild(rect);       // board ke andar daalo
rect.x = board.x + 100;       // absolute = board.x + relative
rect.y = board.y + 200;
```

**Kab use karo:** Hamesha! Jab bhi koi shape kisi board/screen ke andar dikhani ho.

---

## 2026-07-06 — fontWeight Supported Values

**Seekha:** Default font mein sirf kuch specific font weights kaam karte hain.

**Details:**
```javascript
// ❌ Galat — ERROR
text.fontWeight = '600'; // Font weight '600' not supported

// ✅ Sahi values
text.fontWeight = '400'; // Regular
text.fontWeight = '500'; // Medium
text.fontWeight = '700'; // Bold
```

---


## 2026-07-06 — execute_code mein `return` Zaroori Hai

**Seekha:** `execute_code` tool mein response lene ke liye `return` lagana zaroori hai.

**Details:**
```javascript
// ❌ Galat — response nahi milega (log: "" aayega)
// Shape ban jaayegi lekin confirmation nahi
'Square created!';

// ✅ Sahi — result milega
return 'Square created!';
return 'Size: ' + shape.width + 'x' + shape.height;
```

**Kab use karo:** Hamesha! Bina `return` ke shape banti hai lekin MCP ko pata nahi chalta ki kya hua.

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
