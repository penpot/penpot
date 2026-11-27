# Driving Real User Gestures via Playwright

Use Playwright when the bug or behavior depends on Penpot's real input pipeline: pointer gestures, keyboard modifiers, drag/drop targeting, modifier propagation, hover/focus behavior, or alt-drag duplication. The plugin JS API and `penpot:execute_code` can bypass these paths by dispatching store/API operations directly.

## When `execute_code` Is Not Enough

`execute_code` runs in the plugin sandbox. It is excellent for creating shapes, calling Plugin API methods, and querying design data, but it does not faithfully reproduce all user gestures. If the issue involves interactive transforms, frame targeting during drop, drag previews, modifier keys, or canvas hit-testing, drive the browser with Playwright and inspect results via cljs-repl.

## Gesture Pattern

A reliable drag gesture generally needs:
- focus on the canvas first;
- key modifiers held from before mouse down until after mouse up;
- intermediate mouse move events, not just start/end;
- short waits so Penpot's drag pipeline observes the gesture;
- a trailing wait for the transaction to commit.

Alt-drag duplication example:

```javascript
async (page) => {
  await page.mouse.click(700, 700);
  await page.waitForTimeout(200);

  const startX = 821, startY = 565, endX = 821, endY = 815;
  await page.keyboard.down('Alt');
  await page.mouse.move(startX, startY);
  await page.waitForTimeout(100);
  await page.mouse.down();
  await page.waitForTimeout(100);
  for (let i = 1; i <= 10; i++) {
    const t = i / 10;
    await page.mouse.move(startX + (endX - startX) * t,
                          startY + (endY - startY) * t);
    await page.waitForTimeout(20);
  }
  await page.waitForTimeout(100);
  await page.mouse.up();
  await page.waitForTimeout(100);
  await page.keyboard.up('Alt');
  await page.waitForTimeout(500);
}
```

For the primary modifier (Ctrl on Linux/Windows, Command on macOS) use `ControlOrMeta`, not `Control`; see `mem:frontend/testing-playwright`.

## Coordinate Planning

Playwright mouse coordinates and `getBoundingClientRect()` use viewport CSS pixels, not screen coordinates or device pixels. Read the viewport controls' `viewBox` and rectangle at the time of the gesture; pan, zoom and panels change the mapping. Ruler labels can be frame-relative and are not a substitute for the viewBox.

For a point in Penpot coordinates, compute the browser coordinates inside `page.evaluate()` so the helper does not depend on functions in the Playwright process:

```javascript
const { x, y } = await page.evaluate(([px, py]) => {
  const svg = document.querySelector("svg.viewport-controls");
  const [vx, vy, vw] = svg.getAttribute("viewBox").split(/\s+/).map(Number);
  const rect = svg.getBoundingClientRect();
  const zoom = rect.width / vw;
  return { x: rect.left + (px - vx) * zoom, y: rect.top + (py - vy) * zoom };
}, [penpotX, penpotY]);
await page.mouse.click(x, y);
```

Check that `viewBox` height times the computed zoom approximately matches the rectangle height. Device pixel ratio affects the canvas backing pixels, not this CSS-pixel conversion. For state inspection use `mem:frontend/cljs-repl`.
