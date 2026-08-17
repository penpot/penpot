import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { TestContext } from '../framework/types';

// Colors.
// Exercises the context-level color helpers shapesColors() and replaceColor(),
// plus the ColorShapeInfo metadata they expose.

function rect(ctx: TestContext) {
  const r = ctx.penpot.createRectangle();
  ctx.board.appendChild(r);
  return r;
}

describe('Colors', () => {
  test('shapesColors lists the colors used by shapes', (ctx) => {
    const r = rect(ctx);
    r.fills = [{ fillColor: '#abcdef', fillOpacity: 1 }];

    const colors = ctx.penpot.shapesColors([r]);
    expect(colors.length).toBeGreaterThan(0);

    const entry = colors.find((c) => c.color === '#abcdef');
    expect(entry).toBeDefined();
    if (entry) {
      expect(entry.shapesInfo).toBeDefined();
      expect(entry.shapesInfo.length).toBeGreaterThan(0);
      expect(entry.shapesInfo[0].property).toBe('fill');
      expect(entry.shapesInfo[0].shapeId).toBe(r.id);
    }
  });

  test('replaceColor swaps a solid fill color', (ctx) => {
    const r = rect(ctx);
    r.fills = [{ fillColor: '#111111', fillOpacity: 1 }];

    // replaceColor matches by exact color-attrs equality, so the old color must
    // include the same opacity the fill has.
    ctx.penpot.replaceColor(
      [r],
      { color: '#111111', opacity: 1 },
      { color: '#222222', opacity: 1 },
    );

    const fills = r.fills;
    if (Array.isArray(fills)) {
      expect(fills[0].fillColor).toBe('#222222');
    }
  });
});
