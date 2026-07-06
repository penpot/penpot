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
shape.fills = [
  {
    fillType: 'solid',       // 'solid' | 'linear' | 'radial' | 'image'
    fillColor: '#7238B2',    // Hex color string
    fillOpacity: 1           // 0.0 to 1.0
  }
];
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
```

### Rectangle Banana

```javascript
const rect = penpot.createRectangle();
rect.x = 0;
rect.y = 0;
rect.resize(300, 200);
rect.fills = [{ fillType: 'solid', fillColor: '#4A90E2', fillOpacity: 1 }];
```

### Frame/Container Banana

```javascript
const frame = penpot.createFrame();
frame.x = 0;
frame.y = 0;
frame.resize(1440, 900);
frame.name = "Desktop Frame";
```
