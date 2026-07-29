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

## Coordinate Planning

For reliably finding pixel positions of objects, see `mem:frontend/penpot-to-browser-coords`.