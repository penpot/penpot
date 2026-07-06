# MCP Tools Reference

Yeh saare tools hain jo Penpot MCP Server provide karta hai AI clients ko.

---

## execute_code

**Sabse powerful tool** — arbitrary JavaScript code run karo Penpot Plugin context mein.

> ⚠️ **ZAROORI: `return` keyword lagao!**
> 
> Bina `return` ke response hamesha `{"log": ""}` aayega — shape ban jaayegi lekin result nahi milega.
> 
> ```javascript
> // ❌ Galat — log empty aayega
> 'Square created!';
> 
> // ✅ Sahi — result milega
> return 'Square created!';
> ```

```javascript
// Example: Red circle banana
const circle = penpot.createEllipse();
circle.x = 100;
circle.y = 100;
circle.resize(200, 200);
circle.fills = [{ fillColor: '#FF0000', fillOpacity: 1 }];
circle.name = "My Circle";
return 'Circle created: ' + circle.width + 'x' + circle.height;
```

**Kab use karo**: Jab koi specific design task karna ho jo doosre tools se nahi ho.

---

## high_level_overview

Current Penpot file ka overview deta hai — pages, layers, components sab.

```
Tool: high_level_overview
Input: (kuch nahi)
Output: File structure, page list, layer hierarchy
```

**Kab use karo**: Pehle samajhne ke liye ki file mein kya hai.

---

## penpot_api_info

Penpot Plugin API ki information deta hai — available methods aur properties.

```
Tool: penpot_api_info
Input: (optional) specific API section
Output: API documentation
```

**Kab use karo**: Jab pata nahi ki koi specific property ka naam kya hai.

---

## export_shape

Kisi shape ya component ko image file mein export karo.

```
Tool: export_shape
Input: shape ID, format (png/svg/pdf), scale
Output: Exported file
```

**Kab use karo**: Design assets export karne ke liye.

---

## import_image

Local image file ko Penpot mein import karo.

```
Tool: import_image
Input: file path
Output: Penpot mein imported image shape
```

**Kab use karo**: Local images ko design mein use karna ho.

---

## Plugin API — Shape Properties Reference

### Writable Properties (Direct Set Kar Sakte Ho)

```javascript
shape.x = 100;              // X position
shape.y = 200;              // Y position
shape.name = "My Shape";    // Layer name
shape.fills = [{...}];      // Fill colors
shape.strokes = [{...}];    // Border/stroke
shape.opacity = 0.8;        // Opacity (0-1)
```

### Getter-Only Properties (Direct Set NAHI Kar Sakte)

```javascript
// ❌ Galat
shape.width = 200;   // Error!
shape.height = 100;  // Error!

// ✅ Sahi
shape.resize(200, 100);  // Width aur height dono ek saath
shape.rotate(45);         // Rotation
```

---

## Fill Format

```javascript
// ✅ Sahi — fillType zaroori NAHI hai
shape.fills = [
  {
    fillColor: '#7238B2',    // Hex color string (CAPS mein, e.g. '#FF5533')
    fillOpacity: 1           // 0.0 to 1.0
  }
];

// fillType optional hai — sirf gradient/image ke liye chahiye
shape.fills = [{ fillType: 'linear', fillColor: '#7238B2', fillOpacity: 1 }];
```

---

## Stroke Format

```javascript
shape.strokes = [
  {
    strokeColor: '#000000',
    strokeOpacity: 1,
    strokeWidth: 2,
    strokeType: 'solid',     // 'solid' | 'dashed' | 'dotted'
    strokePosition: 'center' // 'center' | 'inner' | 'outer'
  }
];
```

---

## Common Code Patterns

### Text Create Karo

```javascript
const text = penpot.createText("Hello World");
text.x = 50;
text.y = 50;
text.name = "Heading";
text.fontSize = 16;
// ⚠️ fontWeight: sirf '400', '500', '700', 'bold' use karo
// '600' jaise values ERROR dete hain default font mein
```

### Rectangle Banana

```javascript
const rect = penpot.createRectangle();
rect.x = 0;
rect.y = 0;
rect.resize(300, 200);
rect.fills = [{ fillColor: '#4A90E2', fillOpacity: 1 }]; // fillType optional
```

### Board (Frame/Screen) Banana

```javascript
// ✅ Sahi — penpot.createBoard() use karo
const board = penpot.createBoard();
board.x = 0;
board.y = 0;
board.resize(390, 844);
board.name = 'Screen 1';
board.fills = [{ fillColor: '#0F0F1A', fillOpacity: 1 }];

// ❌ Galat — createFrame() exist nahi karta!
// const frame = penpot.createFrame(); // ERROR!
```

### Shapes Board ke ANDAR Banana (ZAROORI PATTERN)

```javascript
// ❌ Galat — shape page par banti hai, board ke andar NAHI
const rect = penpot.createRectangle();
rect.x = 100; rect.y = 200; // ye page coordinates hain
rect.resize(300, 150);
// Prototype mein kuch nahi dikhega!

// ✅ Sahi — pehle appendChild, phir coordinates set karo
const board = penpot.createBoard();
board.x = 0; board.y = 0;
board.resize(390, 844);

const rect = penpot.createRectangle();
board.appendChild(rect);        // 1. Pehle board ke andar daalo
rect.x = board.x + 20;         // 2. Phir absolute position set karo
rect.y = board.y + 100;        //    (board.x + relative offset)
rect.resize(350, 150);
rect.fills = [{ fillColor: '#7238B2', fillOpacity: 1 }];
```

### Prototyping — Boards ke Beech Navigation

```javascript
const screen1 = penpot.createBoard();
screen1.name = 'Screen 1';
screen1.x = 0; screen1.y = 0;
screen1.resize(390, 844);

const screen2 = penpot.createBoard();
screen2.name = 'Screen 2';
screen2.x = 440; screen2.y = 0; // Side by side rakhो
screen2.resize(390, 844);

// Button shape board ke andar banana
const btn = penpot.createRectangle();
screen1.appendChild(btn);
btn.x = screen1.x + 20;
btn.y = screen1.y + 500;
btn.resize(350, 56);
btn.name = 'CTA Button';

// Interaction add karo
btn.addInteraction({
  trigger: 'click',
  action: {
    type: 'navigate-to',
    destination: screen2,
    animation: { type: 'slide', way: 'in', direction: 'left', duration: 300, easing: 'ease-in-out' }
  }
});
```

---

## ⚠️ Known Gotchas (Real Mistakes se Seekha)

| # | Galti | Error / Symptom | Sahi Tarika |
|---|-------|-----------------|-------------|
| 1 | `return` nahi lagaya | `{"log": ""}` — shape banti hai par response nahi milta | Hamesha `return` lagao |
| 2 | `penpot.createFrame()` use kiya | `createFrame is not a function` | `penpot.createBoard()` use karo |
| 3 | `fontWeight: '600'` use kiya | `Font weight '600' not supported` | `'400'`, `'500'`, `'700'` use karo |
| 4 | Shape board ke bahar create ki | Prototype mein kuch nahi dikhta, shapes page par bikhri hoti hain | Pehle `board.appendChild(shape)`, phir coordinates |
| 5 | `fillType: 'solid'` hamesha diya | Kaam karta hai par zaroori nahi | Sirf `fillColor` + `fillOpacity` kaafi hai |
