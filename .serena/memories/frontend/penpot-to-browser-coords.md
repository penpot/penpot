# Penpot Canvas → Playwright Viewport Coordinate Mapping

## Goal
Map Penpot shape coordinates (from the JS/ClojureScript API) to browser viewport CSS pixels
so that Playwright mouse actions (click, drag, hover) can target specific canvas objects.

## Key Facts

### Playwright coordinate system
Playwright mouse coordinates are **viewport CSS pixels**: `(0, 0)` is the top-left of the
browser's rendered content area (not the screen, not the OS window chrome).
`getBoundingClientRect()` returns the same coordinate system — they are directly compatible.

### Canvas element location
The Penpot canvas is rendered by two co-located elements:
- `<canvas>` — the rasterised render
- `<svg id="render">` — vector overlay
- `<svg class="...viewport-controls ...">` — interaction/control layer (has the `viewBox`)

Get the canvas origin with:
```js
document.querySelector("#render").getBoundingClientRect()
// => { left: 318, top: 0, width: 514, height: 586, ... }  (values vary with window size/panels)
```
The left offset (currently ~318 px) is caused by the left-side panel (layers, assets).

### Zoom and pan state
Available in two equivalent ways:

**1. App state (ClojureScript):**
```clojure
(let [wl (get @app.main.store/state :workspace-local)]
  {:zoom  (get wl :zoom)    ; scale factor: penpot-units → CSS pixels
   :vbox  (get wl :vbox)})  ; Rect {:x :y :width :height} — penpot coords of visible area
```

**2. SVG viewBox attribute (DOM):**
```js
document.querySelector("svg.viewport-controls, [class*='viewport-controls']")
  .getAttribute("viewBox")
// => "670 658.31 224 255.38"  i.e. "vbox.x vbox.y vbox.width vbox.height"
```
Both sources are live and always in sync.

### Coordinate conversion formula
```
viewport_x = canvas_left  + (penpot_x - vbox.x) * zoom
viewport_y = canvas_top   + (penpot_y - vbox.y) * zoom
```

Sanity check: `vbox.width * zoom ≈ canvas CSS width` (and same for height). ✓

### Device Pixel Ratio
The canvas physical pixel size = CSS size × DPR (observed DPR = 1.25, so canvas internal
size 642×732 vs CSS size 514×586). This does **not** affect the formula — both
`getBoundingClientRect()` and Playwright use CSS pixels.

### Ruler label offset
The on-screen rulers show coordinates offset from absolute Penpot coordinates (they display
frame-relative values, offset by ~the top-level frame's x/y). **Ignore for coordinate
mapping** — use `vbox` directly.

---

## ClojureScript Helper (paste into cljs REPL session)

```clojure
(defn penpot->viewport-coords
  "Convert Penpot canvas coordinates to browser viewport CSS pixel coordinates.
   Returns {:vp-x <number> :vp-y <number>} — pass directly to Playwright mouse actions."
  [penpot-x penpot-y]
  (let [state       @app.main.store/state
        wl          (get state :workspace-local)
        vbox        (get wl :vbox)
        zoom        (get wl :zoom)
        canvas      (js/document.querySelector "svg.viewport-controls, #render")
        canvas-rect (.getBoundingClientRect canvas)]
    {:vp-x (+ (.-left canvas-rect) (* (- penpot-x (:x vbox)) zoom))
     :vp-y (+ (.-top  canvas-rect) (* (- penpot-y (:y vbox)) zoom))}))
```

Usage example — click the center of a shape:
```clojure
(let [shape   (get-in @app.main.store/state [:files file-id :data :pages-index page-id :objects shape-id])
      cx      (+ (:x shape) (/ (:width shape) 2))
      cy      (+ (:y shape) (/ (:height shape) 2))
      {:keys [vp-x vp-y]} (penpot->viewport-coords cx cy)]
  ;; pass vp-x, vp-y to Playwright page.mouse.click(vp-x, vp-y)
  {:vp-x vp-x :vp-y vp-y})
```

---

## JavaScript equivalent (for use in Playwright scripts directly)

```js
function penpotToViewport(penpotX, penpotY) {
  // Read viewBox from the controls SVG (always in sync with app state)
  const svg = document.querySelector('[class*="viewport-controls"]');
  const [vbX, vbY, vbW, vbH] = svg.getAttribute('viewBox').split(' ').map(Number);
  const rect = svg.getBoundingClientRect();
  const zoom = rect.width / vbW;  // == rect.height / vbH
  return {
    x: rect.left + (penpotX - vbX) * zoom,
    y: rect.top  + (penpotY - vbY) * zoom,
  };
}
```

This function can be injected and called via `page.evaluate()` in Playwright:
```js
const {x, y} = await page.evaluate(
  ([px, py]) => penpotToViewport(px, py),
  [penpotX, penpotY]
);
await page.mouse.click(x, y);
```
